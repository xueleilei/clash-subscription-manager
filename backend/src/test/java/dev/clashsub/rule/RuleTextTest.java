package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.compile.OutputNameAllocator;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.DraftSnapshot;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ReferenceIndex;
import dev.clashsub.profile.TargetRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import static org.assertj.core.api.Assertions.assertThat;

class RuleTextTest {
    private final RuleParser parser = new RuleParser();
    private final RuleTextService service = new RuleTextService(parser, new RuleValidator(), new OutputNameAllocator());

    private DraftSnapshot draft(List<RuleDefinition> rules) {
        return new DraftSnapshot(UUID.randomUUID(), 8, new ProfileDocument(1, "mode: rule\n", List.of(), rules, List.of(), List.of()));
    }

    @Test void largeRuleListRoundTripsAndASingleEditKeepsOtherRuleIdentitiesAndOrder() throws Exception {
        String text = Files.readString(Path.of("../tests/fixtures/rules/large-2124.txt"));
        var initial = draft(List.of());
        var original = draft(parser.parse(text, ReferenceIndex.of(initial.document(), NodeCatalog.empty())).rules());
        var exported = service.text(original, NodeCatalog.empty(), Map.of(), 10L);
        assertThat(exported.ruleCount()).isEqualTo(2124);
        assertThat(service.preview(original, exported.text(), NodeCatalog.empty(), Map.of()).rules()).isEqualTo(original.document().rules());

        var changed = service.preview(original, exported.text().replace("fixture-100.example.invalid", "changed.example.invalid"), NodeCatalog.empty(), Map.of());
        assertThat(changed.applicable()).isTrue(); assertThat(changed.rules()).hasSize(2124);
        assertThat(changed.rules().get(100).expression().value()).isEqualTo("changed.example.invalid");
        for (int index = 0; index < 2124; index++) {
            if (index != 100) { assertThat(changed.rules().get(index)).isEqualTo(original.document().rules().get(index)); }
        }
    }

    @Test void duplicateUnchangedRulesKeepSeparateIdsAndExistingWarnings() {
        var rule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("DOMAIN", "same.invalid", null), TargetRef.reject(), List.of(), "DOMAIN,same.invalid,REJECT",
                List.of(Diagnostic.warning("REMOVED_NODE_TARGET", "rules[0]", "原节点已失效，自动改为 REJECT")));
        var copy = new RuleDefinition(UUID.randomUUID(), rule.expression(), rule.target(), rule.modifiers(), rule.rawText(), List.of());
        var fallback = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.reject(), List.of(), "MATCH,REJECT", List.of());
        var original = draft(List.of(rule, copy, fallback));
        var result = service.preview(original, service.text(original, NodeCatalog.empty(), Map.of(), 10L).text(), NodeCatalog.empty(), Map.of());
        assertThat(result.applicable()).isTrue(); assertThat(result.rules()).isEqualTo(original.document().rules());
        assertThat(result.report().warnings()).containsAll(rule.issues());
    }

    @Test void currentGroupAndProviderNamesRoundTripWithoutLosingStableReferences() {
        var groupId = UUID.randomUUID(); var providerId = UUID.randomUUID();
        var group = new GroupDefinition(groupId, "我的 '新' 组", "select", List.of(), JsonNodeFactory.instance.objectNode());
        var provider = new ProviderDefinition(providerId, "广告 (域名)", "http", "https://fixture.invalid/rules", "domain", "yaml", 3600, JsonNodeFactory.instance.objectNode());
        var rule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("RULE-SET", "旧名称", providerId), TargetRef.group(groupId), List.of(), "", List.of());
        var fallback = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.group(groupId), List.of(), "", List.of());
        var original = new DraftSnapshot(UUID.randomUUID(), 8, new ProfileDocument(1, "mode: rule\n", List.of(group), List.of(rule, fallback), List.of(provider), List.of()));
        var text = service.text(original, NodeCatalog.empty(), Map.of(), 10L).text();
        assertThat(text).contains("'广告 (域名)'", "'我的 ''新'' 组'").doesNotContain("旧名称");
        var result = service.preview(original, text, NodeCatalog.empty(), Map.of());
        assertThat(result.report().errors()).isEmpty();
        assertThat(result.applicable()).isTrue(); assertThat(result.rules()).isEqualTo(original.document().rules());
    }

    @Test void invalidRowsWithoutRawTextCannotDisappearDuringTextEditing() {
        var invalid = new RuleDefinition(UUID.randomUUID(), new RuleExpression("UNSUPPORTED", "", null), TargetRef.unresolved(""), List.of(), "",
                List.of(Diagnostic.error("RULE_SYNTAX", "rules[0]", "请修正规则")));
        var fallback = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.reject(), List.of(), "", List.of());
        var original = draft(List.of(invalid, fallback));
        var result = service.preview(original, service.text(original, NodeCatalog.empty(), Map.of(), 10L).text(), NodeCatalog.empty(), Map.of());
        assertThat(result.applicable()).isFalse(); assertThat(result.rules()).isEqualTo(original.document().rules());
    }
}
