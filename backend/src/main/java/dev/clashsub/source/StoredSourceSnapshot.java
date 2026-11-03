package dev.clashsub.source;

import dev.clashsub.node.NodeSpec;
import java.util.List;

public record StoredSourceSnapshot(byte[] body, List<NodeSpec> nodes) {
    public StoredSourceSnapshot { body = body.clone(); nodes = List.copyOf(nodes); }
    @Override public byte[] body() { return body.clone(); }
    @Override public String toString() { return "StoredSourceSnapshot[nodes=" + nodes.size() + "]"; }
}
