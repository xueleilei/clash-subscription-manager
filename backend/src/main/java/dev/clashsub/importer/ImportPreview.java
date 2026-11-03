package dev.clashsub.importer;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.profile.ProfileDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record ImportPreview(UUID id, String ownerRevision, Instant expiresAt, ProfileDocument document, List<ImportedNodePreview> nodes, ValidationReport report) {
    public ImportPreview { nodes = List.copyOf(nodes); }
    @Override public String toString() { return "ImportPreview[id=" + id + "]"; }
}
