package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClashYamlParserTest {
    private final SubscriptionParser parser = new SubscriptionParser(new ClashYamlParser(new SafeYaml(), new ProtocolFieldValidator()));

    @Test void preservesTlsRealityAndTransportParameters() throws Exception {
        var result = parser.parse(Files.readAllBytes(Path.of("../tests/fixtures/subscriptions/clash.yaml")), InputFormat.CLASH_YAML);
        assertThat(result.errors()).isEmpty();
        assertThat(result.nodes()).hasSize(5);
        assertThat(result.nodes().getFirst().originalName()).isEqualTo("香港 SS ");
        assertThat(result.nodes().get(1).connection().path("ws-opts").path("path").asString()).isEqualTo("/fixture");
        assertThat(result.nodes().get(2).connection().path("reality-opts").path("short-id").asString()).isEqualTo("0123456789abcdef");
        assertThat(result.nodes().get(3).connection().path("obfs-password").asString()).isEqualTo("fixture-obfs-password");
    }

    @Test void malformedNodeCannotExposePartialCommitList() throws Exception {
        var result = parser.parse(Files.readAllBytes(Path.of("../tests/fixtures/subscriptions/one-bad-node.yaml")), InputFormat.AUTO);
        assertThat(result.committable()).isFalse();
        assertThat(result.nodes()).isEmpty();
        assertThat(result.errors()).extracting(Diagnostic::path).anyMatch(path -> path.contains("[1].uuid"));
        assertThat(result.errors().toString()).doesNotContain("fixture-password", "not-a-uuid");
    }

    @Test void distinguishesValidEmptyFromUnsupportedOrMalformedInput() {
        assertThat(parse("proxies: []").validEmpty()).isTrue();
        for (String text : new String[]{"", "  ", "{}", "proxy-providers: {x: {type: http}}", "proxies: null", "proxies: {a: b}"}) {
            assertThat(parse(text).hasErrors()).as(text).isTrue();
            assertThat(parse(text).validEmpty()).isFalse();
        }
        assertThat(parser.parse(new byte[]{(byte) 0xc0, (byte) 0xaf}, InputFormat.AUTO).hasErrors()).isTrue();
    }

    @Test void exactNameConflictsFailWhileCaseUnicodeAndSpacesStayDistinct() {
        String node = "{name: '%s', type: ss, server: fixture.example.com, port: 443, cipher: aes-128-gcm, password: fixture}";
        assertThat(parse("proxies: [" + node.formatted("HK") + "," + node.formatted("HK") + "]").errors())
                .extracting(Diagnostic::code).contains("DUPLICATE_NODE_NAME");
        assertThat(parse("proxies: [" + node.formatted("HK") + "," + node.formatted("hk") + "," + node.formatted("HK ")
                + "," + node.formatted("é") + "," + node.formatted("e\u0301") + "]").nodes()).hasSize(5);
    }

    @Test void rejectsUnknownFieldsWrongTypesDuplicateKeysAndTags() {
        String base = "{name: x, type: ss, server: fixture.example.com, port: 443, cipher: aes-128-gcm, password: fixture%s}";
        assertThat(parse("proxies: [" + base.formatted(", mysterious-setting: true") + "]").hasErrors()).isTrue();
        assertThat(parse("proxies: [" + base.formatted(", udp: 'yes'") + "]").hasErrors()).isTrue();
        assertThat(parse("proxies: [" + base.formatted(", port: 80") + "]").hasErrors()).isTrue();
        assertThat(parse("proxies: !!java.lang.ProcessBuilder []").hasErrors()).isTrue();
        assertThat(parse("proxies: " + "[".repeat(55) + "0" + "]".repeat(55)).hasErrors()).isTrue();
        assertThat(parse("proxies:\n" + "- {}\n".repeat(20_001)).errors()).extracting(Diagnostic::code).contains("NODE_LIMIT");
    }

    @Test void validatesEachPromisedProtocolAndNestedExtension() {
        for (String node : new String[]{
                "{name: x, type: ss, server: example.com, port: 0, cipher: aes-128-gcm, password: fixture}",
                "{name: x, type: vless, server: example.com, port: 443, uuid: invalid}",
                "{name: x, type: hysteria2, server: example.com, port: 443}",
                "{name: x, type: tuic, server: example.com, port: 443, uuid: invalid, password: fixture}",
                "{name: x, type: vless, server: example.com, port: 443, uuid: 00000000-0000-4000-8000-000000000001, ws-opts: {unknown: 1}}"}) {
            assertThat(parse("proxies: [" + node + "]").committable()).as(node).isFalse();
        }
    }

    @Test void regionIsOnlyANameHint() {
        assertThat(RegionHint.fromName("香港 SS")).isEqualTo("HK");
        assertThat(RegionHint.fromName("🇯🇵 东京")).isEqualTo("JP");
        assertThat(RegionHint.fromName("Ordinary node")).isEqualTo("UNKNOWN");
    }

    private ParseResult parse(String text) { return parser.parse(text.getBytes(StandardCharsets.UTF_8), InputFormat.AUTO); }
}
