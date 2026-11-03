package dev.clashsub.release;

import dev.clashsub.common.ValidationReport;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishPreview(UUID profileId, String expectedVersion, String definitionHash, String yamlDigest, String yaml,
        int nodeCount, int groupCount, int ruleCount, List<UUID> emptyGroupIds, Map<UUID, String> sourceVersions, ValidationReport report) {
    public PublishPreview { emptyGroupIds = List.copyOf(emptyGroupIds); sourceVersions = Map.copyOf(sourceVersions); }
    @Override public String toString() { return "PublishPreview[profileId=" + profileId + "]"; }
}
