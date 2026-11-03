package dev.clashsub.sync;

import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.release.ReleaseRepository;
import dev.clashsub.source.SourceRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetentionService {
    private final RetentionMapper mapper;
    private final ReleaseRepository releases;
    private final SourceRepository sources;
    private final RefreshStateStore state;
    private final OwnerRevisionGuard revisions;
    private final NodeRepository nodes;
    private final TransactionTemplate transaction;
    public RetentionService(RetentionMapper mapper, ReleaseRepository releases, SourceRepository sources, RefreshStateStore state,
            OwnerRevisionGuard revisions, NodeRepository nodes, TransactionTemplate transaction) {
        this.mapper = mapper; this.releases = releases; this.sources = sources; this.state = state;
        this.revisions = revisions; this.nodes = nodes; this.transaction = transaction;
    }
    public RetentionResult clean(Instant now) {
        var result = new RetentionResult(0, 0, 0, 0);
        for (var owner : mapper.owners()) { result = result.plus(Objects.requireNonNull(transaction.execute(tx -> cleanOwner(UUID.fromString(owner), now)))); }
        return result;
    }
    private RetentionResult cleanOwner(UUID owner, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(30));
        revisions.compareAndIncrement(owner, revisions.current(owner)); var protectedSnapshots = new HashSet<SnapshotKey>(); int removedReleases = 0;
        sources.list(owner).forEach(source -> protectedSnapshots.add(new SnapshotKey(source.id(), source.snapshotVersion())));
        state.pending(owner).forEach(decision -> protectedSnapshots.add(new SnapshotKey(decision.sourceId().toString(), Long.parseLong(decision.snapshotVersion()))));
        for (var profile : mapper.profiles(owner.toString())) {
            UUID profileId = UUID.fromString(profile.id()); int historical = 0;
            for (var release : releases.list(owner, profileId)) {
                boolean current = release.id().toString().equals(profile.currentReleaseId());
                if (current || historical++ < 100) {
                    releases.require(owner, profileId, release.id()).snapshot().sourceVersions().forEach((source, version) -> protectedSnapshots.add(new SnapshotKey(source.toString(), version)));
                } else { removedReleases += mapper.deleteRelease(owner.toString(), release.id().toString()); }
            }
        }
        int removedSnapshots = 0;
        for (var row : mapper.oldSnapshots(owner.toString(), cutoff)) {
            if (!protectedSnapshots.contains(new SnapshotKey(row.sourceId(), row.snapshotVersion()))) { removedSnapshots += mapper.deleteSnapshot(owner.toString(), row.id()); }
        }
        int attempts = mapper.deleteAttempts(owner.toString(), cutoff); mapper.deleteResolvedDecisions(owner.toString(), cutoff);
        mapper.deleteExpiredPreviews(owner.toString(), now);
        return new RetentionResult(attempts, removedReleases, removedSnapshots, nodes.cleanTombstoneSecrets(owner, cutoff));
    }
    private record SnapshotKey(String source, long version) { }
}
