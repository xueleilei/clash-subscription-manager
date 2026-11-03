package dev.clashsub.release;

public record TokenDO(String id, String ownerId, long version, String currentReleaseId, String tokenHash, String tokenCipher) {
    @Override public String toString() { return "TokenDO[profileId=" + id + "]"; }
}
