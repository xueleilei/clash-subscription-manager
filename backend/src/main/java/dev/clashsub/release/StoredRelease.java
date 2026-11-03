package dev.clashsub.release;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.node.NodeCatalog;
import java.util.List;

public record StoredRelease(ReleaseSnapshot snapshot, NodeCatalog nodes, List<Diagnostic> warnings) {
    public StoredRelease { warnings = List.copyOf(warnings); }
    @Override public String toString() { return "StoredRelease[id=" + snapshot.id() + "]"; }
}
