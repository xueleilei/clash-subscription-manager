package dev.clashsub.profile;

import java.time.Instant;
import java.util.UUID;

public record ProfileView(UUID id, String name, String version, String draftVersion, UUID currentReleaseId,
        String publishedVersion, String draftDefinitionHash, String publishedDefinitionHash, boolean hasUnpublishedNodeChanges, Instant createTime, Instant updateTime) { }
