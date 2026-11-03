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

    @Test void includesAllDefinedGroupsNestedRulesAndDialerDependenciesButNotUnusedNodes() {
        var sourceB = UUID.randomUUID(); var unused = UUID.randomUUID(); var nested = UUID.randomUUID();
        var nodes = new NodeCatalog(Map.of(A, node(A, SOURCE, "Same", TargetRef.node(DIALER)), B, node(B, sourceB, "Same", null),
                DIALER, node(DIALER, SOURCE, "Dialer", null), unused, node(unused, SOURCE, "Unused", null)));
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.group(nested)), group(nested, "Nested", "select", TargetRef.node(A))),
                List.of(rule("DOMAIN", "fixture.invalid", TargetRef.node(B)), rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var result = compiler().compile(PROFILE, doc, nodes, Map.of(SOURCE, 3L, sourceB, 7L));
        assertThat(result.report().errors()).isEmpty(); var compiled = result.compiled();
        assertThat(compiled.usedNodeIds()).containsExactlyInAnyOrder(A, B, DIALER);
        var root = YAML.load(compiled.yaml()).value();
        assertThat(root.path("proxies").size()).isEqualTo(3);
        assertThat(root.path("proxies").path(0).path("name").asString()).isNotEqualTo(root.path("proxies").path(1).path("name").asString());
        assertThat(root.path("proxies").path(0).path("dialer-proxy").asString()).isEqualTo(root.path("proxies").path(2).path("name").asString());
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
        var cycle = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Cycle", TargetRef.group(GROUP)))), Map.of(SOURCE, 1L));
        assertThat(cycle.report().errors()).extracting(Diagnostic::code).contains("DEPENDENCY_CYCLE"); assertThat(cycle.compiled()).isNull();
        var missing = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Missing", TargetRef.node(B)))), Map.of(SOURCE, 1L));
        assertThat(missing.report().errors()).extracting(Diagnostic::code).contains("MISSING_DEPENDENCY");
    }

    @Test void blocksOutputNameCollisionsDynamicOptionsAndManagedFieldOverrides() {
        String outputName = "Same · MAAAAAAAAAAAAAAAAAAAAQ";
        var doc = document(List.of(group(GROUP, outputName, "select", TargetRef.node(A))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        assertThat(compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, node(A, SOURCE, "Same", null))), Map.of(SOURCE, 1L)).report().errors())
                .extracting(Diagnostic::code).contains("OUTPUT_NAME_COLLISION");
        for (String option : List.of("include-all", "proxies", "unknown-option", "dialer-proxy")) {
            var group = new GroupDefinition(GROUP, "Main", "select", List.of(), JSON.createObjectNode().put(option, true));
            assertThat(compiler().compile(PROFILE, document(List.of(group), List.of(rule("MATCH", "", TargetRef.reject())), List.of()), NodeCatalog.empty(), Map.of()).report().valid()).isFalse();
        }
    }

    @Test void yamlMetacharactersInNodeNamesCannotInjectKeysOrRules() {
        var dangerous = node(A, SOURCE, "node: 'x'\nproxies: [] # fixture", null);
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var result = compiler().compile(PROFILE, doc, new NodeCatalog(Map.of(A, dangerous)), Map.of(SOURCE, 1L));
        assertThat(result.report().errors()).isEmpty();
        var output = YAML.load(result.compiled().yaml()).value();
        assertThat(output.path("proxies").size()).isEqualTo(1); assertThat(output.path("rules").size()).isEqualTo(1);
        assertThat(output.path("proxies").path(0).path("name").asString()).startsWith(dangerous.originalName());
    }

    @Test void stableNamesAndCanonicalNodeFieldsIgnoreCatalogInsertionOrder() {
        var doc = document(List.of(group(GROUP, "Main", "select", TargetRef.node(A), TargetRef.node(B))), List.of(rule("MATCH", "", TargetRef.group(GROUP))), List.of());
        var one = new LinkedHashMap<UUID, NodeSpec>(); one.put(A, node(A, SOURCE, "节点 ", null)); one.put(B, node(B, SOURCE, "Node", null));
        var two = new LinkedHashMap<UUID, NodeSpec>(); two.put(B, one.get(B)); two.put(A, one.get(A));
        assertThat(compiler().compile(PROFILE, doc, new NodeCatalog(one), Map.of(SOURCE, 1L)).compiled().yaml())
                .isEqualTo(compiler().compile(PROFILE, doc, new NodeCatalog(two), Map.of(SOURCE, 1L)).compiled().yaml());
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
