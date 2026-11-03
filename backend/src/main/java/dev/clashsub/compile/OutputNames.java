package dev.clashsub.compile;

import dev.clashsub.common.ValidationReport;
import dev.clashsub.profile.TargetRef;
import java.util.Map;
import java.util.UUID;

public record OutputNames(Map<UUID, String> nodes, Map<UUID, String> groups, Map<UUID, String> providers, ValidationReport report) {
    public OutputNames { nodes = Map.copyOf(nodes); groups = Map.copyOf(groups); providers = Map.copyOf(providers); }
    public String target(TargetRef ref) {
        return switch (ref.kind()) {
            case NODE -> nodes.get(ref.id()); case GROUP -> groups.get(ref.id());
            case DIRECT -> "DIRECT"; case REJECT -> "REJECT"; case UNRESOLVED -> null;
        };
    }
}
