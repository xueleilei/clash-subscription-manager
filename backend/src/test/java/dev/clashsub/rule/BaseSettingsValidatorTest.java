package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.profile.ReferenceIndex;
import dev.clashsub.profile.TargetRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class BaseSettingsValidatorTest {
    private final BaseSettingsValidator validator = new BaseSettingsValidator(new SafeYaml());
    private final ReferenceIndex refs = new ReferenceIndex(Map.of("Proxy", List.of(TargetRef.group(UUID.randomUUID()))), Map.of());
    @Test void acceptsCommonDnsPortsAndSnifferWithoutChangingYaml() {
        String yaml = """
                mode: rule
                mixed-port: 7890
                ipv6: false
                dns:
                  enable: true
                  enhanced-mode: fake-ip
                  nameserver: [https://dns.example.invalid/dns-query]
                  nameserver-policy:
                    'geosite:cn': [223.5.5.5]
                sniffer:
                  enable: true
                  sniff:
                    TLS: {ports: [443]}
                """;
        assertThat(validator.validate(yaml, refs).valid()).isTrue();
        assertThat(new SafeYaml().load(yaml).value().path("mixed-port").asInt()).isEqualTo(7890);
    }
    @Test void rejectsManagedFieldsMalformedTypesAndUnsafeExtensions() {
        assertThat(validator.validate("proxies: []", refs).errors()).extracting(Diagnostic::code).contains("MANAGED_FIELD");
        assertThat(validator.validate("mixed-port: 99999", refs).valid()).isFalse();
        assertThat(validator.validate("dns: []", refs).valid()).isFalse();
        assertThat(validator.validate("unknown-setting: true", refs).valid()).isFalse();
        assertThat(validator.validate("sub-rules: {fixture: [MATCH,Proxy]}", refs).errors()).extracting(Diagnostic::code).contains("UNSUPPORTED_BASE_REFERENCE");
    }
    @Test void textualProxyReferencesCannotSurviveGroupRenameSilently() {
        String yaml = "dns:\n  nameserver: ['https://dns.example.invalid/dns-query#Proxy']\n";
        assertThat(validator.validate(yaml, refs).errors()).extracting(Diagnostic::code).contains("UNSUPPORTED_BASE_REFERENCE");
        var renamed = new ReferenceIndex(Map.of("Renamed", refs.targets().get("Proxy")), Map.of());
        assertThat(validator.validate(yaml, renamed).errors()).extracting(Diagnostic::path).anyMatch(path -> path.contains("nameserver"));
        assertThat(validator.validate("dns:\n  nameserver-policy:\n    'rule-set:Domains': [1.1.1.1]\n", refs).valid()).isFalse();
    }
    @Test void providersValidateBehaviorFormatIntervalsAndDownloadOptions() {
        var options = new JsonMapper().createObjectNode();
        var providers = new ProviderValidator();
        for (String behavior : List.of("domain", "ipcidr", "classical")) {
            for (String format : List.of("yaml", "text", "mrs")) {
                var value = new ProviderDefinition(UUID.randomUUID(), "Fixture", "http", "https://example.invalid/rules", behavior, format, 3600, options);
                assertThat(providers.validate(List.of(value)).valid()).isEqualTo(!behavior.equals("classical") || !format.equals("mrs"));
            }
        }
        assertThat(providers.validate(List.of(new ProviderDefinition(UUID.randomUUID(), "Bad", "http", "file:///tmp/x", "domain", "yaml", 0, options))).valid()).isFalse();
        assertThat(providers.validate(List.of(new ProviderDefinition(UUID.randomUUID(), "Bad", "http", "https://example.invalid", "domain", "yaml", 1,
                options.put("proxy", "Proxy")))).errors()).extracting(Diagnostic::code).contains("UNSUPPORTED_PROVIDER_REFERENCE");
    }
    @Test void validatesPinnedDnsFieldNames() {
        assertThat(validator.validate("dns: {cache-max-size: 1000, ipv6-timeout: 300, fallback-lazy-query: true}", refs).valid()).isTrue();
        assertThat(validator.validate("dns: {cache-size: 1000}", refs).valid()).isFalse();
    }
    @Test void findsProviderReferencesInCompoundSelectors() {
        assertThat(validator.validate("dns:\n  nameserver-policy:\n    'geosite:cn,rule-set:Domains': [1.1.1.1]\n", refs).valid()).isFalse();
    }
}
