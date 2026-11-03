package dev.clashsub.profile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeCatalog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReferenceValidator {
    private static final Set<String> DYNAMIC = Set.of("use", "include-all", "include-all-proxies", "include-all-providers", "filter", "exclude-filter", "exclude-type");
    private static final Set<String> RESERVED = Set.of("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL");
    private record Visit(UUID id, Iterator<UUID> children) { }

    public ValidationReport validate(ProfileDocument document, NodeCatalog catalog) {
        var issues = new ArrayList<Diagnostic>();
        var groups = new LinkedHashMap<UUID, GroupDefinition>(); var names = new HashSet<String>();
        for (int i = 0; i < document.groups().size(); i++) {
            var group = document.groups().get(i); String path = "groups[" + i + "]";
            if (group.id() == null || groups.putIfAbsent(group.id(), group) != null) { issues.add(Diagnostic.error("DUPLICATE_GROUP_ID", path + ".id", "代理组 ID 缺失或重复")); }
            if (group.name() == null || group.name().isBlank() || group.name().length() > 256 || RESERVED.contains(group.name())) {
                issues.add(Diagnostic.error("INVALID_GROUP_NAME", path + ".name", "代理组名称为空、过长或使用了内置策略名称"));
            } else if (!names.add(group.name())) { issues.add(Diagnostic.error("DUPLICATE_GROUP_NAME", path + ".name", "代理组名称重复")); }
            if (!Set.of("select", "url-test").contains(group.type() == null ? "" : group.type())) { issues.add(Diagnostic.error("UNSUPPORTED_GROUP_TYPE", path + ".type", "仅支持 select 和 url-test 代理组")); }
            if (group.members().isEmpty()) { issues.add(Diagnostic.warning("EMPTY_GROUP", path + ".members", "空组发布时使用 REJECT，请补充成员")); }
            for (String field : group.options().propertyNames()) {
                if (DYNAMIC.contains(field)) { issues.add(Diagnostic.error("DYNAMIC_MEMBERSHIP", path + ".options." + field, "手工代理组不支持动态成员设置")); }
            }
        }
        var providers = new HashSet<UUID>();
        for (int i = 0; i < document.providers().size(); i++) {
            var provider = document.providers().get(i);
            if (provider.id() == null || !providers.add(provider.id())) { issues.add(Diagnostic.error("DUPLICATE_PROVIDER_ID", "providers[" + i + "].id", "规则集 ID 缺失或重复")); }
        }
        for (int i = 0; i < document.groups().size(); i++) {
            var seen = new HashSet<TargetRef>(); var group = document.groups().get(i);
            for (int j = 0; j < group.members().size(); j++) {
                String path = "groups[" + i + "].members[" + j + "]";
                var target = group.members().get(j);
                if (!seen.add(target)) { issues.add(Diagnostic.error("DUPLICATE_MEMBER", path, "代理组成员重复")); }
                target(target, path, groups, catalog, issues);
            }
        }
        var ruleIds = new HashSet<UUID>();
        for (int i = 0; i < document.rules().size(); i++) {
            var rule = document.rules().get(i); String path = "rules[" + i + "]";
            if (rule.id() == null || !ruleIds.add(rule.id())) { issues.add(Diagnostic.error("DUPLICATE_RULE_ID", path + ".id", "规则 ID 缺失或重复")); }
            target(rule.target(), path + ".target", groups, catalog, issues);
            if (rule.expression() == null) { issues.add(Diagnostic.error("MISSING_EXPRESSION", path + ".expression", "规则表达式缺失")); }
            else if (rule.expression().providerId() != null && !providers.contains(rule.expression().providerId())) {
                issues.add(Diagnostic.error("MISSING_PROVIDER", path + ".expression.providerId", "规则引用了当前方案中不存在的规则集"));
            }
        }
        cycles(groups, issues);
        return ValidationReport.of(issues);
    }

    private void target(TargetRef target, String path, Map<UUID, GroupDefinition> groups, NodeCatalog catalog, List<Diagnostic> issues) {
        if (target == null) { issues.add(Diagnostic.error("MISSING_TARGET", path, "请选择目标策略")); return; }
        switch (target.kind()) {
            case NODE -> { if (catalog.get(target.id()) == null) { issues.add(Diagnostic.error("MISSING_NODE", path, "引用的节点不存在")); } }
            case GROUP -> { if (!groups.containsKey(target.id())) { issues.add(Diagnostic.error("MISSING_GROUP", path, "引用的代理组不在当前方案中")); } }
            case UNRESOLVED -> issues.add(Diagnostic.error("UNRESOLVED_TARGET", path, "引用尚未解析，请重新选择目标"));
            default -> { }
        }
    }

    private void cycles(Map<UUID, GroupDefinition> groups, List<Diagnostic> issues) {
        var colors = new HashMap<UUID, Integer>();
        for (var id : groups.keySet()) {
            if (colors.containsKey(id)) { continue; }
            var stack = new ArrayDeque<Visit>(); colors.put(id, 1); stack.push(new Visit(id, children(groups.get(id))));
            while (!stack.isEmpty()) {
                var top = stack.peek();
                if (!top.children().hasNext()) { colors.put(top.id(), 2); stack.pop(); continue; }
                var child = top.children().next();
                if (!groups.containsKey(child)) { continue; }
                if (colors.getOrDefault(child, 0) == 1) { issues.add(Diagnostic.error("GROUP_CYCLE", "groups." + top.id() + ".members", "代理组之间存在循环引用")); }
                else if (!colors.containsKey(child)) { colors.put(child, 1); stack.push(new Visit(child, children(groups.get(child)))); }
            }
        }
    }
    private Iterator<UUID> children(GroupDefinition group) {
        return group.members().stream().filter(target -> target.kind() == TargetRef.Kind.GROUP).map(TargetRef::id).iterator();
    }
}
