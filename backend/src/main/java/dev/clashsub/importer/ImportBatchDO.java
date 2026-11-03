package dev.clashsub.importer;
import java.time.Instant;
public record ImportBatchDO(String id, String ownerId, String profileId, String payloadCipher, Instant createTime) {
    @Override public String toString() { return "ImportBatchDO[id=" + id + "]"; }
}
