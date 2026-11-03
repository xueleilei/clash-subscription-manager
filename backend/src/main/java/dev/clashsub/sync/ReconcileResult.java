package dev.clashsub.sync;
import dev.clashsub.profile.ProfileDocument;
import java.util.List;
import java.util.UUID;
public record ReconcileResult(ProfileDocument document, int removedMembers, List<UUID> rejectedRuleIds, List<UUID> emptyGroupIds) {
    public ReconcileResult { rejectedRuleIds = List.copyOf(rejectedRuleIds); emptyGroupIds = List.copyOf(emptyGroupIds); }
}
