package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.TargetRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DependencyResolver {
    private record Visit(TargetRef ref, Iterator<TargetRef> children) { }
    public UsedDependencies resolve(ProfileDocument document, NodeCatalog catalog) {
        var issues = new ArrayList<Diagnostic>(); var used = new LinkedHashMap<UUID, NodeSpec>();
        var groups = new HashMap<UUID, GroupDefinition>(); document.groups().forEach(group -> groups.put(group.id(), group));
        var roots = new ArrayList<TargetRef>(); document.groups().forEach(group -> roots.add(TargetRef.group(group.id())));
        document.rules().forEach(rule -> roots.add(rule.target()));
        var colors = new HashMap<TargetRef, Integer>();
        for (var root : roots) {
            if (root == null || colors.containsKey(root)) { continue; }
            var stack = new ArrayDeque<Visit>(); colors.put(root, 1);
            stack.push(new Visit(root, children(root, catalog, groups, used, issues).iterator()));
            while (!stack.isEmpty()) {
                var frame = stack.peek();
                if (!frame.children().hasNext()) { colors.put(frame.ref(), 2); stack.pop(); continue; }
                var child = frame.children().next();
                if (colors.getOrDefault(child, 0) == 1) { issues.add(Diagnostic.error("DEPENDENCY_CYCLE", path(frame.ref()), "节点链式代理与组成员之间存在循环依赖")); }
                else if (!colors.containsKey(child)) {
                    colors.put(child, 1); stack.push(new Visit(child, children(child, catalog, groups, used, issues).iterator()));
                }
            }
        }
        return new UsedDependencies(used, ValidationReport.of(issues));
    }
    private List<TargetRef> children(TargetRef ref, NodeCatalog catalog, Map<UUID, GroupDefinition> groups,
            Map<UUID, NodeSpec> used, List<Diagnostic> issues) {
        switch (ref.kind()) {
            case NODE -> {
                var node = catalog.get(ref.id());
                if (node == null || !ref.id().equals(node.id())) { issues.add(missing(ref)); return List.of(); }
                used.put(node.id(), node);
                return node.dialerRef() == null ? List.of() : List.of(node.dialerRef());
            }
            case GROUP -> {
                var group = groups.get(ref.id());
                if (group == null) { issues.add(missing(ref)); return List.of(); }
                return group.members();
            }
            case UNRESOLVED -> issues.add(Diagnostic.error("UNRESOLVED_DEPENDENCY", path(ref), "链式代理引用尚未解析"));
            default -> { }
        }
        return List.of();
    }
    private Diagnostic missing(TargetRef ref) { return Diagnostic.error("MISSING_DEPENDENCY", path(ref), "节点或链式代理依赖不存在"); }
    private String path(TargetRef ref) { return (ref.kind() == TargetRef.Kind.GROUP ? "groups." : "nodes.") + (ref.id() == null ? "unresolved" : ref.id()); }
}
