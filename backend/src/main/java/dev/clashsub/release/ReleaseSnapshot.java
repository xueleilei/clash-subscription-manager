package dev.clashsub.release;

import dev.clashsub.profile.ProfileDocument;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReleaseSnapshot(UUID id, UUID profileId, long version, ProfileDocument definition,
        Map<UUID, Long> sourceVersions, String yaml, Instant createdAt, String reason) {
    public ReleaseSnapshot { sourceVersions = Map.copyOf(sourceVersions); }
    @Override public String toString() { return "ReleaseSnapshot[id=" + id + ", version=" + version + "]"; }
}
