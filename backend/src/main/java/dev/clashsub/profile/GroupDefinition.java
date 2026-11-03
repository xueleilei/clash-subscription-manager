package dev.clashsub.profile;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record GroupDefinition(UUID id, String name, String type, List<TargetRef> members, ObjectNode options) {
    public GroupDefinition { members = List.copyOf(members); options = options.deepCopy(); }
    @Override public ObjectNode options() { return options.deepCopy(); }
    @Override public String toString() { return "GroupDefinition[id=" + id + "]"; }
}
