package dev.clashsub.node;

import java.util.List;
import java.util.UUID;

public record LocalNodeBatchDeleteCommand(List<Item> nodes, Long ownerRevision) {
    public record Item(UUID id, Long expectedVersion) { }
}
