package dev.clashsub.source;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record RemovalPreview(UUID id, String ownerRevision, Instant expiresAt, UUID sourceId, int nodeCount, List<UUID> affectedProfiles) {
    public RemovalPreview { affectedProfiles = List.copyOf(affectedProfiles); }
}
