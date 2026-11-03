package dev.clashsub.compile;

import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.rule.ProviderDefinition;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static dev.clashsub.compile.ProfileCompilerTest.*;

class ProfileCompilerIT {
    @Test void realCoreAcceptsBothEmptyGroupsProvidersAndGeodata() {
        var provider = new ProviderDefinition(PROVIDER, "Fixture", "http", "https://example.invalid/rules.mrs", "domain", "mrs", 3600, JSON.createObjectNode().put("size-limit", 0));
        var providerRule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("RULE-SET", "Fixture", PROVIDER), TargetRef.group(GROUP), List.of(), "", List.of());
        var doc = new ProfileDocument(1, "mode: rule\ngeodata-mode: true\ndns: {enable: false}\n", List.of(group(GROUP, "Select empty", "select"), group(UUID.randomUUID(), "Auto empty", "url-test")),
                List.of(providerRule, rule("GEOSITE", "cn", TargetRef.reject()), new RuleDefinition(UUID.randomUUID(), new RuleExpression("GEOIP", "CN", null), TargetRef.reject(), List.of("no-resolve"), "", List.of()),
                        rule("MATCH", "", TargetRef.group(GROUP))), List.of(provider), List.of());
        var compiled = compiler().compile(PROFILE, doc, NodeCatalog.empty(), Map.of()); assertThat(compiled.report().errors()).isEmpty();
        var root = Path.of("..").toAbsolutePath().normalize();
        var executable = root.resolve(".cache/mihomo/" + (System.getProperty("os.name").contains("Mac") ? "darwin" : "linux") + "-" +
                (System.getProperty("os.arch").contains("aarch64") ? "arm64" : "amd64") + "/mihomo");
        var validator = new MihomoValidator(executable, root.resolve(".cache/mihomo/resources"), Duration.ofSeconds(10), YAML);
        assertThat(validator.validate(compiled.compiled().yaml()).errors()).isEmpty();
    }
}
