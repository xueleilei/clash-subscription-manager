package dev.clashsub.sync;

import dev.clashsub.profile.*;
import dev.clashsub.common.Diagnostic;
import dev.clashsub.rule.RuleDefinition;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReferenceReconciler {
    public ReconcileResult reconcile(ProfileDocument definition, Set<UUID> removedNodeIds) {
        var groups = new ArrayList<GroupDefinition>(); var empty = new ArrayList<UUID>(); int removed = 0;
        for (var group : definition.groups()) {
            var members = group.members().stream().filter(member -> !removed(member, removedNodeIds)).toList();
            removed += group.members().size() - members.size();
            groups.add(new GroupDefinition(group.id(), group.name(), group.type(), members, group.options()));
            if (members.isEmpty()) { empty.add(group.id()); }
        }
        var rules = new ArrayList<RuleDefinition>(); var rejected = new ArrayList<UUID>();
        for (var rule : definition.rules()) {
            if (removed(rule.target(), removedNodeIds)) {
                var issues = new ArrayList<>(rule.issues());
                issues.add(Diagnostic.warning("REMOVED_NODE_TARGET", "rules[" + rules.size() + "].target", "原目标节点已失效，自动改为 REJECT"));
                rules.add(new RuleDefinition(rule.id(), rule.expression(), TargetRef.reject(), rule.modifiers(), rule.rawText(), issues)); rejected.add(rule.id());
            } else { rules.add(rule); }
        }
        return new ReconcileResult(new ProfileDocument(definition.schemaVersion(), definition.baseYaml(), groups, rules, definition.providers(), definition.importIssues()), removed, rejected, empty);
    }
    private boolean removed(TargetRef target, Set<UUID> removed) { return target != null && target.kind() == TargetRef.Kind.NODE && removed.contains(target.id()); }
}
