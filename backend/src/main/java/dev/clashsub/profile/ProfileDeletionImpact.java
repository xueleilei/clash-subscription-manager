package dev.clashsub.profile;

import java.util.UUID;

public record ProfileDeletionImpact(UUID profileId, String profileVersion, String draftVersion, int groupCount,
        int ruleCount, boolean hasPublished, String message) { }
