package dev.clashsub.importer;
import dev.clashsub.profile.*;
import dev.clashsub.rule.RuleDefinition;
import java.util.*;
public final class DocumentNodeReferences {
    private DocumentNodeReferences() { }
    public static TargetRef replace(TargetRef target, Map<UUID, UUID> ids) {
        return target != null && target.kind() == TargetRef.Kind.NODE && ids.containsKey(target.id()) ? TargetRef.node(ids.get(target.id())) : target;
    }
    public static ProfileDocument replace(ProfileDocument document, Map<UUID, UUID> ids) {
        var groups = document.groups().stream().map(group -> new GroupDefinition(group.id(), group.name(), group.type(),
                group.members().stream().map(target -> replace(target, ids)).distinct().toList(), group.options())).toList();
        var rules = document.rules().stream().map(rule -> new RuleDefinition(rule.id(), rule.expression(), replace(rule.target(), ids), rule.modifiers(), rule.rawText(), rule.issues())).toList();
        return new ProfileDocument(document.schemaVersion(), document.baseYaml(), groups, rules, document.providers(), document.importIssues());
    }
    public static Set<UUID> direct(ProfileDocument document) {
        var result = new HashSet<UUID>();
        document.groups().forEach(group -> group.members().forEach(target -> add(result, target)));
        document.rules().forEach(rule -> add(result, rule.target())); return result;
    }
    private static void add(Set<UUID> ids, TargetRef target) { if (target != null && target.kind() == TargetRef.Kind.NODE) { ids.add(target.id()); } }
}
