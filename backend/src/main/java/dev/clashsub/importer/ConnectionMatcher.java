package dev.clashsub.importer;
import dev.clashsub.common.DefinitionHasher;
import dev.clashsub.node.*;
import dev.clashsub.profile.TargetRef;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
@Component
public class ConnectionMatcher {
    private final JsonMapper json;
    public ConnectionMatcher(JsonMapper json) { this.json = json; }
    public List<ImportedNodePreview> suggest(NodeCatalog imported, NodeCatalog existing) {
        var remote = new HashMap<String, List<UUID>>();
        for (var node : existing.nodes().values()) {
            if (node.sourceId() == null) { continue; }
            String key = fingerprint(node, existing, new HashSet<>()); if (key != null) { remote.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node.id()); }
        }
        return imported.nodes().values().stream().sorted(Comparator.comparing(node -> node.id().toString())).map(node -> {
            String key = fingerprint(node, imported, new HashSet<>());
            var candidates = key == null ? List.<UUID>of() : remote.getOrDefault(key, List.of()).stream().sorted().toList();
            return new ImportedNodePreview(node.id(), node.originalName(), node.protocol(), candidates.size() == 1 ? candidates.getFirst() : null, candidates);
        }).toList();
    }
    private String fingerprint(NodeSpec node, NodeCatalog catalog, Set<UUID> path) {
        if (node == null || path.size() >= 50 || !path.add(node.id())) { return null; }
        var value = json.createObjectNode().put("protocol", node.protocol()).set("connection", canonical(node.connection()));
        var target = node.dialerRef();
        if (target != null) {
            if (target.kind() == TargetRef.Kind.NODE) {
                String child = fingerprint(catalog.get(target.id()), catalog, path); if (child == null) { return null; } value.put("dialer", child);
            } else if (target.kind() == TargetRef.Kind.DIRECT || target.kind() == TargetRef.Kind.REJECT) { value.put("dialer", target.kind().name()); }
            else { return null; }
        }
        path.remove(node.id()); return DefinitionHasher.sha256(json.writeValueAsBytes(value));
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) { var result = json.createObjectNode(); value.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.set(entry.getKey(), canonical(entry.getValue()))); return result; }
        if (value.isArray()) { var result = json.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result; } return value;
    }
}
