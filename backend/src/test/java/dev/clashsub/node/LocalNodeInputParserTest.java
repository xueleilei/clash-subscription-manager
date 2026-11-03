package dev.clashsub.node;

import dev.clashsub.common.ApiValidationException;
import dev.clashsub.parse.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LocalNodeInputParserTest {
    private final SafeYaml yaml = new SafeYaml();
    private final LocalNodeInputParser parser = new LocalNodeInputParser(new SubscriptionParser(new ClashYamlParser(yaml, new ProtocolFieldValidator())), yaml);

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
}
