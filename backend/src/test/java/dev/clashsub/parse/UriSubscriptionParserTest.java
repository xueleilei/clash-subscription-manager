package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UriSubscriptionParserTest {
    private final SubscriptionParser parser = new SubscriptionParser(new ClashYamlParser(new SafeYaml(), new ProtocolFieldValidator()));
    private static final String UUID = "00000000-0000-4000-8000-000000000001";

    @Test void allSevenProtocolsAndBothBase64AlphabetsProduceSameNodes() throws Exception {
        var text = Files.readString(Path.of("../tests/fixtures/subscriptions/uris.txt"));
        var plain = parse(text);
        assertThat(plain.errors()).isEmpty();
        assertThat(plain.nodes()).hasSize(7);
        assertThat(plain.nodes()).extracting(ParsedNode::protocol).containsExactly("ss", "ssr", "vmess", "vless", "trojan", "hysteria2", "tuic");
        assertThat(parse(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))).nodes()).isEqualTo(plain.nodes());
        assertThat(parse(b64(text)).nodes()).isEqualTo(plain.nodes());
        assertThat(parse(b64(b64(text))).committable()).isFalse();
    }

    @Test void ipv6PercentEscapesAndPlusArePreserved() {
        var result = parse("ss://aes-128-gcm:p%40ss%3Aword%2B%25@[2001:db8::1]:443#HK%20%E9%A6%99%E6%B8%AF%20");
        assertThat(result.errors()).isEmpty();
        assertThat(result.nodes().getFirst().originalName()).isEqualTo("HK 香港 ");
        assertThat(result.nodes().getFirst().connection().path("password").asString()).isEqualTo("p@ss:word+%");
        assertThat(result.nodes().getFirst().connection().path("server").asString()).isEqualTo("2001:db8::1");
        assertThat(parse("trojan://a+b@example.com:443#name+plus").nodes().getFirst().originalName()).isEqualTo("name+plus");
    }

    @Test void standardAndLegacySsAndVmessJsonAreDecoded() {
        assertThat(parse("ss://" + b64("aes-128-gcm:fixture") + "@example.com:443#ss").committable()).isTrue();
        assertThat(parse("ss://" + b64("aes-128-gcm:fixture@example.com:443") + "#ss").committable()).isTrue();
        var json = "{\"v\":\"2\",\"ps\":\"VMess WS\",\"add\":\"example.com\",\"port\":\"443\",\"id\":\"" + UUID + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"host.example.com\",\"path\":\"/socket\",\"tls\":\"tls\",\"sni\":\"sni.example.com\"}";
        var node = parse("vmess://" + b64(json)).nodes().getFirst();
        assertThat(node.connection().path("ws-opts").path("path").asString()).isEqualTo("/socket");
        assertThat(node.connection().path("servername").asString()).isEqualTo("sni.example.com");
    }

    @Test void vlessTlsRealityWsAndGrpcMapWithoutLosingOptions() {
        var reality = parse("vless://" + UUID + "@example.com:443?security=reality&sni=example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123456789abcdef&flow=xtls-rprx-vision#Reality");
        assertThat(reality.errors()).isEmpty();
        assertThat(reality.nodes().getFirst().connection().path("reality-opts").path("short-id").asString()).isEqualTo("0123456789abcdef");
        var grpc = parse("vless://" + UUID + "@example.com:443?security=tls&type=grpc&serviceName=service%2Fname&alpn=h2#Grpc");
        assertThat(grpc.errors()).isEmpty();
        assertThat(grpc.nodes().getFirst().connection().path("grpc-opts").path("grpc-service-name").asString()).isEqualTo("service/name");
        var ws = parse("vless://" + UUID + "@example.com:443?security=tls&type=ws&path=%2Fsocket&host=cdn.example.com#WS");
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.nodes().getFirst().connection().path("ws-opts").path("headers").path("Host").asString()).isEqualTo("cdn.example.com");
    }

    @Test void hy2AliasAndDefaultPortAndTuicAuthenticationAreExplicit() {
        assertThat(parse("hy2://fixture@example.com?sni=example.com#H").nodes().getFirst().connection().path("port").asInt()).isEqualTo(443);
        var hop = parse("hysteria2://fixture@example.com:443,1000-2000/?insecure=0#H");
        assertThat(hop.errors()).isEmpty();
        assertThat(hop.nodes().getFirst().connection().path("ports").asString()).isEqualTo("443,1000-2000");
        assertThat(parse("tuic://" + UUID + ":fixture@example.com:443?congestion_control=bbr&udp_relay_mode=native#T").committable()).isTrue();
        assertThat(parse("tuic://not-a-uuid:fixture@example.com:443#T").committable()).isFalse();
    }

    @Test void mixedUnknownAndMalformedInputsFailEntireListWithSafeLineErrors() {
        var valid = "trojan://fixture-secret@example.com:443#T\n";
        for (String invalid : new String[]{"vless://invalid@example.com:443", "unknown://fixture@example.com:443",
                "hy2://fixture@example.com?unknown=1", "trojan://fixture@example.com:443?security=none",
                "ss://invalid", "ssr://!!", "vmess://" + b64("{\"id\":\"bad\"}"), "tuic://bad:fixture@example.com:443",
                "trojan://fixture@example.com:443?allowInsecure=maybe", "trojan://fixture@example.com:443?sni=a&sni=b"}) {
            var result = parse(valid + invalid);
            assertThat(result.committable()).as(invalid).isFalse();
            assertThat(result.nodes()).isEmpty();
            assertThat(result.errors()).extracting(Diagnostic::line).contains(2);
            assertThat(result.errors().toString()).doesNotContain("fixture-secret");
        }
    }

    @Test void unknownFieldsAndUnsupportedTransportsAreRejected() {
        for (String extra : new String[]{"type=xhttp", "type=kcp", "security=unknown", "spx=%2F", "pbk=abc"}) {
            assertThat(parse("vless://" + UUID + "@example.com:443?" + extra).committable()).as(extra).isFalse();
        }
        assertThat(parse("vmess://" + b64("{\"add\":\"example.com\",\"port\":443,\"id\":\"" + UUID + "\",\"unknown\":true}")).committable()).isFalse();
    }

    @Test void namelessDuplicatesFailAndNamesNeverContainCredentials() {
        var link = "trojan://fixture-secret@example.com:443";
        assertThat(parse(link).nodes().getFirst().originalName()).isEqualTo("trojan example.com:443");
        assertThat(parse(link + "\n" + link).errors()).extracting(Diagnostic::code).contains("DUPLICATE_NODE_NAME");
        assertThat(parse("# comment only").hasErrors()).isTrue();
    }

    @Test void equivalentIpv6NamesConflict() {
        var duplicates = parse("trojan://fixture@[2001:db8::1]:443\ntrojan://fixture@[2001:0db8:0:0:0:0:0:1]:443");
        assertThat(duplicates.errors()).extracting(Diagnostic::code).contains("DUPLICATE_NODE_NAME");
    }

    @Test void visionRequiresTls() {
        assertThat(parse("vless://" + UUID + "@example.com:443?security=none&flow=xtls-rprx-vision").committable()).isFalse();
    }

    @Test void legacySsDecodesCredentialsExactlyOnce() {
        var result = parse("ss://" + b64("aes-128-gcm:fixture@%?#/:+@example.com:443") + "#Legacy");
        assertThat(result.errors()).isEmpty();
        assertThat(result.nodes().getFirst().connection().path("password").asString()).isEqualTo("fixture@%?#/:+");
    }

    @Test void ssrAllowsExplicitEmptyOptionalParameters() {
        var body = "example.com:443:auth_sha1_v4:aes-128-cfb:plain:" + b64("fixture")
                + "/?obfsparam=&protoparam=&remarks=&group=";
        assertThat(parse("ssr://" + b64(body)).errors()).isEmpty();
    }

    private ParseResult parse(String text) { return parser.parse(text.getBytes(StandardCharsets.UTF_8), InputFormat.AUTO); }
    private static String b64(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
}
