package dev.clashsub.importer;
import java.time.Instant;
public record PreviewDO(String id, String ownerId, String kind, long dataRevision, String payloadCipher, Instant createTime, Instant expiresAt, boolean consumed) {
    @Override public String toString() { return "PreviewDO[id=" + id + ", kind=" + kind + "]"; }
}
