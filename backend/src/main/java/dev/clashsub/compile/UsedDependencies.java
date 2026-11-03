package dev.clashsub.compile;

import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeSpec;
import java.util.Map;
import java.util.UUID;

public record UsedDependencies(Map<UUID, NodeSpec> nodes, ValidationReport report) {
    public UsedDependencies { nodes = Map.copyOf(nodes); }
    @Override public String toString() { return "UsedDependencies[count=" + nodes.size() + "]"; }
}
