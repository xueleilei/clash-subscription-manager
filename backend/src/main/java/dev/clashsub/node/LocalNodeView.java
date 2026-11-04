package dev.clashsub.node;
import java.util.List;
import java.util.UUID;
public record LocalNodeView(NodeSpec node, String version, List<UUID> affectedProfiles, List<UUID> publishedProfiles,
        String regionOverride) { }
