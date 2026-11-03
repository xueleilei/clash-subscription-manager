package dev.clashsub.node;

import dev.clashsub.profile.TargetRef;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record NodeSpec(UUID id, UUID sourceId, String originalName, String protocol, ObjectNode connection, TargetRef dialerRef) {
    public NodeSpec { connection = connection.deepCopy(); }
    @Override public ObjectNode connection() { return connection.deepCopy(); }
    @Override public String toString() { return "NodeSpec[id=" + id + ", protocol=" + protocol + "]"; }
}
