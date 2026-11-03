package dev.clashsub.profile;

public record ProfileDraftDO(String id, String ownerId, String profileId, long version, String documentCipher) {
    @Override public String toString() { return "ProfileDraftDO[id=" + id + ", version=" + version + "]"; }
}
