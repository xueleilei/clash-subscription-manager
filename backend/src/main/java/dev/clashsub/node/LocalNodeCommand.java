package dev.clashsub.node;

import dev.clashsub.profile.TargetRef;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record LocalNodeCommand(Long expectedVersion, String name, String protocol, ObjectNode connection,
        TargetRef dialerRef, UUID localGroupId, String regionOverride) {
    public LocalNodeCommand(Long expectedVersion, String name, String protocol, ObjectNode connection, TargetRef dialerRef) {
        this(expectedVersion, name, protocol, connection, dialerRef, null, null);
    }
    public LocalNodeCommand { if (connection != null) { connection = connection.deepCopy(); } }
    @Override public ObjectNode connection() { return connection == null ? null : connection.deepCopy(); }
    @Override public String toString() { return "LocalNodeCommand[redacted]"; }
}
