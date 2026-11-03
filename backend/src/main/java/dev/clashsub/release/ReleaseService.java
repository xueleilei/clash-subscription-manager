package dev.clashsub.release;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiValidationException;
import dev.clashsub.common.DefinitionHasher;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.compile.MihomoValidator;
import dev.clashsub.compile.ProfileCompiler;
import dev.clashsub.profile.DraftGuard;
import dev.clashsub.profile.OwnerSnapshotReader;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService {
    private final OwnerSnapshotReader snapshots;
    private final DraftGuard guard;
    private final ProfileCompiler compiler;
    private final MihomoValidator core;
    private final ReleaseCommitter committer;
    private final DefinitionHasher hashes;
    private final ReleaseRepository releases;
    public ReleaseService(OwnerSnapshotReader snapshots, DraftGuard guard, ProfileCompiler compiler, MihomoValidator core, ReleaseCommitter committer, DefinitionHasher hashes, ReleaseRepository releases) {
        this.snapshots = snapshots; this.guard = guard; this.compiler = compiler; this.core = core; this.committer = committer; this.hashes = hashes; this.releases = releases;
    }
    @Transactional(propagation = Propagation.NEVER)
    public PreparedRelease prepare(UUID profileId, ProfileDocument document, OwnerSnapshot snapshot) {
        var versions = new LinkedHashMap<UUID, Long>(); snapshot.sources().forEach((id, source) -> versions.put(id, source.snapshotVersion()));
        var compiled = compiler.compile(profileId, document, snapshot.nodes(), versions); var report = compiled.report();
        if (compiled.valid()) { report = report.merge(core.validate(compiled.compiled().yaml())); }
        return new PreparedRelease(document, compiled.compiled(), report, snapshot.nodes());
    }
    /** Automatic upstream updates must never adopt an unpublished local edit or deletion. */
    @Transactional(propagation = Propagation.NEVER)
    public PreparedRelease preparePublished(UUID profileId, ProfileDocument document, OwnerSnapshot candidate, ReleaseSnapshot previous) {
        var catalog = new LinkedHashMap<>(candidate.nodes().nodes());
        var publishedNodes = releases.require(candidate.ownerId(), profileId, previous.id()).nodes();
        for (var node : publishedNodes.nodes().values()) {
            if (node.sourceId() == null) { catalog.put(node.id(), node); }
        }
        var pinned = new OwnerSnapshot(candidate.ownerId(), candidate.dataRevision(), candidate.sources(),
                new dev.clashsub.node.NodeCatalog(catalog), candidate.profiles());
        return prepare(profileId, document, pinned);
    }
    @Transactional(propagation = Propagation.NEVER)
    public ReleaseView publish(UUID owner, UUID profileId, long expectedDraftVersion, ProfileDocument submittedDocument) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var snapshot = snapshots.read(owner); var profile = require(snapshot, profileId, expectedDraftVersion);
            var document = submittedDocument == null ? profile.draft().document() : submittedDocument;
            guard.validate(profile.draft().document(), document, snapshot.nodes());
            var prepared = prepare(profileId, document, snapshot);
            if (!prepared.valid()) { throw new ApiValidationException(prepared.validation()); }
            try { return committer.commit(snapshot, profileId, prepared); }
            catch (ApiException exception) { if (exception.status() != 409 || attempt == 2) { throw exception; } }
        }
        throw ApiException.conflict();
    }
    @Transactional(propagation = Propagation.NEVER)
    public PublishPreview preview(UUID owner, UUID profileId, long expectedVersion, ProfileDocument submittedDocument) {
        var snapshot = snapshots.read(owner); var profile = require(snapshot, profileId, expectedVersion);
        var document = submittedDocument == null ? profile.draft().document() : submittedDocument;
        guard.validate(profile.draft().document(), document, snapshot.nodes()); var prepared = prepare(profileId, document, snapshot);
        Map<UUID, String> versions = new LinkedHashMap<>();
        if (prepared.compiled() != null) { prepared.compiled().sourceVersions().forEach((id, version) -> versions.put(id, Long.toString(version))); }
        return new PublishPreview(profileId, Long.toString(expectedVersion), hashes.hash(document), prepared.compiled() == null ? null : prepared.compiled().digest(),
                prepared.valid() ? prepared.compiled().yaml() : "", prepared.compiled() == null ? 0 : prepared.compiled().usedNodeIds().size(), document.groups().size(), document.rules().size(),
                document.groups().stream().filter(group -> group.members().isEmpty()).map(dev.clashsub.profile.GroupDefinition::id).toList(), versions, prepared.validation());
    }
    private ProfileState require(OwnerSnapshot snapshot, UUID id, long expected) {
        var profile = snapshot.profiles().get(id); if (profile == null) { throw ApiException.missing(); }
        if (profile.draft().version() != expected) { throw ApiException.conflict(); } return profile;
    }
}
