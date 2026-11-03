package dev.clashsub.sync;
import java.time.Instant;
public record AttemptDO(String id, String ownerId, String sourceId, long configVersion, long snapshotVersion, String status, String payloadCipher, Instant createTime, Instant updateTime) {
    @Override public String toString() { return "AttemptDO[id=" + id + ", status=" + status + "]"; }
}
