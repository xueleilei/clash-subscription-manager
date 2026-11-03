package dev.clashsub.source;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.node.NodeQuery;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.parse.InputFormat;
import dev.clashsub.support.MySqlIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.springframework.test.context.TestPropertySource(properties = "clash.fetch.trusted-proxy-url=http://127.0.0.1:9999")
class SourceRepositoryIT extends MySqlIntegrationTest {
    @Autowired SourceService service;
    @Autowired SourceRepository sources;
    @Autowired NodeRepository nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired OwnerRevisionGuard revisions;
    @Autowired JsonMapper json;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    UUID owner;

    @BeforeEach void owner() {
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", owner.toString(), "fixture-" + owner, "fixture-hash");
    }

    @Test void createsEncryptedSourceWithDefaultScheduleAndNoListSecrets() {
        var view = service.save(owner, create());
        assertThat(view.refreshIntervalSeconds()).isEqualTo(21_600);
        assertThat(view.configVersion()).isEqualTo("1");
        assertThat(view.lastAttemptTime()).isNull(); assertThat(view.lastSuccessTime()).isNull();
        assertThat(view.nextRefreshTime()).isBetween(java.time.Instant.now().minusSeconds(10), java.time.Instant.now().plusSeconds(10));
        assertThat(revisions.current(owner)).isEqualTo(1);
        var row = jdbc.queryForObject("SELECT request_cipher FROM subscription_source WHERE id=?", String.class, view.id().toString());
        assertThat(row).doesNotContain("fixture-password", "secret-query", "example.com");
        assertThat(json.writeValueAsString(service.list(owner))).doesNotContain("fixture-password", "secret-query", "Authorization");
        assertThat(service.credentials(owner, view.id()).headers()).containsEntry("Authorization", "Bearer fixture-password");
    }

