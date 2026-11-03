package dev.clashsub.parse;

import tools.jackson.databind.node.ObjectNode;

public record ParsedNode(String originalName, String protocol, ObjectNode connection, String originalDialerName) {
    public ParsedNode { connection = connection.deepCopy(); }
    @Override public ObjectNode connection() { return connection.deepCopy(); }
    @Override public String toString() { return "ParsedNode[protocol=" + protocol + "]"; }
}
