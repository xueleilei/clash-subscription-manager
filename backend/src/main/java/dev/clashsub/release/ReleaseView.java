package dev.clashsub.release;

import java.time.Instant;
import java.util.UUID;

public record ReleaseView(UUID id, UUID profileId, String version, String definitionHash, String yamlDigest,
        int nodeCount, String reason, Instant createdAt) { }
