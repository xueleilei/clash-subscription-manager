package dev.clashsub.profile;

import dev.clashsub.common.ApiException;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.rule.RuleDefinition;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Pure ownership/structure gate, shared by draft saves and consistent-snapshot publication. */
@Component
public class DraftGuard {
    private final ReferenceValidator references;
    public DraftGuard(ReferenceValidator references) { this.references = references; }
    public void validate(ProfileDocument previous, ProfileDocument document, NodeCatalog catalog) {
        if (document == null || document.schemaVersion() != 1 || document.baseYaml() == null || document.baseYaml().length() > SafeYaml.MAX_BYTES
                || document.groups().size() > 1000 || document.rules().size() > 10_000 || document.providers().size() > 1000) {
            throw ApiException.invalid("草稿结构或大小不受支持");
        }
        var groupIds = new HashSet<UUID>(); var oldGroups = new HashSet<UUID>();
        document.groups().forEach(group -> groupIds.add(group.id())); previous.groups().forEach(group -> oldGroups.add(group.id()));
        var targets = Stream.concat(document.groups().stream().flatMap(group -> group.members().stream()), document.rules().stream().map(RuleDefinition::target)).toList();
        for (var target : targets) {
            if (target == null) { continue; }
            if (target.kind() == TargetRef.Kind.NODE && catalog.get(target.id()) == null) { throw ApiException.missing(); }
            if (target.kind() == TargetRef.Kind.GROUP && !groupIds.contains(target.id())) {
                if (oldGroups.contains(target.id())) { throw ApiException.invalid("删除代理组前，请替换或解除它在其他组和规则中的全部引用"); }
                throw ApiException.missing();
            }
        }
        var providerIds = new HashSet<UUID>(); var oldProviders = new HashSet<UUID>();
        document.providers().forEach(provider -> providerIds.add(provider.id())); previous.providers().forEach(provider -> oldProviders.add(provider.id()));
        for (var rule : document.rules()) {
            var provider = rule.expression() == null ? null : rule.expression().providerId();
            if (provider != null && !providerIds.contains(provider)) {
                if (oldProviders.contains(provider)) { throw ApiException.invalid("删除规则集前，请替换或解除它在规则中的全部引用"); }
                throw ApiException.missing();
            }
        }
        var report = references.validate(document, catalog);
        var structural = Set.of("DUPLICATE_GROUP_ID", "DUPLICATE_RULE_ID", "DUPLICATE_PROVIDER_ID", "DUPLICATE_MEMBER");
        if (report.errors().stream().anyMatch(issue -> structural.contains(issue.code()))) { throw ApiException.invalid("组、规则或规则集存在缺失或重复的 ID，或重复成员"); }
    }
}
