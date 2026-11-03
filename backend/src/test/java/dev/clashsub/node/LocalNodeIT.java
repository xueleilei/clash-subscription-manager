package dev.clashsub.node;

import dev.clashsub.sync.RefreshTestSupport;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import dev.clashsub.profile.TargetRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

class LocalNodeIT extends RefreshTestSupport {
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired LocalNodeService localNodes;
    private HttpClient client;

    @BeforeEach void authenticate() throws Exception {
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", passwords.encode("Fixture-manual-234567"), owner.toString());
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var csrf = data(send("GET", "/csrf", null));
        var response = client.send(HttpRequest.newBuilder(uri("/session"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(csrf.path("headerName").asString(), csrf.path("token").asString())
                .POST(HttpRequest.BodyPublishers.ofString("username=refresh-" + owner + "&password=Fixture-manual-234567")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test void createsValidatedEncryptedLocalNodeAndListsMetadataOnly() throws Exception {
        var created = ok(send("POST", "/local-nodes", input("Manual fixture", "fixture-local-secret")));
        var id = UUID.fromString(created.path("id").asString());
        var detail = send("GET", "/local-nodes/" + id, null);
        assertThat(detail.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(ok(detail).path("node").path("connection").path("password").asString()).isEqualTo("fixture-local-secret");
        var list = send("GET", "/nodes?localOnly=true", null);
        assertThat(ok(list).path("total").asInt()).isEqualTo(1);
        assertThat(list.body()).doesNotContain("fixture-local-secret", "password");
        assertThat(nodes.connection(owner, id).sourceId()).isNull();
        byte[] cipher = jdbc.queryForObject("SELECT connection_cipher FROM node WHERE id=?", byte[].class, id.toString());
        assertThat(new String(cipher, StandardCharsets.UTF_8)).doesNotContain("fixture-local-secret");
    }

    @Test void editingLocalParametersIsPinnedAcrossTwoRemoteRefreshesUntilManualPublication() throws Exception {
        var created = ok(send("POST", "/local-nodes", input("Local old name", "fixture-local-old")));
        UUID id = UUID.fromString(created.path("id").asString());
        UUID upstream = source("Remote", yaml("Remote node"));
        UUID profile = profile("Mixed", List.of(id, node(upstream, "Remote node").id()), null);
        long beforeVersion = profiles.load(owner, profile).version();
        var edit = input("Local new name", "fixture-local-new").put("expectedVersion", created.path("version").asString());
        var saved = ok(send("PUT", "/local-nodes/" + id, edit));
        assertThat(saved.path("id").asString()).isEqualTo(id.toString());
        assertThat(saved.path("affectedProfiles").toString()).contains(profile.toString());
        assertThat(profiles.load(owner, profile).version()).isEqualTo(beforeVersion + 1);
        assertThat(profiles.load(owner, profile).document().groups().getFirst().members()).contains(TargetRef.node(id));
        assertThat(profileRow(profile).path("hasUnpublishedNodeChanges").asBoolean()).isTrue();
        assertThat(artifact(profile)).contains("fixture-local-old").doesNotContain("fixture-local-new");
        for (String secret : List.of("fixture-remote-second", "fixture-remote-third")) {
            respond(upstream, yaml("Remote node").replace("fixture-first", secret));
            assertThat(run(upstream).status()).isEqualTo("SUCCESS");
            assertThat(artifact(profile)).contains(secret, "fixture-local-old").doesNotContain("fixture-local-new", "Local new name");
        }
        publisher.publish(owner, profile, profiles.load(owner, profile).version(), null);
        assertThat(artifact(profile)).contains("fixture-local-new", "Local new name").doesNotContain("fixture-local-old");
        assertThat(profileRow(profile).path("hasUnpublishedNodeChanges").asBoolean()).isFalse();
    }

    @Test void deletingLocalNodeChangesOnlyDraftAndDoesNotBreakAutomaticRefresh() throws Exception {
        var created = ok(send("POST", "/local-nodes", input("Delete fixture", "fixture-local-delete")));
        UUID id = UUID.fromString(created.path("id").asString());
        UUID upstream = source("Remote", yaml("Remote node"));
        UUID profile = profile("Delete mixed", List.of(id, node(upstream, "Remote node").id()), id);
        var preview = ok(send("GET", "/local-nodes/" + id + "/removal", null));
        assertThat(preview.path("affectedProfiles").toString()).contains(profile.toString());
        assertThat(preview.path("publishedProfiles").toString()).contains(profile.toString());
        ok(send("DELETE", "/local-nodes/" + id, json.createObjectNode()
                .put("expectedVersion", preview.path("version").asString()).put("ownerRevision", preview.path("ownerRevision").asString())));
        assertThat(send("GET", "/local-nodes/" + id, null).statusCode()).isEqualTo(404);
        assertThat(profiles.load(owner, profile).document().groups().getFirst().members()).doesNotContain(TargetRef.node(id));
        assertThat(profiles.load(owner, profile).document().rules().getFirst().target()).isEqualTo(TargetRef.reject());
        assertThat(artifact(profile)).contains("fixture-local-delete");
        for (String secret : List.of("fixture-remote-second", "fixture-remote-third")) {
            respond(upstream, yaml("Remote node").replace("fixture-first", secret));
            assertThat(run(upstream).status()).isEqualTo("SUCCESS");
            assertThat(artifact(profile)).contains(secret, "fixture-local-delete");
        }
        publisher.publish(owner, profile, profiles.load(owner, profile).version(), null);
        assertThat(artifact(profile)).doesNotContain("fixture-local-delete");
    }

    @Test void staleEditAndDeletionPreviewCannotOverwriteChanges() throws Exception {
        var created = ok(send("POST", "/local-nodes", input("Version fixture", "fixture-local-one")));
        String id = created.path("id").asString();
        var oldInput = input("Version fixture", "fixture-local-two").put("expectedVersion", created.path("version").asString());
        ok(send("PUT", "/local-nodes/" + id, oldInput));
        assertThat(send("PUT", "/local-nodes/" + id, oldInput).statusCode()).isEqualTo(409);
        var preview = ok(send("GET", "/local-nodes/" + id + "/removal", null));
        ok(send("POST", "/local-nodes", input("Other fixture", "fixture-other")));
        assertThat(send("DELETE", "/local-nodes/" + id, json.createObjectNode()
                .put("expectedVersion", preview.path("version").asString()).put("ownerRevision", preview.path("ownerRevision").asString())).statusCode()).isEqualTo(409);
        assertThat(ok(send("GET", "/local-nodes/" + id, null)).path("node").path("connection").path("password").asString()).isEqualTo("fixture-local-two");
    }

    @Test void chainedLocalReferencesMarkDraftsAndBlockDeletingTheirGateway() throws Exception {
        var gateway = ok(send("POST", "/local-nodes", input("Gateway", "fixture-gateway")));
        UUID id = UUID.fromString(gateway.path("id").asString());
        var leafInput = input("Leaf", "fixture-leaf"); leafInput.set("dialerRef", json.valueToTree(TargetRef.node(id)));
        UUID leaf = UUID.fromString(ok(send("POST", "/local-nodes", leafInput)).path("id").asString());
        UUID profile = profile("Chain", List.of(leaf), null);
        var result = ok(send("PUT", "/local-nodes/" + id, input("Gateway", "fixture-gateway-edited").put("expectedVersion", gateway.path("version").asString())));
        assertThat(result.path("affectedProfiles").toString()).contains(profile.toString());
        assertThat(profileRow(profile).path("hasUnpublishedNodeChanges").asBoolean()).isTrue();
        var preview = ok(send("GET", "/local-nodes/" + id + "/removal", null));
        assertThat(preview.path("dependentNodes").toString()).contains(leaf.toString());
        assertThat(send("DELETE", "/local-nodes/" + id, json.createObjectNode()
                .put("expectedVersion", preview.path("version").asString()).put("ownerRevision", preview.path("ownerRevision").asString())).statusCode()).isEqualTo(409);
        var cycle = input("Gateway", "fixture-gateway-edited").put("expectedVersion", result.path("version").asString());
        cycle.set("dialerRef", json.valueToTree(TargetRef.node(leaf)));
        assertThat(send("PUT", "/local-nodes/" + id, cycle).statusCode()).isEqualTo(422);
    }

    @Test void rejectsInvalidCoreOptionsReservedFieldsForeignReferencesAndRemoteEdits() throws Exception {
        var invalid = input("Invalid", "fixture-secret"); ((ObjectNode) invalid.path("connection")).put("unknown-option", true);
        assertThat(send("POST", "/local-nodes", invalid).statusCode()).isEqualTo(422);
        var reserved = input("Reserved", "fixture-secret"); ((ObjectNode) reserved.path("connection")).put("type", "trojan");
        assertThat(send("POST", "/local-nodes", reserved).statusCode()).isEqualTo(422);
        var broken = json.createObjectNode().put("name", "Core invalid").put("protocol", "hysteria2");
        broken.putObject("connection").put("server", "fixture.example.invalid").put("ports", "443-444").put("hop-interval", "invalid-interval").put("password", "fixture-secret");
        assertThat(send("POST", "/local-nodes", broken).statusCode()).isEqualTo(422);
        var foreign = input("Foreign", "fixture-secret"); foreign.set("dialerRef", json.valueToTree(TargetRef.node(UUID.randomUUID())));
        assertThat(send("POST", "/local-nodes", foreign).statusCode()).isEqualTo(404);
        UUID upstream = source("Remote", yaml("Remote node")); UUID remote = node(upstream, "Remote node").id();
        assertThat(send("GET", "/local-nodes/" + remote, null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/local-nodes/" + remote, input("Remote", "fixture-secret").put("expectedVersion", "1")).statusCode()).isEqualTo(404);
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isZero();
    }

    @Test void parsesOnlyOneNodeWithoutFetchingAnUpstream() throws Exception {
        var uri = json.createObjectNode().put("text", "vless://00000000-0000-4000-8000-000000000001@fixture.example.invalid:443?security=tls&type=ws#Manual");
        var response = send("POST", "/local-nodes/parse", uri);
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(ok(response).path("name").asString()).isEqualTo("Manual");
        var singleYaml = "name: YAML fixture\ntype: ss\nserver: fixture.example.invalid\nport: 443\ncipher: aes-128-gcm\npassword: fixture-only\n";
        assertThat(ok(send("POST", "/local-nodes/parse", json.createObjectNode().put("text", singleYaml))).path("protocol").asString()).isEqualTo("ss");
        assertThat(send("POST", "/local-nodes/parse", json.createObjectNode().put("text", yaml("One", "Two"))).statusCode()).isEqualTo(422);
        assertThat(send("POST", "/local-nodes/parse", json.createObjectNode().put("text", "proxies: []")).statusCode()).isEqualTo(422);
        org.mockito.Mockito.verify(fetcher, org.mockito.Mockito.never()).fetch(org.mockito.ArgumentMatchers.any());
    }

    @Test void anotherOwnersRealLocalNodeCannotBeReadChangedDeletedOrUsedAsDialer() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", other.toString(), "local-other-" + other, "fixture-hash");
        var connection = (ObjectNode) input("Other owner", "fixture-other-owner-secret").path("connection");
        var foreign = localNodes.save(other, null, new LocalNodeCommand(null, "Other owner", "ss", connection, null));
        assertThat(send("GET", "/local-nodes/" + foreign.id(), null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/local-nodes/" + foreign.id(), input("Overwrite", "fixture-new").put("expectedVersion", foreign.version())).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/local-nodes/" + foreign.id(), json.createObjectNode().put("expectedVersion", foreign.version()).put("ownerRevision", "0")).statusCode()).isEqualTo(404);
        var request = input("Foreign dialer", "fixture-secret"); request.set("dialerRef", json.valueToTree(TargetRef.node(foreign.id())));
        assertThat(send("POST", "/local-nodes", request).statusCode()).isEqualTo(404);
        assertThat(nodes.connection(other, foreign.id()).connection().path("password").asString()).isEqualTo("fixture-other-owner-secret");
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isZero();
    }

    private JsonNode profileRow(UUID id) throws Exception {
        for (var profile : ok(send("GET", "/profiles", null))) { if (profile.path("id").asString().equals(id.toString())) { return profile; } }
        throw new AssertionError("Profile not found");
    }

    private ObjectNode input(String name, String password) {
        var input = json.createObjectNode().put("name", name).put("protocol", "ss");
        input.putObject("connection").put("server", "fixture.example.invalid").put("port", 443).put("cipher", "aes-128-gcm").put("password", password);
        return input;
    }
    private HttpResponse<String> send(String method, String path, JsonNode body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path));
        if (!method.equals("GET")) {
            var csrf = data(send("GET", "/csrf", null));
            builder.header(csrf.path("headerName").asString(), csrf.path("token").asString());
        }
        builder.header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1" + path); }
    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).path("data"); }
    private JsonNode ok(HttpResponse<String> response) { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return data(response); }
}
