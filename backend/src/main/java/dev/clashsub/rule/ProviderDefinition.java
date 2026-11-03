package dev.clashsub.rule;

import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record ProviderDefinition(UUID id, String name, String type, String url, String behavior, String format,
        long interval, ObjectNode options) {
    public ProviderDefinition { options = options.deepCopy(); }
    @Override public ObjectNode options() { return options.deepCopy(); }
    @Override public String toString() { return "ProviderDefinition[id=" + id + "]"; }
}
