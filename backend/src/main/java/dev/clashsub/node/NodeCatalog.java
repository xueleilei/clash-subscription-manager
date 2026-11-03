package dev.clashsub.node;

import java.util.Map;
import java.util.UUID;

public record NodeCatalog(Map<UUID, NodeSpec> nodes) {
    public NodeCatalog { nodes = Map.copyOf(nodes); }
    public NodeSpec get(UUID id) { return nodes.get(id); }
    public static NodeCatalog empty() { return new NodeCatalog(Map.of()); }
    @Override public String toString() { return "NodeCatalog[count=" + nodes.size() + "]"; }
}