    @Test void absentSecretsAreKeptAndOldVersionsConflict() {
        var view = service.save(owner, create());
        var changed = service.save(owner, new SourceCommand(view.id(), 1L, "Renamed", null, null, null, null, null, null, null));
        assertThat(changed.configVersion()).isEqualTo("2");
        assertThat(service.credentials(owner, view.id()).url()).contains("secret-query");
        assertThatThrownBy(() -> service.save(owner, new SourceCommand(view.id(), 1L, "Stale", null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(service.list(owner).getFirst().name()).isEqualTo("Renamed");
        assertThat(revisions.current(owner)).isEqualTo(2);
    }

    @Test void sourceMetadataExposesSavedProxyAliasWithoutConnectionSecrets() throws Exception {
        var admin = UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN));
        var view = service.save(admin, create());
        var sourceId = view.id().toString();
        var client = new dev.clashsub.support.ApiTestClient(port);
        client.login(ADMIN, PASSWORD);
        for (String proxy : List.of("default", "")) {
            view = service.save(admin, new SourceCommand(view.id(), Long.parseLong(view.configVersion()), null, null, null, null, null, null, null, proxy));
            var response = client.get("/api/v1/sources");
            assertThat(response.statusCode()).isEqualTo(200);
            var item = java.util.stream.StreamSupport.stream(client.data(response).spliterator(), false)
                    .filter(value -> value.path("id").asString().equals(sourceId)).findFirst().orElseThrow();
            assertThat(item.has("outboundProxy")).isTrue();
            assertThat(item.path("outboundProxy").asString()).isEqualTo(proxy);
            assertThat(response.body()).doesNotContain("fixture-password", "secret-query", "Authorization", "127.0.0.1:9999");
        }
    }

    @Test void ownerScopeAndPlaceholderProtectionAreEnforced() {
        var view = service.save(owner, create());
        assertThatThrownBy(() -> service.credentials(UUID.randomUUID(), view.id())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        assertThatThrownBy(() -> service.save(UUID.randomUUID(), new SourceCommand(view.id(), 1L, "Foreign", null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        assertThatThrownBy(() -> service.save(owner, new SourceCommand(view.id(), 1L, null, null, null, null, null, Map.of("Authorization", "********"), null, null)))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
    }

    @Test void nodeConnectionsStayEncryptedAndQueriesStayOwnerScoped() {
        var source = service.save(owner, create());
        var nodeId = UUID.randomUUID();
        var connection = json.createObjectNode().put("server", "fixture.example.com").put("port", 443).put("cipher", "aes-128-gcm").put("password", "fixture-password");
        var node = new NodeSpec(nodeId, source.id(), "香港 SS ", "ss", connection, null);
        transaction.executeWithoutResult(tx -> nodes.replaceSource(owner, source.id(), 1, List.of(node)));
        var row = jdbc.queryForObject("SELECT connection_cipher FROM node WHERE id=?", String.class, nodeId.toString());
        assertThat(row).doesNotContain("fixture-password", "fixture.example.com");
        var page = nodes.list(owner, new NodeQuery(source.id(), "ss", "HK", "香港", 1, 50));
        assertThat(page.items()).hasSize(1);
        assertThat(json.writeValueAsString(page)).doesNotContain("fixture-password", "fixture.example.com");
        assertThat(nodes.connection(owner, nodeId).connection().path("password").asString()).isEqualTo("fixture-password");
        assertThat(nodes.list(UUID.randomUUID(), new NodeQuery(null, null, null, null, 1, 50)).items()).isEmpty();
        assertThatThrownBy(() -> nodes.connection(UUID.randomUUID(), nodeId)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
    }

    @Test void explicitCredentialEndpointsRequireLoginOwnershipAndNoStore() throws Exception {
        var admin = UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN));
        var own = service.save(admin, create());
        var foreign = service.save(owner, create());
        var nodeId = UUID.randomUUID();
        var node = new NodeSpec(nodeId, own.id(), "HTTP fixture", "ss", json.createObjectNode().put("password", "fixture-password"), null);
        transaction.executeWithoutResult(tx -> nodes.replaceSource(admin, own.id(), 1, List.of(node)));
        var client = new dev.clashsub.support.ApiTestClient(port);
        assertThat(client.get("/api/v1/sources/" + own.id() + "/credentials").statusCode()).isEqualTo(401);
        client.login(ADMIN, PASSWORD);
        var credentials = client.get("/api/v1/sources/" + own.id() + "/credentials");
        assertThat(credentials.statusCode()).isEqualTo(200);
        assertThat(credentials.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(client.data(credentials).path("url").asString()).contains("secret-query");
        assertThat(client.get("/api/v1/sources/" + foreign.id() + "/credentials").statusCode()).isEqualTo(404);
        var connection = client.get("/api/v1/nodes/" + nodeId + "/connection");
        assertThat(connection.statusCode()).isEqualTo(200);
        assertThat(connection.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(client.get("/api/v1/nodes").body()).doesNotContain("fixture-password", "secret-query");
    }

    @Test void malformedQueryIsBadRequestInsteadOfInternalError() throws Exception {
        var client = new dev.clashsub.support.ApiTestClient(port);
        client.login(ADMIN, PASSWORD);
        assertThat(client.get("/api/v1/nodes?page=invalid").statusCode()).isEqualTo(400);
    }

    @Test void stableNodeIdCannotBeReusedForARenamedUpstreamNode() {
        var source = service.save(owner, create());
        var id = UUID.randomUUID();
        var first = new NodeSpec(id, source.id(), "First", "ss", json.createObjectNode(), null);
        transaction.executeWithoutResult(tx -> nodes.replaceSource(owner, source.id(), 1, List.of(first)));
        var renamed = new NodeSpec(id, source.id(), "Renamed", "ss", json.createObjectNode(), null);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> nodes.replaceSource(owner, source.id(), 2, List.of(renamed))))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(nodes.connection(owner, id).originalName()).isEqualTo("First");
    }

    @Test void sourceListCountsOnlyCurrentOwnedNodes() {
        var first = service.save(owner, create()); var second = service.save(owner, create());
        var a = new NodeSpec(UUID.randomUUID(), first.id(), "A", "ss", json.createObjectNode(), null);
        var b = new NodeSpec(UUID.randomUUID(), first.id(), "B", "ss", json.createObjectNode(), null);
        transaction.executeWithoutResult(tx -> nodes.replaceSource(owner, first.id(), 1, List.of(a, b)));
        assertThat(json.valueToTree(service.list(owner)).get(0).path("nodeCount").asInt(-1)).isEqualTo(2);
        transaction.executeWithoutResult(tx -> nodes.replaceSource(owner, first.id(), 2, List.of(a)));
        var counts = json.valueToTree(service.list(owner));
        assertThat(counts.get(0).path("nodeCount").asInt(-1)).isEqualTo(1);
        assertThat(counts.get(1).path("nodeCount").asInt(-1)).isZero();
        assertThat(counts.get(1).path("id").asString()).isEqualTo(second.id().toString());
    }

    private SourceCommand create() {
        return new SourceCommand(null, null, "Fixture subscription", true, null, InputFormat.AUTO,
                "https://example.com/sub?secret-query=fixture-password", Map.of("Authorization", "Bearer fixture-password"), "fixture-UA", "");
    }
}
