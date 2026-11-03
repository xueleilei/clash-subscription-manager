package dev.clashsub.source;

import java.time.Instant;

public record SourceDO(String id, String ownerId, String name, long configVersion, long snapshotVersion,
        boolean enabled, int refreshIntervalSeconds, String inputFormat, String requestCipher, String syncStatus,
        Instant lastAttemptTime, Instant lastSuccessTime, Instant nextRefreshTime, String lastErrorCode,
        Instant createTime, Instant updateTime) {
    @Override public String toString() { return "SourceDO[id=" + id + ", configVersion=" + configVersion + "]"; }
}
