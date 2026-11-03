package dev.clashsub.sync;
import java.time.Instant;
public record EmptyDecisionDO(String id, String ownerId, String sourceId, long configVersion, long snapshotVersion, String candidateDigest, String state, String payloadCipher, Instant createTime) {
    @Override public String toString() { return "EmptyDecisionDO[id=" + id + ", state=" + state + "]"; }
}
