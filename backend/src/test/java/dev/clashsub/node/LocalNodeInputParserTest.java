package dev.clashsub.node;

import dev.clashsub.common.ApiValidationException;
import dev.clashsub.parse.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalNodeInputParserTest {
    private final SafeYaml yaml = new SafeYaml();
    private final LocalNodeInputParser parser = new LocalNodeInputParser(new SubscriptionParser(new ClashYamlParser(yaml, new ProtocolFieldValidator())), yaml);

    @Test void adjacentFlowObjectsReportSingleEditLimitInsteadOfUnsupportedYaml() {
        String node = "{name: '%s', type: ss, server: fixture.invalid, port: 443, cipher: aes-128-gcm, password: fixture-only}";
        var error = catchThrowableOfType(() -> parser.parse(node.formatted("One") + "\n" + node.formatted("Two")), ApiValidationException.class);
        assertThat(error.report().errors()).extracting(dev.clashsub.common.Diagnostic::code).containsExactly("LOCAL_NODE_COUNT");
    }

    @Test void singleBase64ShareLinkRetainsAuthenticationAndTransportParameters() {
        String uri = "vless://00000000-0000-4000-8000-000000000001@fixture.example.invalid:443?security=tls&type=ws&path=%2Ffixture#Manual";
        var node = parser.parse(Base64.getEncoder().encodeToString(uri.getBytes(StandardCharsets.UTF_8)));
        assertThat(node.originalName()).isEqualTo("Manual");
        assertThat(node.connection().path("uuid").asString()).isEqualTo("00000000-0000-4000-8000-000000000001");
        assertThat(node.connection().path("ws-opts").path("path").asString()).isEqualTo("/fixture");
    }

    @Test void diagnosticsNeverEchoPrivateMalformedInputAndLimitUtf8Bytes() {
        var error = catchThrowableOfType(() -> parser.parse("proxies: [{name: fixture-secret, password: fixture-private-password, broken: [}"), ApiValidationException.class);
        assertThat(error.report().toString()).doesNotContain("fixture-secret", "fixture-private-password");
        var oversized = catchThrowableOfType(() -> parser.parse("name: " + "节".repeat(100_000)), ApiValidationException.class);
        assertThat(oversized.report().errors()).extracting(dev.clashsub.common.Diagnostic::code).containsExactly("LOCAL_NODE_INPUT");
    }

    @Test void acceptsCopiedObjectsYamlListsAndWrappedProxiesWithoutChangingParameters() {
        String one = "{name: '香港 # 01', type: ss, server: fixture.invalid, port: 443, cipher: aes-128-gcm, password: fixture-only, plugin: obfs, plugin-opts: {mode: http, host: fixture.invalid}}";
        String two = one.replace("香港 # 01", "香港 02").replace("port: 443", "port: 444");
        for (String text : List.of(one + "\n# node boundary\n" + two, "- " + one + "\n- " + two, "[" + one + ", " + two + "]", "proxies:\n- " + one + "\n- " + two)) {
            var parsed = parser.parseAll(text);
            assertThat(parsed).hasSize(2);
            assertThat(parsed.getFirst().originalName()).isEqualTo("香港 # 01");
            assertThat(parsed.getFirst().connection().path("plugin-opts").path("host").asString()).isEqualTo("fixture.invalid");
            assertThat(parsed.getLast().connection().path("port").asInt()).isEqualTo(444);
        }
    }

    @Test void acceptsMultipleShareLinksAndBase64AndRejectsPartialOrDuplicateBatches() {
        String one = "vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443#One";
        String links = one + "\n" + one.replace("#One", "#Two");
        assertThat(parser.parseAll(links)).hasSize(2);
        assertThat(parser.parseAll(Base64.getEncoder().encodeToString(links.getBytes(StandardCharsets.UTF_8)))).hasSize(2);
        assertThatThrownBy(() -> parser.parseAll(one + "\nunsupported://fixture-only")).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> parser.parseAll(one + "\n" + one)).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> parser.parseAll("proxies: []")).isInstanceOf(ApiValidationException.class);
        String tooMany = java.util.stream.IntStream.range(0, 501).mapToObj(index -> one.replace("#One", "#Node" + index)).collect(java.util.stream.Collectors.joining("\n"));
        var error = catchThrowableOfType(() -> parser.parseAll(tooMany), ApiValidationException.class);
        assertThat(error.report().errors()).extracting(dev.clashsub.common.Diagnostic::code).containsExactly("LOCAL_NODE_COUNT");
    }

    @Test void yamlListErrorsPointAtTheOriginalInputLineWithoutAnIncorrectNormalizedColumn() {
        var error = catchThrowableOfType(() -> parser.parseAll("- {name: One, type: ss, server: fixture.invalid, port: 443, cipher: aes-128-gcm, password: fixture-only}\n- {name: Broken, type: ss, server: [}"), ApiValidationException.class);
        assertThat(error.report().errors().getFirst().line()).isEqualTo(2);
        assertThat(error.report().errors().getFirst().column()).isNull();
    }
}
