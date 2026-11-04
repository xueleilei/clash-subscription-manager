package dev.clashsub.node;

import java.util.UUID;

public record NodeSummary(UUID id, UUID sourceId, String originalName, String protocol, String region, String snapshotVersion,
        UUID localGroupId, String localGroupName) { }
