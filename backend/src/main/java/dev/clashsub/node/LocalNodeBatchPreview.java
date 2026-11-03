package dev.clashsub.node;

import java.util.List;

public record LocalNodeBatchPreview(String ownerRevision, List<LocalNodeCommand> nodes) {
    public LocalNodeBatchPreview { nodes = List.copyOf(nodes); }
    @Override public String toString() { return "LocalNodeBatchPreview[redacted]"; }
}
