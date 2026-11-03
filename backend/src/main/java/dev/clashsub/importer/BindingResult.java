package dev.clashsub.importer;
import java.util.List;
import java.util.UUID;
public record BindingResult(UUID localNodeId, UUID remoteNodeId, List<UUID> affectedProfiles, int copiedNodes) {
    public BindingResult { affectedProfiles = List.copyOf(affectedProfiles); }
}
