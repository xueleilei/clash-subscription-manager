package dev.clashsub.sync;

import dev.clashsub.node.*;
import dev.clashsub.parse.ParsedNode;
import dev.clashsub.profile.TargetRef;
import java.util.*;

/** Identity includes exact original name; inactive identities may return without restoring any references. */
public record NodeDiff(List<NodeSpec> replacement, Set<UUID> added, Set<UUID> updated, Set<UUID> removed, NodeCatalog catalog) {
    public NodeDiff { replacement = List.copyOf(replacement); added = Set.copyOf(added); updated = Set.copyOf(updated); removed = Set.copyOf(removed); }
    public boolean changed() { return !added.isEmpty() || !updated.isEmpty() || !removed.isEmpty(); }
    public static NodeDiff calculate(UUID source, List<ParsedNode> parsed, List<NodeSpec> history, NodeCatalog previous) {
        var identities = new HashMap<String, UUID>(); history.forEach(node -> identities.put(node.originalName(), node.id()));
        for (var node : parsed) { identities.computeIfAbsent(node.originalName(), ignored -> UUID.randomUUID()); }
        var result = new ArrayList<NodeSpec>(); var added = new HashSet<UUID>(); var updated = new HashSet<UUID>(); var removed = new HashSet<UUID>();
        var catalog = new LinkedHashMap<>(previous.nodes());
        previous.nodes().values().stream().filter(node -> source.equals(node.sourceId())).forEach(node -> { removed.add(node.id()); catalog.remove(node.id()); });
        for (var node : parsed) {
            var id = identities.get(node.originalName()); var dialer = dialer(node.originalDialerName(), identities);
            var spec = new NodeSpec(id, source, node.originalName(), node.protocol(), node.connection(), dialer); result.add(spec); catalog.put(id, spec); removed.remove(id);
            var old = previous.get(id); if (old == null) { added.add(id); } else if (!old.equals(spec)) { updated.add(id); }
        }
        return new NodeDiff(result, added, updated, removed, new NodeCatalog(catalog));
    }
    private static TargetRef dialer(String name, Map<String, UUID> identities) {
        if (name == null) { return null; }
        if (name.equals("DIRECT")) { return TargetRef.direct(); } if (name.equals("REJECT")) { return TargetRef.reject(); }
        var id = identities.get(name); return id == null ? TargetRef.unresolved(name) : TargetRef.node(id);
    }
    @Override public String toString() { return "NodeDiff[added=" + added.size() + ", updated=" + updated.size() + ", removed=" + removed.size() + "]"; }
}
