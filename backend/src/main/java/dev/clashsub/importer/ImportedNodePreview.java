package dev.clashsub.importer;
import java.util.List;
import java.util.UUID;
public record ImportedNodePreview(UUID id, String originalName, String protocol, UUID suggestedRemoteId, List<UUID> candidateRemoteIds) {
    public ImportedNodePreview { candidateRemoteIds = List.copyOf(candidateRemoteIds); }
}
