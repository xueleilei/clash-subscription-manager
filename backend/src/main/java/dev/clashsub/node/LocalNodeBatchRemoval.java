package dev.clashsub.node;

import java.util.List;
import java.util.UUID;

/** Deletion preview containing names and versions only, never connection credentials. */
public record LocalNodeBatchRemoval(List<Item> nodes, String ownerRevision, List<UUID> affectedProfiles,
        List<UUID> publishedProfiles, List<NamedNode> dependentNodes) {
    public LocalNodeBatchRemoval {
        nodes = List.copyOf(nodes);
        affectedProfiles = List.copyOf(affectedProfiles);
        publishedProfiles = List.copyOf(publishedProfiles);
        dependentNodes = List.copyOf(dependentNodes);
    }
    public record Item(UUID id, String name, String version) { }
    public record NamedNode(UUID id, String name) { }
}
