package dev.clashsub.importer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record BindingPreview(UUID id, String ownerRevision, Instant expiresAt, UUID localNodeId, UUID remoteNodeId, List<UUID> affectedProfiles, List<String> changedFields) {
    public BindingPreview { affectedProfiles = List.copyOf(affectedProfiles); changedFields = List.copyOf(changedFields); }
}
