package dev.clashsub.node;
import java.util.List;
import java.util.UUID;
public record LocalNodeRemoval(UUID id, String version, String ownerRevision, List<UUID> affectedProfiles,
        List<UUID> publishedProfiles, List<UUID> dependentNodes) { }
