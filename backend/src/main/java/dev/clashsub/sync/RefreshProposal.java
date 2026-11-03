package dev.clashsub.sync;
import dev.clashsub.common.OwnerSnapshot;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.release.PreparedRelease;
import dev.clashsub.source.SourceDO;
import java.util.Map;
import java.util.UUID;
public record RefreshProposal(OwnerSnapshot before, SourceDO source, UUID attemptId, UUID decisionId, byte[] body, NodeDiff diff,
        Map<UUID, ProfileDocument> drafts, Map<UUID, PreparedRelease> releases, ChangeSummary changes) {
    public RefreshProposal { body = body.clone(); drafts = Map.copyOf(drafts); releases = Map.copyOf(releases); }
    @Override public byte[] body() { return body.clone(); }
    @Override public String toString() { return "RefreshProposal[attemptId=" + attemptId + "]"; }
}
