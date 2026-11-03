package dev.clashsub.release;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiValidationException;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.profile.ProfileRepository;
import dev.clashsub.source.SourceRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReleaseCommitter {
    private final OwnerRevisionGuard revisions;
    private final ProfileRepository profiles;
    private final SourceRepository sources;
    private final ReleaseRepository releases;
    public ReleaseCommitter(OwnerRevisionGuard revisions, ProfileRepository profiles, SourceRepository sources, ReleaseRepository releases) {
        this.revisions = revisions; this.profiles = profiles; this.sources = sources; this.releases = releases;
    }
    @Transactional
    public ReleaseView commit(OwnerSnapshot snapshot, UUID profileId, PreparedRelease prepared) {
        if (!prepared.valid()) { throw new ApiValidationException(prepared.validation()); }
        var expected = snapshot.profiles().get(profileId); if (expected == null) { throw ApiException.missing(); }
        UUID owner = snapshot.ownerId(); revisions.compareAndIncrement(owner, snapshot.dataRevision());
        var profile = profiles.require(owner, profileId); var draft = profiles.draft(owner, profileId);
        if (profile.version() != expected.profileVersion() || draft.version() != expected.draft().version()) { throw ApiException.conflict(); }
        var currentSources = sources.list(owner);
        if (currentSources.size() != snapshot.sources().size()) { throw ApiException.conflict(); }
        for (var row : currentSources) {
            var source = snapshot.sources().get(UUID.fromString(row.id()));
            if (source == null || source.configVersion() != row.configVersion() || source.snapshotVersion() != row.snapshotVersion()) { throw ApiException.conflict(); }
        }
        profiles.updateDraft(owner, profileId, draft.version(), prepared.definition());
        var release = releases.create(owner, profileId, prepared, snapshot.nodes(), "MANUAL");
        profiles.pointRelease(owner, profileId, profile.version(), release.id());
        return release;
    }
}
