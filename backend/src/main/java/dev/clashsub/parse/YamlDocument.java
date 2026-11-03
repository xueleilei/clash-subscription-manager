package dev.clashsub.parse;

import tools.jackson.databind.node.ObjectNode;

public record YamlDocument(ObjectNode value) {
    public YamlDocument { value = value.deepCopy(); }
    @Override public ObjectNode value() { return value.deepCopy(); }
    @Override public String toString() { return "YamlDocument[redacted]"; }
}
