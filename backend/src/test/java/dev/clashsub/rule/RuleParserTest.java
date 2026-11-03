package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.profile.ReferenceIndex;
import dev.clashsub.profile.TargetRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuleParserTest {
    private final UUID group = UUID.randomUUID(), node = UUID.randomUUID(), provider = UUID.randomUUID();
    private final ReferenceIndex refs = new ReferenceIndex(Map.of("Proxy", List.of(TargetRef.group(group)), "Node", List.of(TargetRef.node(node)),
            "DIRECT", List.of(TargetRef.direct()), "REJECT", List.of(TargetRef.reject())), Map.of("Domains", provider));
    private final RuleParser parser = new RuleParser();

    @Test void parsesCommonRulesModifiersProvidersAndDirectNodeTargets() {
        var result = parser.parse("""
                DOMAIN,example.invalid,Proxy
                DOMAIN-SUFFIX,example.invalid,DIRECT
                DOMAIN-KEYWORD,fixture,REJECT
                IP-CIDR,192.0.2.0/24,Proxy,no-resolve
                IP-CIDR6,2001:db8::/32,DIRECT,no-resolve
                PROCESS-NAME,fixture.exe,Node
                GEOSITE,cn,DIRECT
                GEOIP,CN,DIRECT,no-resolve
                RULE-SET,Domains,Proxy
                MATCH,REJECT
                """, refs);
        assertThat(result.report().valid()).isTrue(); assertThat(result.rules()).hasSize(10);
        assertThat(result.rules().get(3).modifiers()).containsExactly("no-resolve");
        assertThat(result.rules().get(5).target()).isEqualTo(TargetRef.node(node));
        assertThat(result.rules().get(8).expression().providerId()).isEqualTo(provider);
    }

    @Test void preservesLogicalExpressionsAndQuotedCommasWithoutSplittingTargets() {
        var result = parser.parse("""
                AND,((DOMAIN,example.invalid),(OR,((NETWORK,UDP),(DST-PORT,443)))),Proxy
                NOT,((DOMAIN,ads.invalid)),REJECT
                DOMAIN-REGEX,"^a,b$",Proxy
                """, refs);
        assertThat(result.report().valid()).isTrue();
        assertThat(result.rules().get(0).expression().value()).isEqualTo("((DOMAIN,example.invalid),(OR,((NETWORK,UDP),(DST-PORT,443))))");
        assertThat(result.rules().get(2).expression().value()).isEqualTo("\"^a,b$\"");
        assertThat(result.rules()).allSatisfy(rule -> assertThat(rule.rawText()).isNotBlank());
    }

    @Test void reportsLineNumbersAndRetainsUnknownRulesAsBlockedDraftRows() {
        var result = parser.parse("DOMAIN,ok.invalid,Proxy\nSUB-RULE,(NETWORK,TCP),missing\nAND,((DOMAIN,x),Proxy\n", refs);
        assertThat(result.report().valid()).isFalse(); assertThat(result.rules()).hasSize(3);
        assertThat(result.report().errors()).extracting(Diagnostic::line).contains(2, 3);
        assertThat(result.rules().get(1).rawText()).isEqualTo("SUB-RULE,(NETWORK,TCP),missing");
    }

    @Test void ambiguousNamesAndUnmanagedNestedProviderReferencesBlockPublishing() {
        var ambiguous = new ReferenceIndex(Map.of("Same", List.of(TargetRef.group(group), TargetRef.node(node))), refs.providers());
        assertThat(parser.parse("DOMAIN,x,Same", ambiguous).report().errors()).extracting(Diagnostic::code).contains("UNRESOLVED_TARGET");
        assertThat(parser.parse("AND,((RULE-SET,Domains),(NETWORK,UDP)),Proxy", refs).report().errors())
                .extracting(Diagnostic::code).contains("UNSUPPORTED_NESTED_REFERENCE");
    }

    @Test void rejectsMalformedExpressionsAndUnsupportedModifiers() {
        for (String line : List.of("IP-CIDR,not-a-network,DIRECT", "DOMAIN,x,DIRECT,no-resolve", "MATCH,DIRECT,no-resolve", "NOT,((NETWORK,TCP),(NETWORK,UDP)),DIRECT")) {
            assertThat(parser.parse(line, refs).report().valid()).as(line).isFalse();
        }
    }
    @Test void referenceIndexDoesNotChooseDuplicateProviders() {
        var json = new tools.jackson.databind.json.JsonMapper();
        var a = new ProviderDefinition(UUID.randomUUID(), "Same", "http", "https://example.invalid/a", "domain", "yaml", 1, json.createObjectNode());
        var b = new ProviderDefinition(UUID.randomUUID(), "Same", "http", "https://example.invalid/b", "domain", "yaml", 1, json.createObjectNode());
        var doc = new dev.clashsub.profile.ProfileDocument(1, "mode: rule\n", List.of(), List.of(), List.of(a, b), List.of());
        var index = ReferenceIndex.of(doc, dev.clashsub.node.NodeCatalog.empty());
        assertThat(parser.parse("RULE-SET,Same,REJECT", index).report().errors()).extracting(Diagnostic::code).contains("MISSING_PROVIDER");
    }
    @Test void referenceIndexHandlesIncompleteNamesWithoutCrashingDraftDiagnostics() {
        var json = new tools.jackson.databind.json.JsonMapper();
        var incomplete = new ProviderDefinition(UUID.randomUUID(), null, "http", "", "domain", "yaml", 1, json.createObjectNode());
        var doc = new dev.clashsub.profile.ProfileDocument(1, "mode: rule\n", List.of(new dev.clashsub.profile.GroupDefinition(UUID.randomUUID(), null, "select", List.of(), json.createObjectNode())),
                List.of(), List.of(incomplete), List.of());
        assertThat(ReferenceIndex.of(doc, dev.clashsub.node.NodeCatalog.empty()).providers()).isEmpty();
    }
}
