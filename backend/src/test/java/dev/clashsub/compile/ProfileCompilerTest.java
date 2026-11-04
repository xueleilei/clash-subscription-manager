package dev.clashsub.compile;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ReferenceValidator;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.rule.BaseSettingsValidator;
import dev.clashsub.rule.ProviderDefinition;
import dev.clashsub.rule.ProviderValidator;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import dev.clashsub.rule.RuleValidator;
import dev.clashsub.source.SourceState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileCompilerTest {
    static final UUID PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID SOURCE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID DIALER = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID GROUP = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID PROVIDER = UUID.fromString("50000000-0000-0000-0000-000000000001");
    static final JsonMapper JSON = new JsonMapper();
    static final SafeYaml YAML = new SafeYaml();
    static ProfileCompiler compiler() {
        return new ProfileCompiler(YAML, new ReferenceValidator(), new BaseSettingsValidator(YAML), new ProviderValidator(), new RuleValidator(),
                new DependencyResolver(), new OutputNameAllocator());
    }
    static NodeSpec node(UUID id, UUID source, String name, TargetRef dialer) {
        return new NodeSpec(id, source, name, "ss", JSON.createObjectNode().put("server", "example.invalid").put("port", 443)
                .put("cipher", "aes-128-gcm").put("password", "fixture-secret"), dialer);
    }
    static GroupDefinition group(UUID id, String name, String type, TargetRef... members) {
        return new GroupDefinition(id, name, type, List.of(members), JSON.createObjectNode());
    }
    static RuleDefinition rule(String type, String value, TargetRef target) {
        return new RuleDefinition(UUID.randomUUID(), new RuleExpression(type, value, null), target, List.of(), "", List.of());
    }
    static ProfileDocument document(List<GroupDefinition> groups, List<RuleDefinition> rules, List<ProviderDefinition> providers) {
        return new ProfileDocument(1, "mode: rule\nmixed-port: 7890\nipv6: false\n", groups, rules, providers, List.of());
    }
    static Map<UUID, SourceState> sources(long version) {
        return Map.of(SOURCE, new SourceState(SOURCE, 1L, version, "机场甲"));
    }

    @Test void compiledYamlKeepsRuleCommentsWithoutTurningThemIntoRules() {
        var skeleton = document(List.of(), List.of(), List.of());
        var parsed = new dev.clashsub.rule.RuleParser().parse("# === 局域网直连 ===\nDOMAIN-SUFFIX,local,DIRECT # 本地网络\n\n# === 分流规则 ===\nMATCH,REJECT\n# 文件末尾\n",
                dev.clashsub.profile.ReferenceIndex.of(skeleton, NodeCatalog.empty()));
        var result = compiler().compile(PROFILE, document(List.of(), parsed.rules(), List.of()), NodeCatalog.empty(), Map.of());
        assertThat(result.report().errors()).isEmpty();
        assertThat(result.compiled().yaml()).contains("# === 局域网直连 ===", "# 本地网络", "# === 分流规则 ===", "# 文件末尾");
        assertThat(result.compiled().yaml()).containsPattern("\\n[ \\t]*\\n");
        assertThat(YAML.load(result.compiled().yaml()).value().path("rules").size()).isEqualTo(2);
        assertThat(YAML.load(result.compiled().yaml()).value().path("rules").path(0).asString()).isEqualTo("DOMAIN-SUFFIX,local,DIRECT");
    }

    @Test void unicodeLineSeparatorsCannotMakeAnAnnotatedRuleSpanMultipleYamlLines() {
        var row = rule("DOMAIN-REGEX", "foo\u2028bar", TargetRef.direct())
                .withTrivia(new dev.clashsub.rule.RuleTrivia(List.of("# 单行规则"), "", List.of()));
        var result = compiler().compile(PROFILE, document(List.of(), List.of(row, rule("MATCH", "", TargetRef.reject())), List.of()), NodeCatalog.empty(), Map.of());
        assertThat(result.report().valid()).isFalse(); assertThat(result.compiled()).isNull();
        var target = rule("MATCH", "", TargetRef.group(GROUP)).withTrivia(row.trivia());
        var named = compiler().compile(PROFILE, document(List.of(group(GROUP, "分隔\u2028组", "select", TargetRef.direct())), List.of(target), List.of()), NodeCatalog.empty(), Map.of());
        assertThat(named.report().valid()).isFalse(); assertThat(named.compiled()).isNull();
    }

    @Test void localNodesHaveReadableNamesInGroupsAndDirectRules() {
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A))),
                List.of(rule("MATCH", "", TargetRef.node(A))), List.of());
        var result = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, null, "我的节点", null))), Map.of());
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        assertThat(output.path("proxies").path(0).path("name").asString()).isEqualTo("我的节点 · 本地节点");
        assertThat(output.path("proxy-groups").path(0).path("proxies").path(0).asString()).isEqualTo("我的节点 · 本地节点");
        assertThat(output.path("rules").path(0).asString()).isEqualTo("MATCH,我的节点 · 本地节点");
    }

    @Test void includesAllDefinedGroupsNestedRulesAndDialerDependenciesButNotUnusedNodes() {
        var sourceB = UUID.randomUUID(); var unused = UUID.randomUUID(); var nested = UUID.randomUUID();
        var nodes = new NodeCatalog(Map.of(A, node(A, SOURCE, "Same", TargetRef.node(DIALER)), B, node(B, sourceB, "Same", null),
                DIALER, node(DIALER, SOURCE, "Dialer", null), unused, node(unused, SOURCE, "Unused", null)));
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.group(nested)), group(nested, "Nested", "select", TargetRef.node(A))),
                List.of(rule("DOMAIN", "fixture.invalid", TargetRef.node(B)), rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var result = compiler().compile(PROFILE, doc, nodes, Map.of(SOURCE, new SourceState(SOURCE, 1L, 3L, "机场甲"),
                sourceB, new SourceState(sourceB, 1L, 7L, "机场乙")));
        assertThat(result.report().errors()).isEmpty(); var compiled = result.compiled();
        assertThat(compiled.usedNodeIds()).containsExactlyInAnyOrder(A, B, DIALER);
        var root = YAML.load(compiled.yaml()).value();
        assertThat(root.path("proxies").size()).isEqualTo(3);
        assertThat(root.path("proxies").path(0).path("name").asString()).isEqualTo("Same · 机场甲");
        assertThat(root.path("proxies").path(1).path("name").asString()).isEqualTo("Same · 机场乙");
        assertThat(root.path("proxies").path(2).path("name").asString()).isEqualTo("Dialer · 机场甲");
        assertThat(root.path("proxies").path(0).path("dialer-proxy").asString()).isEqualTo("Dialer · 机场甲");
        assertThat(root.path("proxy-groups").path(1).path("proxies").path(0).asString()).isEqualTo("Same · 机场甲");
        assertThat(root.path("rules").path(0).asString()).isEqualTo("DOMAIN,fixture.invalid,Same · 机场乙");
        assertThat(compiled.sourceVersions()).containsExactlyInAnyOrderEntriesOf(Map.of(SOURCE, 3L, sourceB, 7L));
        assertThat(root.path("mixed-port").asInt()).isEqualTo(7890); assertThat(root.path("ipv6").asBoolean()).isFalse();
    }

    @Test void byteStableGoldenOutputIncludesRejectFallbackAndIsolatedProviderPaths() throws Exception {
        var provider = new ProviderDefinition(PROVIDER, "Domains", "http", "https://example.invalid/rules.yaml", "domain", "yaml", 3600, JSON.createObjectNode());
        var providerRule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("RULE-SET", "Domains", PROVIDER), TargetRef.group(GROUP), List.of(), "", List.of());
        var doc = document(List.of(group(GROUP, "Empty", "url-test")), List.of(providerRule, rule("MATCH", "", TargetRef.reject())), List.of(provider));
        var first = compiler().compile(PROFILE, doc, NodeCatalog.empty(), Map.of()); var second = compiler().compile(PROFILE, doc, NodeCatalog.empty(), Map.of());
        assertThat(first.report().errors()).isEmpty(); assertThat(first.compiled().yaml()).isEqualTo(second.compiled().yaml());
        assertThat(first.compiled().yaml()).isEqualTo(Files.readString(Path.of("../tests/fixtures/profiles/compiled.expected.yaml")));
        var group = YAML.load(first.compiled().yaml()).value().path("proxy-groups").path(0);
        assertThat(group.path("proxies").path(0).asString()).isEqualTo("REJECT"); assertThat(group.path("empty-fallback").asString()).isEqualTo("REJECT");
        assertThat(first.compiled().yaml()).contains("./rules/" + PROFILE + "/" + PROVIDER + ".yaml");
    }

    @Test void failsOnMixedNodeGroupCyclesAndMissingDialerDependencies() {
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var cycle = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Cycle", TargetRef.group(GROUP)))), sources(1L));
        assertThat(cycle.report().errors()).extracting(Diagnostic::code).contains("DEPENDENCY_CYCLE"); assertThat(cycle.compiled()).isNull();
        var missing = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Missing", TargetRef.node(B)))), sources(1L));
        assertThat(missing.report().errors()).extracting(Diagnostic::code).contains("MISSING_DEPENDENCY");
    }

    @Test void blocksReservedGroupNamesDynamicOptionsAndManagedFieldOverrides() {
        String outputName = "PASS-RULE";
        var doc = document(List.of(group(GROUP, outputName, "select", TargetRef.node(A))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        assertThat(compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Same", null))), sources(1L)).report().errors())
                .extracting(Diagnostic::code).contains("OUTPUT_NAME_COLLISION");
        for (String option : List.of("include-all", "proxies", "unknown-option", "dialer-proxy")) {
            var group = new GroupDefinition(GROUP, "Main", "select", List.of(), JSON.createObjectNode().put(option, true));
            assertThat(compiler().compile(PROFILE, document(List.of(group), List.of(rule("MATCH", "", TargetRef.reject())), List.of()), NodeCatalog.empty(), Map.of()).report().valid()).isFalse();
        }
    }

    @Test void yamlMetacharactersInNodeNamesCannotInjectKeysOrRules() {
        var dangerous = node(A, SOURCE, "node: 'x'\nproxies: [] # fixture", null);
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var result = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, dangerous)), sources(1L));
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        assertThat(output.path("proxies").size()).isEqualTo(1); assertThat(output.path("rules").size()).isEqualTo(1);
        assertThat(output.path("proxies").path(0).path("name").asString()).startsWith(dangerous.originalName());
    }

    @Test void stableNamesAndCanonicalNodeFieldsIgnoreCatalogInsertionOrder() {
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A), TargetRef.node(B))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var one = new LinkedHashMap<UUID, NodeSpec>(); one.put(A, node(A, SOURCE, "节点 ", null)); one.put(B, node(B, SOURCE, "Node", null));
        var two = new LinkedHashMap<UUID, NodeSpec>(); two.put(B, one.get(B)); two.put(A, one.get(A));
        assertThat(compiler().compile(PROFILE, doc, new NodeCatalog(one), sources(1L)).compiled().yaml())
                .isEqualTo(compiler().compile(PROFILE, doc, new NodeCatalog(two), sources(1L)).compiled().yaml());
    }

    @Test void sameNamesAcrossSubscriptionsAndManualGroupsReceiveStableDistinctSuffixes() {
        var otherSource = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var sameNames = Map.of(SOURCE, new SourceState(SOURCE, 1L, 1L, "同名订阅"),
                otherSource, new SourceState(otherSource, 1L, 1L, "同名订阅"));
        var first = node(A, SOURCE, "香港", null);
        var second = node(B, otherSource, "香港", TargetRef.node(A));
        var local = node(DIALER, null, "香港", null).withLocalGroupName("同名订阅");
        var forward = new LinkedHashMap<UUID, NodeSpec>(); forward.put(A, first); forward.put(B, second); forward.put(DIALER, local);
        var reversed = new LinkedHashMap<UUID, NodeSpec>(); reversed.put(DIALER, local); reversed.put(B, second); reversed.put(A, first);
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A), TargetRef.node(B), TargetRef.node(DIALER))),
                List.of(rule("MATCH", "", TargetRef.node(B))), List.of());

        var result = compiler().compile(PROFILE, doc, new NodeCatalog(forward), sameNames);
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        var names = output.path("proxies").valueStream().map(value -> value.path("name").asString()).toList();
        assertThat(names).doesNotHaveDuplicates().allSatisfy(name -> assertThat(name).startsWith("香港 · 同名订阅 · "));
        assertThat(output.path("proxy-groups").path(0).path("proxies").valueStream().map(value -> value.asString()).toList())
                .containsExactlyElementsOf(names);
        assertThat(output.path("proxies").path(1).path("dialer-proxy").asString()).isEqualTo(names.getFirst());
        assertThat(output.path("rules").path(0).asString()).isEqualTo("MATCH," + names.get(1));
        assertThat(compiler().compile(PROFILE, doc, new NodeCatalog(reversed), sameNames).compiled().yaml())
                .isEqualTo(result.compiled().yaml());
    }

    @Test void nodeNameCannotShadowAnExistingGroup() {
        var doc = document(List.of(group(GROUP, "Same · 机场甲", "select", TargetRef.node(A))),
                List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var result = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Same", null))), sources(1L));
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        assertThat(output.path("proxies").path(0).path("name").asString()).isEqualTo("Same · 机场甲 · 30000000");
        assertThat(output.path("proxy-groups").path(0).path("proxies").path(0).asString()).isEqualTo("Same · 机场甲 · 30000000");
        assertThat(output.path("rules").path(0).asString()).isEqualTo("MATCH,Same · 机场甲");
    }

    @Test void collisionSuffixDoesNotTakeAnotherNodesReadableName() {
        var otherSource = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var sourceNames = Map.of(SOURCE, new SourceState(SOURCE, 1L, 1L, "机场甲"),
                otherSource, new SourceState(otherSource, 1L, 1L, "30000000"));
        var catalog = new NodeCatalog(Map.of(A, node(A, SOURCE, "Same", null), B, node(B, SOURCE, "Same", null),
                DIALER, node(DIALER, otherSource, "Same · 机场甲", null)));
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A), TargetRef.node(B), TargetRef.node(DIALER))),
                List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var result = compiler().compile(PROFILE, doc, catalog, sourceNames);
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        assertThat(output.path("proxies").valueStream().map(value -> value.path("name").asString()).toList()).doesNotHaveDuplicates();
        assertThat(output.path("proxies").path(2).path("name").asString()).isEqualTo("Same · 机场甲 · 30000000");
    }

    @Test void sourceLabelCannotIntroduceRuleDelimitersOrExtraLines() {
        var sourceNames = Map.of(SOURCE, new SourceState(SOURCE, 1L, 1L, "  机场,甲\nREJECT\t "));
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A))),
                List.of(rule("MATCH", "", TargetRef.node(A))), List.of());
        var result = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "香港", null))), sourceNames);
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        assertThat(output.path("proxies").path(0).path("name").asString()).isEqualTo("香港 · 机场，甲 REJECT");
        assertThat(output.path("rules").size()).isEqualTo(1);
        assertThat(output.path("rules").path(0).asString()).isEqualTo("MATCH,香港 · 机场，甲 REJECT");
    }

    @Test void missingGroupTypeReturnsDiagnosticsEvenWhenOptionsArePresent() {
        var incomplete = new GroupDefinition(GROUP, "Incomplete", null, List.of(), JSON.createObjectNode().put("tolerance", 50));
        var result = compiler().compile(PROFILE, document(List.of(incomplete), List.of(rule("MATCH", "", TargetRef.reject())), List.of()), NodeCatalog.empty(), Map.of());
        assertThat(result.report().errors()).extracting(Diagnostic::code).contains("UNSUPPORTED_GROUP_TYPE");
        assertThat(result.compiled()).isNull();
    }
    @Test void acceptsNumericHttpStatusOption() {
        var configured = new GroupDefinition(GROUP, "Automatic", "url-test", List.of(), JSON.createObjectNode().put("expected-status", 204));
        var result = compiler().compile(PROFILE, document(List.of(configured), List.of(rule("MATCH", "", TargetRef.reject())), List.of()), NodeCatalog.empty(), Map.of());
        assertThat(result.report().errors()).isEmpty();
    }
}
