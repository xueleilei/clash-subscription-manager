package dev.clashsub.common;

import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.ProfileState;
import dev.clashsub.source.SourceState;
import java.util.Map;
import java.util.UUID;

public record OwnerSnapshot(UUID ownerId, long dataRevision, Map<UUID, SourceState> sources, NodeCatalog nodes,
        Map<UUID, ProfileState> profiles) {
    public OwnerSnapshot { sources = Map.copyOf(sources); profiles = Map.copyOf(profiles); }
    @Override public String toString() { return "OwnerSnapshot[ownerId=" + ownerId + ", dataRevision=" + dataRevision + "]"; }
}
