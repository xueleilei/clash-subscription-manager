package dev.clashsub.profile;

import dev.clashsub.node.NodeCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReferenceIndex(Map<String, List<TargetRef>> targets, Map<String, UUID> providers) {
    public ReferenceIndex {
        var copy = new LinkedHashMap<String, List<TargetRef>>();
        targets.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        targets = Map.copyOf(copy); providers = Map.copyOf(providers);
    }
    public static ReferenceIndex of(ProfileDocument document, NodeCatalog catalog) {
        var targets = new LinkedHashMap<String, List<TargetRef>>();
        targets.put("DIRECT", new ArrayList<>(List.of(TargetRef.direct())));
        targets.put("REJECT", new ArrayList<>(List.of(TargetRef.reject())));
        document.groups().stream().filter(group -> group.name() != null && !group.name().isBlank() && group.id() != null)
                .forEach(group -> targets.computeIfAbsent(group.name(), k -> new ArrayList<>()).add(TargetRef.group(group.id())));
        catalog.nodes().values().forEach(node -> targets.computeIfAbsent(node.originalName(), k -> new ArrayList<>()).add(TargetRef.node(node.id())));
        var providers = new LinkedHashMap<String, UUID>();
        var ambiguous = new java.util.HashSet<String>();
        document.providers().stream().filter(provider -> provider.name() != null && !provider.name().isBlank() && provider.id() != null).forEach(provider -> {
            if (providers.putIfAbsent(provider.name(), provider.id()) != null) { ambiguous.add(provider.name()); }
        });
        ambiguous.forEach(providers::remove);
        return new ReferenceIndex(targets, providers);
    }
    public TargetRef resolve(String name) {
        var matches = targets.getOrDefault(name, List.of());
        return matches.size() == 1 ? matches.getFirst() : TargetRef.unresolved(name);
    }
}
