package dev.clashsub.node;

public record NodeDO(String id, String ownerId, String sourceId, String nameHash, String originalName, String protocol,
        String region, String regionOverride, long snapshotVersion, String connectionCipher) {
    @Override public String toString() { return "NodeDO[id=" + id + "]"; }
}
