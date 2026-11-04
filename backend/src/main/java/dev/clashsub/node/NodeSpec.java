package dev.clashsub.node;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.clashsub.profile.TargetRef;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record NodeSpec(UUID id, UUID sourceId, String originalName, String protocol, ObjectNode connection,
        TargetRef dialerRef, @JsonInclude(JsonInclude.Include.NON_NULL) String localGroupName) {
    public NodeSpec(UUID id, UUID sourceId, String originalName, String protocol, ObjectNode connection, TargetRef dialerRef) {
        this(id, sourceId, originalName, protocol, connection, dialerRef, null);
    }
    public NodeSpec { connection = connection.deepCopy(); }
    public NodeSpec withLocalGroupName(String name) {
        if (Objects.equals(name, localGroupName)) { return this; }
        return new NodeSpec(id, sourceId, originalName, protocol, connection, dialerRef, name);
    }
    @Override public ObjectNode connection() { return connection.deepCopy(); }
    @Override public String toString() { return "NodeSpec[id=" + id + ", protocol=" + protocol + "]"; }
}
