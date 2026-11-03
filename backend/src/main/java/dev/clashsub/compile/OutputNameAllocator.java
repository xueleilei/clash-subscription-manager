package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.profile.ProfileDocument;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutputNameAllocator {
    public OutputNames allocate(ProfileDocument document, UsedDependencies dependencies) {
        var nodes = new LinkedHashMap<UUID, String>(); var groups = new LinkedHashMap<UUID, String>(); var providers = new LinkedHashMap<UUID, String>();
        var allocated = new HashSet<>(Set.of("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE", "GLOBAL"));
        var issues = new ArrayList<Diagnostic>();
        dependencies.nodes().values().stream().sorted(java.util.Comparator.comparing(node -> node.id().toString())).forEach(node -> {
            String name = node.originalName() + " · " + suffix(node.id()); nodes.put(node.id(), name);
            if (!allocated.add(name)) { issues.add(collision("nodes." + node.id())); }
        });
        for (var group : document.groups()) {
            groups.put(group.id(), group.name()); if (!allocated.add(group.name())) { issues.add(collision("groups." + group.id() + ".name")); }
        }
        document.providers().forEach(provider -> providers.put(provider.id(), provider.name()));
        return new OutputNames(nodes, groups, providers, ValidationReport.of(issues));
    }
    private String suffix(UUID id) {
        byte[] bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private Diagnostic collision(String path) { return Diagnostic.error("OUTPUT_NAME_COLLISION", path, "输出名称与另一节点、代理组或内置策略冲突，请修改组名称"); }
}
