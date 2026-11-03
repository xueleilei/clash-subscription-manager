package dev.clashsub.profile;

import java.time.Instant;

public record ProfileDO(String id, String ownerId, String name, long version, String currentReleaseId,
        Instant createTime, Instant updateTime) { }
