package dev.clashsub.profile;

import java.util.UUID;

public record DraftSnapshot(UUID profileId, long version, ProfileDocument document) {
    @Override public String toString() { return "DraftSnapshot[profileId=" + profileId + ", version=" + version + "]"; }
}
