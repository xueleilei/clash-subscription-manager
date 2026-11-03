package dev.clashsub.importer;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.ProfileDocument;
import java.util.List;
public record ImportPayload(String yaml, ProfileDocument document, NodeCatalog nodes, List<ImportedNodePreview> suggestions, ValidationReport report) {
    public ImportPayload { suggestions = List.copyOf(suggestions); }
    @Override public String toString() { return "ImportPayload[nodes=" + nodes.nodes().size() + "]"; }
}
