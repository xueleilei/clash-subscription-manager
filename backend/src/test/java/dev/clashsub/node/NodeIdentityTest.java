package dev.clashsub.node;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeIdentityTest {
    private final UUID source = UUID.randomUUID();

    @Test void identityUsesSourceAndExactUtf8Name() {
        assertThat(NodeIdentity.key(source, "香港 🇭🇰")).isEqualTo(NodeIdentity.key(source, "香港 🇭🇰"));
        assertThat(NodeIdentity.key(source, "香港")).isNotEqualTo(NodeIdentity.key(UUID.randomUUID(), "香港"));
        assertThat(NodeIdentity.key(source, "HK")).isNotEqualTo(NodeIdentity.key(source, "hk"));
        assertThat(NodeIdentity.key(source, "香港")).isNotEqualTo(NodeIdentity.key(source, "香港 "));
        assertThat(NodeIdentity.key(source, "é")).isNotEqualTo(NodeIdentity.key(source, "e\u0301"));
        assertThat(NodeIdentity.key(source, "香港").nameHash()).hasSize(64);
    }

    @Test void hashIndexStillVerifiesOriginalName() {
        var key = NodeIdentity.key(source, "香港 ");
        assertThat(key.matches(source, "香港 ")).isTrue();
        assertThat(key.matches(source, "香港")).isFalse();
        assertThat(key.matches(UUID.randomUUID(), "香港 ")).isFalse();
        assertThatThrownBy(() -> NodeIdentity.key(source, "\uD800")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void connectionIsDefensivelyCopiedAndNeverLogged() {
        var connection = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        connection.put("password", "fixture-secret");
        var node = new NodeSpec(UUID.randomUUID(), source, "Hong Kong", "ss", connection, null);
        connection.put("password", "changed");
        node.connection().put("password", "also-changed");
        assertThat(node.connection().path("password").asString()).isEqualTo("fixture-secret");
        assertThat(node.toString()).doesNotContain("fixture-secret", "password");
    }
}
