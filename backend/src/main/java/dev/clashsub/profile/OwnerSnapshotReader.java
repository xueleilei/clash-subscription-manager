package dev.clashsub.profile;

import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.release.ReleaseRepository;
import dev.clashsub.source.SourceRepository;
import dev.clashsub.source.SourceState;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OwnerSnapshotReader {
    private final OwnerRevisionGuard revisions;
    private final SourceRepository sources;
    private final NodeRepository nodes;
    private final ProfileRepository profiles;
    private final ReleaseRepository releases;
    public OwnerSnapshotReader(OwnerRevisionGuard revisions, SourceRepository sources, NodeRepository nodes, ProfileRepository profiles, ReleaseRepository releases) {
        this.revisions = revisions; this.sources = sources; this.nodes = nodes; this.profiles = profiles; this.releases = releases;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OwnerSnapshot read(UUID owner) {
        long revision = revisions.current(owner);
        var sourceStates = new LinkedHashMap<UUID, SourceState>();
        sources.list(owner).forEach(row -> { var id = UUID.fromString(row.id()); sourceStates.put(id, new SourceState(id, row.configVersion(), row.snapshotVersion())); });
        var catalog = nodes.catalog(owner); var states = new LinkedHashMap<UUID, ProfileState>();
        for (var row : profiles.list(owner)) {
            var id = UUID.fromString(row.id()); var published = row.currentReleaseId() == null ? null : releases.require(owner, id, UUID.fromString(row.currentReleaseId())).snapshot();
            states.put(id, new ProfileState(id, row.version(), profiles.draft(owner, id), published));
        }
        return new OwnerSnapshot(owner, revision, sourceStates, catalog, states);
    }
}
