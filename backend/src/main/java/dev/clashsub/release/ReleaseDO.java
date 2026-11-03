package dev.clashsub.release;

import java.time.Instant;

public record ReleaseDO(String id, String ownerId, String profileId, long version, String definitionHash, String yamlDigest,
        int nodeCount, String reason, String payloadCipher, Instant createTime) {
    @Override public String toString() { return "ReleaseDO[id=" + id + ", version=" + version + "]"; }
}
