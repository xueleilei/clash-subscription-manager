package dev.clashsub.node;

import java.util.List;
import java.util.UUID;

public record LocalNodeBatchResult(List<UUID> ids, List<UUID> affectedProfiles) {
    public LocalNodeBatchResult { ids = List.copyOf(ids); affectedProfiles = List.copyOf(affectedProfiles); }
}
