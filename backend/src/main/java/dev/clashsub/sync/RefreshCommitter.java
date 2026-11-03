package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.profile.ProfileRepository;
import dev.clashsub.release.ReleaseRepository;
import dev.clashsub.source.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefreshCommitter {
    private final OwnerRevisionGuard revisions;
    private final SourceRepository sources;
    private final NodeRepository nodes;
    private final ProfileRepository profiles;
    private final ReleaseRepository releases;
    private final RefreshStateStore state;
    public RefreshCommitter(OwnerRevisionGuard revisions, SourceRepository sources, NodeRepository nodes, ProfileRepository profiles, ReleaseRepository releases, RefreshStateStore state) {
        this.revisions = revisions; this.sources = sources; this.nodes = nodes; this.profiles = profiles; this.releases = releases; this.state = state;
    }
    @Transactional public CommitResult commit(RefreshProposal proposal) {
        var snapshot = proposal.before(); UUID owner = snapshot.ownerId(), source = UUID.fromString(proposal.source().id());
        revisions.compareAndIncrement(owner, snapshot.dataRevision()); sources.lock(owner, source); state.assertRunning(owner, proposal.attemptId()); state.assertSource(owner, proposal.source());
        if (proposal.decisionId() != null) { state.assertPending(owner, proposal.decisionId(), proposal.source()); state.markDecision(owner, proposal.decisionId(), "CLEARED"); }
        // Verify all inputs after taking the owner revision lock, before making any effective change.
        verifyInputs(snapshot);
        boolean changed = proposal.diff().changed() || proposal.source().snapshotVersion() == 0;
        Instant now = Instant.now();
        if (changed) {
            long next = proposal.source().snapshotVersion() + 1;
            nodes.replaceSource(owner, source, next, proposal.diff().replacement());
            sources.saveSnapshot(owner, source, next, new StoredSourceSnapshot(proposal.body(), proposal.diff().replacement()), now);
            sources.advanceSnapshot(owner, source, proposal.source().configVersion(), proposal.source().snapshotVersion(), next, now);
        } else { sources.unchangedSuccess(owner, source, proposal.source().configVersion(), proposal.source().snapshotVersion(), now); }
        applyProfiles(proposal, proposal.decisionId() == null ? "SOURCE_REFRESH" : "EMPTY_CLEAR");
        var outcome = new RefreshOutcome(proposal.attemptId(), source, changed ? "SUCCESS" : "UNCHANGED", proposal.decisionId(), proposal.changes(), ValidationReport.ok());
        state.finish(owner, outcome); return new CommitResult(outcome);
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void verifyInputs(OwnerSnapshot snapshot) {
        UUID owner = snapshot.ownerId();
        var currentSources = sources.list(owner);
        if (currentSources.size() != snapshot.sources().size()) { throw ApiException.conflict(); }
        for (var row : currentSources) {
            var expected = snapshot.sources().get(UUID.fromString(row.id()));
            if (expected == null || expected.configVersion() != row.configVersion() || expected.snapshotVersion() != row.snapshotVersion()) { throw ApiException.conflict(); }
        }
        var currentProfiles = profiles.list(owner); if (currentProfiles.size() != snapshot.profiles().size()) { throw ApiException.conflict(); }
        for (var row : currentProfiles) {
            var id = UUID.fromString(row.id()); var expected = snapshot.profiles().get(id);
            if (expected == null || expected.profileVersion() != row.version() || profiles.draft(owner, id).version() != expected.draft().version()) { throw ApiException.conflict(); }
        }
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void applyProfiles(RefreshProposal proposal, String reason) {
        var snapshot = proposal.before(); UUID owner = snapshot.ownerId();
        var ids = new TreeSet<UUID>(); ids.addAll(proposal.drafts().keySet()); ids.addAll(proposal.releases().keySet());
        for (var id : ids) {
            var expected = snapshot.profiles().get(id); var draft = proposal.drafts().get(id);
            if (draft != null) { profiles.updateDraft(owner, id, expected.draft().version(), draft); }
            var prepared = proposal.releases().get(id);
            if (prepared != null) {
                var release = releases.create(owner, id, prepared, proposal.diff().catalog(), reason);
                profiles.pointRelease(owner, id, expected.profileVersion(), release.id());
            } else { profiles.touch(owner, id, expected.profileVersion()); }
        }
    }
}
