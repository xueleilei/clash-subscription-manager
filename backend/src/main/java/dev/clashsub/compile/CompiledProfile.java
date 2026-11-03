package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CompiledProfile(String yaml, String digest, Set<UUID> usedNodeIds, List<Diagnostic> warnings,
        Map<UUID, Long> sourceVersions) {
    public CompiledProfile {
        usedNodeIds = Set.copyOf(usedNodeIds); warnings = List.copyOf(warnings); sourceVersions = Map.copyOf(sourceVersions);
    }
    @Override public String toString() { return "CompiledProfile[digest=" + digest + "]"; }
}
