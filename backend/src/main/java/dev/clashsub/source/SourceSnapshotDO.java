package dev.clashsub.source;

import java.time.Instant;

public record SourceSnapshotDO(String id, String ownerId, String sourceId, long snapshotVersion, String payloadCipher, Instant createTime) {
    @Override public String toString() { return "SourceSnapshotDO[id=" + id + "]"; }
}
