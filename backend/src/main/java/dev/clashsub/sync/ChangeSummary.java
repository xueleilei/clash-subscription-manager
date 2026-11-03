package dev.clashsub.sync;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public record ChangeSummary(int added, int updated, int removed, Map<UUID, ProfileChange> profiles) {
    public ChangeSummary { profiles = Map.copyOf(profiles); }
    public record ProfileChange(int removedMembers, List<UUID> rejectedRuleIds, List<UUID> emptyGroupIds) {
        public ProfileChange { rejectedRuleIds = List.copyOf(rejectedRuleIds); emptyGroupIds = List.copyOf(emptyGroupIds); }
    }
    public static ChangeSummary none() { return new ChangeSummary(0, 0, 0, Map.of()); }
}
