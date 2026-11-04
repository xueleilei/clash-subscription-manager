package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.source.SourceState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OutputNameAllocator {
    private static final int SHORT_ID_LENGTH = 8;
    private static final int ID_EXTENSION_LENGTH = 4;
    private static final Pattern LABEL_WHITESPACE = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\s]+");

    public OutputNames allocate(ProfileDocument document, UsedDependencies dependencies, Map<UUID, SourceState> sources) {
        var nodes = new LinkedHashMap<UUID, String>(); var groups = new LinkedHashMap<UUID, String>(); var providers = new LinkedHashMap<UUID, String>();
        var allocated = new HashSet<>(Set.of("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE", "GLOBAL"));
        var issues = new ArrayList<Diagnostic>();
        for (var group : document.groups()) {
            groups.put(group.id(), group.name()); if (!allocated.add(group.name())) { issues.add(collision("groups." + group.id() + ".name")); }
        }
        var baseNames = new LinkedHashMap<UUID, String>();
        var counts = new HashMap<String, Integer>();
        dependencies.nodes().values().stream().sorted(java.util.Comparator.comparing(node -> node.id().toString())).forEach(node -> {
            String name = node.originalName() + " · " + sourceLabel(node, sources);
            baseNames.put(node.id(), name);
            counts.merge(name, 1, Integer::sum);
        });
        // Reserve every readable name so a collision suffix cannot take another node's normal name.
        var unavailable = new HashSet<>(allocated);
        unavailable.addAll(baseNames.values());
        baseNames.forEach((id, base) -> {
            String name = counts.get(base) > 1 || allocated.contains(base) ? disambiguate(base, id, unavailable) : base;
            nodes.put(id, name);
            allocated.add(name);
            unavailable.add(name);
        });
        document.providers().forEach(provider -> providers.put(provider.id(), provider.name()));
        return new OutputNames(nodes, groups, providers, ValidationReport.of(issues));
    }
    private String sourceLabel(NodeSpec node, Map<UUID, SourceState> sources) {
        if (node.sourceId() == null) { return safeLabel(node.localGroupName(), "本地节点"); }
        var source = sources.get(node.sourceId());
        return safeLabel(source == null ? null : source.name(), "上游订阅");
    }
    private String safeLabel(String name, String fallback) {
        if (name == null) { return fallback; }
        // Direct rule targets use commas as delimiters and must fit on one line.
        String label = LABEL_WHITESPACE.matcher(name.replace(',', '，')).replaceAll(" ").strip();
        return label.isEmpty() ? fallback : label;
    }
    private String disambiguate(String base, UUID id, Set<String> unavailable) {
        String suffix = id.toString().replace("-", "");
        for (int length = SHORT_ID_LENGTH; length <= suffix.length(); length += ID_EXTENSION_LENGTH) {
            String candidate = base + " · " + suffix.substring(0, length);
            if (!unavailable.contains(candidate)) { return candidate; }
        }
        String candidate = base + " · " + suffix;
        for (int number = 2; ; number++) {
            if (!unavailable.contains(candidate + " · " + number)) { return candidate + " · " + number; }
        }
    }
    private Diagnostic collision(String path) { return Diagnostic.error("OUTPUT_NAME_COLLISION", path, "输出名称与另一节点、代理组或内置策略冲突，请修改组名称"); }
}
