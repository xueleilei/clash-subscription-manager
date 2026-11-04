package dev.clashsub.node;

import dev.clashsub.parse.SafeYaml;
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
    @Autowired LocalNodeCommitter localCommitter;
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

    @Test void servesConfiguredOptionsAndFiltersLocalAndSubscriptionNodesByTheSameCodes() throws Exception {
        var groups = ok(send("GET", "/nodes/regions", null));
        assertThat(groups.toString()).contains("\"code\":\"MO\"", "澳门", "\"code\":\"BR\"").doesNotContain("aliases", "connection");
        var local = ok(send("POST", "/local-nodes", input("澳門 01", "fixture-secret")));
        UUID upstream = source("Fixture source", yaml("🇲🇴 02"));
        var all = ok(send("GET", "/nodes?region=MO", null));
        assertThat(all.path("total").asInt()).isEqualTo(2);
        var manual = ok(send("GET", "/nodes?region=MO&localOnly=true", null));
        assertThat(manual.path("total").asInt()).isEqualTo(1);
        assertThat(manual.path("items").get(0).path("id")).isEqualTo(local.path("id"));
        var remote = ok(send("GET", "/nodes?region=MO&sourceId=" + upstream, null));
        assertThat(remote.path("total").asInt()).isEqualTo(1);
        assertThat(remote.path("items").get(0).path("region").asString()).isEqualTo("MO");
        ok(send("PUT", "/local-nodes/" + local.path("id").asString(), input("Sydney 01", "fixture-secret").put("expectedVersion", "0")));
        assertThat(ok(send("GET", "/nodes?region=AU&localOnly=true", null)).path("total").asInt()).isOne();
    }

    @Test void manualRegionWinsOverTheNameAndCanBeResetWithoutChangingThePublishedNode() throws Exception {
        var command = input("香港 fixture", "fixture-only").put("regionOverride", "US");
        var created = ok(send("POST", "/local-nodes", command));
        UUID id = UUID.fromString(created.path("id").asString());
        String path = "/local-nodes/" + id;
        assertThat(ok(send("GET", path, null)).path("regionOverride").asString()).isEqualTo("US");
        assertThat(ok(send("GET", "/nodes?region=US&localOnly=true", null)).path("items").get(0).path("id")).isEqualTo(created.path("id"));
        assertThat(ok(send("GET", "/nodes?region=HK", null)).path("total").asInt()).isZero();

        var profile = profile("Region fixture", List.of(id), id);
        long draftVersion = profiles.load(owner, profile).version();
        String published = artifact(profile);
        String cipher = nodes.requireLocal(owner, id).connectionCipher();
        command.put("expectedVersion", created.path("version").asString()).put("regionOverride", "UNKNOWN");
        var manual = ok(send("PUT", path, command));
        assertThat(manual.path("affectedProfiles").size()).isZero();
        assertThat(ok(send("GET", "/nodes?region=UNKNOWN", null)).path("total").asInt()).isOne();
        assertThat(send("PUT", path, command.put("regionOverride", "AUTO")).statusCode()).isEqualTo(409);
        command.put("expectedVersion", manual.path("version").asString());
        var automatic = ok(send("PUT", path, command));
        assertThat(ok(send("GET", path, null)).path("regionOverride").isNull()).isTrue();
        assertThat(ok(send("GET", "/nodes?region=HK", null)).path("total").asInt()).isOne();
        assertThat(profiles.load(owner, profile).version()).isEqualTo(draftVersion);
        assertThat(profileRow(profile).path("hasUnpublishedNodeChanges").asBoolean()).isFalse();
        assertThat(artifact(profile)).isEqualTo(published);
        assertThat(nodes.requireLocal(owner, id).connectionCipher()).isEqualTo(cipher);

        command.put("expectedVersion", automatic.path("version").asString()).put("regionOverride", "ZZ");
        assertThat(send("PUT", path, command).statusCode()).isEqualTo(400);
        assertThat(ok(send("GET", path, null)).path("version")).isEqualTo(automatic.path("version"));
        var pinned = ok(send("PUT", path, command.put("regionOverride", "MO")));
        ok(send("PUT", path, input("Japan fixture", "fixture-only").put("expectedVersion", pinned.path("version").asString())));
        assertThat(ok(send("GET", "/nodes?region=MO", null)).path("total").asInt()).isOne();
    }

    @Test void batchImportsDefaultToIndividualRegionsOrUseTheExplicitRegionForAllNodes() throws Exception {
        var batch = json.createObjectNode().put("text", yaml("香港 fixture", "Japan fixture"));
        var preview = ok(send("POST", "/local-nodes/batch/preview", batch));
        batch.put("ownerRevision", preview.path("ownerRevision").asString());
        ok(send("POST", "/local-nodes/batch", batch));
        assertThat(ok(send("GET", "/nodes?region=HK", null)).path("total").asInt()).isOne();
        assertThat(ok(send("GET", "/nodes?region=JP", null)).path("total").asInt()).isOne();
        preview = ok(send("POST", "/local-nodes/batch/preview", batch));
        batch.put("ownerRevision", preview.path("ownerRevision").asString()).put("regionOverride", "FAKE");
        assertThat(send("POST", "/local-nodes/batch", batch).statusCode()).isEqualTo(400);
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isEqualTo(2);
        var result = ok(send("POST", "/local-nodes/batch", batch.put("regionOverride", "US")));
        assertThat(ok(send("GET", "/nodes?region=US", null)).path("total").asInt()).isEqualTo(2);
        for (var id : result.path("ids")) {
            assertThat(ok(send("GET", "/local-nodes/" + id.asString(), null)).path("regionOverride").asString()).isEqualTo("US");
        }
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

    @Test void batchPreviewAndAtomicImportSupportCopiedFlowObjectsAndPreservePublishedProfiles() throws Exception {
        UUID upstream = source("Fixture upstream", yaml("Upstream fixture"));
        UUID profile = profile("Fixture published", List.of(node(upstream, "Upstream fixture").id()), null);
        String publishedBefore = artifact(profile);
        var before = snapshots.read(owner);
        String text = yaml("Batch one", "Batch two").replace("proxies:\n", "").replace("- {", "{");
        var previewResponse = send("POST", "/local-nodes/batch/preview", json.createObjectNode().put("text", text));
        assertThat(previewResponse.headers().firstValue("cache-control")).hasValue("no-store");
        var preview = ok(previewResponse);
        assertThat(preview.path("nodes").size()).isEqualTo(2);
        assertThat(snapshots.read(owner)).isEqualTo(before);
        var command = json.createObjectNode().put("text", text).put("ownerRevision", preview.path("ownerRevision").asString());
        var result = ok(send("POST", "/local-nodes/batch", command));
        assertThat(result.path("ids").size()).isEqualTo(2);
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isEqualTo(2);
        assertThat(artifact(profile)).isEqualTo(publishedBefore);
        assertThat(snapshots.read(owner).profiles()).isEqualTo(before.profiles());
        assertThat(send("POST", "/local-nodes/batch", command).statusCode()).isEqualTo(409);
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isEqualTo(2);
    }

    @Test void batchInvalidNodeAndOutdatedPreviewNeverPartiallyImport() throws Exception {
        String invalid = yaml("Valid", "Invalid").replace("name: 'Invalid', type: ss", "name: 'Invalid', type: unsupported");
        long revision = snapshots.read(owner).dataRevision();
        assertThat(send("POST", "/local-nodes/batch/preview", json.createObjectNode().put("text", invalid)).statusCode()).isEqualTo(422);
        assertThat(send("POST", "/local-nodes/batch", json.createObjectNode().put("text", invalid).put("ownerRevision", revision)).statusCode()).isEqualTo(422);
        assertThat(snapshots.read(owner).nodes().nodes()).isEmpty();
        assertThat(snapshots.read(owner).dataRevision()).isEqualTo(revision);
        var preview = ok(send("POST", "/local-nodes/batch/preview", json.createObjectNode().put("text", yaml("One", "Two"))));
        ok(send("POST", "/local-nodes", input("Added after preview", "fixture-only")));
        assertThat(send("POST", "/local-nodes/batch", json.createObjectNode().put("text", yaml("One", "Two")).put("ownerRevision", preview.path("ownerRevision").asString())).statusCode()).isEqualTo(409);
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isEqualTo(1);
    }

    @Test void batchResolvesInternalChainsAndRejectsCyclesAndKernelFailures() throws Exception {
        String text = yaml("Gateway", "Leaf").replace("name: 'Leaf',", "name: 'Leaf', dialer-proxy: Gateway,");
        var preview = ok(send("POST", "/local-nodes/batch/preview", json.createObjectNode().put("text", text)));
        ok(send("POST", "/local-nodes/batch", json.createObjectNode().put("text", text).put("ownerRevision", preview.path("ownerRevision").asString())));
        var catalog = nodes.catalog(owner);
        var gateway = catalog.nodes().values().stream().filter(node -> node.originalName().equals("Gateway")).findFirst().orElseThrow();
        var leaf = catalog.nodes().values().stream().filter(node -> node.originalName().equals("Leaf")).findFirst().orElseThrow();
        assertThat(leaf.dialerRef()).isEqualTo(TargetRef.node(gateway.id()));
        String cycle = text.replace("name: 'Gateway',", "name: 'Gateway', dialer-proxy: Leaf,");
        var before = snapshots.read(owner);
        assertThat(send("POST", "/local-nodes/batch/preview", json.createObjectNode().put("text", cycle)).statusCode()).isEqualTo(422);
        String invalidCore = "proxies:\n- {name: Core-invalid, type: hysteria2, server: fixture.invalid, ports: 443-444, hop-interval: invalid-interval, password: fixture-only}\n";
        assertThat(send("POST", "/local-nodes/batch", json.createObjectNode().put("text", invalidCore).put("ownerRevision", before.dataRevision())).statusCode()).isEqualTo(422);
        assertThat(snapshots.read(owner)).isEqualTo(before);
        org.mockito.Mockito.verify(fetcher, org.mockito.Mockito.never()).fetch(org.mockito.ArgumentMatchers.any());
    }

    @Test void batchTransactionRollsBackEarlierInsertAndRevisionOnDatabaseFailure() {
        var before = snapshots.read(owner);
        var connection = (ObjectNode) input("Rollback fixture", "fixture-only").path("connection");
        var repeated = new NodeSpec(UUID.randomUUID(), null, "Rollback fixture", "ss", connection, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> localCommitter.saveBatch(before, List.of(repeated, repeated), null, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(snapshots.read(owner)).isEqualTo(before);
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

    @Test void batchRemovalUpdatesEachDraftOnceAndKeepsPublishedLocalSnapshots() throws Exception {
        UUID a = UUID.fromString(ok(send("POST", "/local-nodes", input("Bulk A", "fixture-a"))).path("id").asString());
        UUID b = UUID.fromString(ok(send("POST", "/local-nodes", input("Bulk B", "fixture-b"))).path("id").asString());
        UUID upstream = source("Remote fixture", yaml("Remote fixture"));
        UUID remote = node(upstream, "Remote fixture").id();
        UUID profile = profile("Bulk removal fixture", List.of(a, b, remote), a);
        var before = snapshots.read(owner); String original = artifact(profile);
        var response = send("POST", "/local-nodes/batch/removal", batchSelection(a, b));
        var preview = ok(response);
        assertThat(response.body()).doesNotContain("fixture-a", "fixture-b", "connection", "password");
        assertThat(preview.path("nodes").size()).isEqualTo(2);
        assertThat(preview.path("affectedProfiles").get(0).asString()).isEqualTo(profile.toString());
        assertThat(preview.path("publishedProfiles").get(0).asString()).isEqualTo(profile.toString());
        assertThat(preview.path("dependentNodes").isEmpty()).isTrue();
        assertThat(snapshots.read(owner)).isEqualTo(before);
        var deleted = ok(send("DELETE", "/local-nodes/batch", batchRemovalCommand(preview)));
        assertThat(deleted.path("ids").size()).isEqualTo(2);
        var draft = snapshots.read(owner).profiles().get(profile).draft();
        assertThat(draft.version()).isEqualTo(before.profiles().get(profile).draft().version() + 1);
        assertThat(draft.document().groups().getFirst().members()).containsExactly(TargetRef.node(remote));
        assertThat(draft.document().rules().getFirst().target()).isEqualTo(TargetRef.reject());
        assertThat(artifact(profile)).isEqualTo(original);
        respond(upstream, yaml("Remote fixture").replace("port: 443", "port: 444")); run(upstream);
        respond(upstream, yaml("Remote fixture").replace("port: 443", "port: 445")); run(upstream);
        assertThat(artifact(profile)).contains("Bulk A", "Bulk B");
        assertThat(ok(send("GET", "/nodes?localOnly=true", null)).path("total").asInt()).isZero();
    }

    @Test void batchRemovalAllowsSelectedChainsButBlocksUnselectedDependents() throws Exception {
        UUID gateway = UUID.fromString(ok(send("POST", "/local-nodes", input("Gateway", "fixture-only"))).path("id").asString());
        var leafInput = input("Leaf", "fixture-only"); leafInput.set("dialerRef", json.valueToTree(TargetRef.node(gateway)));
        UUID leaf = UUID.fromString(ok(send("POST", "/local-nodes", leafInput)).path("id").asString());
        var outerInput = input("Unselected", "fixture-only"); outerInput.set("dialerRef", json.valueToTree(TargetRef.node(leaf)));
        UUID outer = UUID.fromString(ok(send("POST", "/local-nodes", outerInput)).path("id").asString());
        var preview = ok(send("POST", "/local-nodes/batch/removal", batchSelection(gateway, leaf)));
        assertThat(preview.path("dependentNodes").size()).isEqualTo(1);
        assertThat(preview.path("dependentNodes").get(0).path("id").asString()).isEqualTo(outer.toString());
        var before = snapshots.read(owner);
        assertThat(send("DELETE", "/local-nodes/batch", batchRemovalCommand(preview)).statusCode()).isEqualTo(409);
        assertThat(snapshots.read(owner)).isEqualTo(before);
        var all = ok(send("POST", "/local-nodes/batch/removal", batchSelection(gateway, leaf, outer)));
        assertThat(all.path("dependentNodes").isEmpty()).isTrue();
        ok(send("DELETE", "/local-nodes/batch", batchRemovalCommand(all)));
        assertThat(nodes.catalog(owner).nodes()).isEmpty();
    }

    @Test void batchRemovalRejectsStaleVersionsAndAnyForeignOrUpstreamNode() throws Exception {
        UUID a = UUID.fromString(ok(send("POST", "/local-nodes", input("Owned A", "fixture-only"))).path("id").asString());
        UUID b = UUID.fromString(ok(send("POST", "/local-nodes", input("Owned B", "fixture-only"))).path("id").asString());
        var stale = ok(send("POST", "/local-nodes/batch/removal", batchSelection(a, b)));
        ok(send("PUT", "/local-nodes/" + a, input("Owned edited", "fixture-edited").put("expectedVersion", "0")));
        var before = snapshots.read(owner);
        assertThat(send("DELETE", "/local-nodes/batch", batchRemovalCommand(stale)).statusCode()).isEqualTo(409);
        var fresh = ok(send("POST", "/local-nodes/batch/removal", batchSelection(a, b)));
        var wrongVersion = batchRemovalCommand(fresh); ((ObjectNode) wrongVersion.path("nodes").get(0)).put("expectedVersion", "999");
        assertThat(send("DELETE", "/local-nodes/batch", wrongVersion).statusCode()).isEqualTo(409);
        assertThat(send("POST", "/local-nodes/batch/removal", batchSelection(a, a)).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/local-nodes/batch/removal", batchSelection()).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/local-nodes/batch/removal", batchSelection(a, UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(snapshots.read(owner)).isEqualTo(before);
        UUID other = UUID.randomUUID(); jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", other.toString(), "bulk-other-" + other, "fixture-hash");
        var foreign = localNodes.save(other, null, new LocalNodeCommand(null, "Foreign", "ss", (ObjectNode) input("Foreign", "fixture-only").path("connection"), null));
        assertThat(send("POST", "/local-nodes/batch/removal", batchSelection(a, foreign.id())).statusCode()).isEqualTo(404);
        UUID upstream = source("Upstream fixture", yaml("Upstream fixture"));
        assertThat(send("POST", "/local-nodes/batch/removal", batchSelection(a, node(upstream, "Upstream fixture").id())).statusCode()).isEqualTo(404);
        assertThat(nodes.requireLocal(owner, a).snapshotVersion()).isEqualTo(1);
        assertThat(nodes.requireLocal(owner, b).snapshotVersion()).isZero();
    }

    private ObjectNode batchSelection(UUID... ids) {
        var value = json.createObjectNode(); var selected = value.putArray("ids");
        for (UUID id : ids) { selected.add(id.toString()); } return value;
    }

    @Test void batchRemovalRollsBackEarlierDeletesWhenALaterRowConflicts() throws Exception {
        UUID a = UUID.fromString(ok(send("POST", "/local-nodes", input("Rollback A", "fixture-only"))).path("id").asString());
        UUID b = UUID.fromString(ok(send("POST", "/local-nodes", input("Rollback B", "fixture-only"))).path("id").asString());
        UUID profile = profile("Rollback fixture", List.of(a, b), a);
        var before = snapshots.read(owner); String artifact = artifact(profile);
        var ordered = List.of(a, b).stream().sorted().toList();
        var versions = java.util.Map.of(ordered.getFirst(), 0L, ordered.getLast(), 999L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> localCommitter.deleteBatch(before, versions,
                java.util.Map.of(profile, before.profiles().get(profile).draft().document())))
                .isInstanceOf(dev.clashsub.common.ApiException.class);
        assertThat(snapshots.read(owner)).isEqualTo(before);
        assertThat(nodes.requireLocal(owner, a).snapshotVersion()).isZero();
        assertThat(nodes.requireLocal(owner, b).snapshotVersion()).isZero();
        assertThat(artifact(profile)).isEqualTo(artifact);
    }

    @Test void batchRemovalRejectsMalformedSelectionsWithoutChangingOwnedData() throws Exception {
        UUID a = UUID.fromString(ok(send("POST", "/local-nodes", input("Owned", "fixture-only"))).path("id").asString());
        UUID other = UUID.randomUUID(); jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", other.toString(), "bulk-other-" + other, "fixture-hash");
        var foreign = localNodes.save(other, null, new LocalNodeCommand(null, "Foreign", "ss", (ObjectNode) input("Foreign", "fixture-only").path("connection"), null));
        UUID upstream = source("Upstream fixture", yaml("Upstream fixture"));
        UUID remote = node(upstream, "Upstream fixture").id();
        var before = snapshots.read(owner);
        var selected = batchSelection(a); selected.path("ids").asArray().addNull();
        assertThat(send("POST", "/local-nodes/batch/removal", selected).statusCode()).isEqualTo(400);
        var oversized = batchSelection();
        for (int i = 0; i < 501; i++) { oversized.path("ids").asArray().add(UUID.randomUUID().toString()); }
        assertThat(send("POST", "/local-nodes/batch/removal", oversized).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/local-nodes/batch/removal", json.createObjectNode()).statusCode()).isEqualTo(400);
        var preview = ok(send("POST", "/local-nodes/batch/removal", batchSelection(a)));
        for (UUID invalidId : List.of(a, foreign.id(), remote, UUID.randomUUID())) {
            var command = batchRemovalCommand(preview);
            command.path("nodes").asArray().addObject().put("id", invalidId.toString()).put("expectedVersion", "0");
            assertThat(send("DELETE", "/local-nodes/batch", command).statusCode()).isEqualTo(invalidId.equals(a) ? 400 : 404);
        }
        var noVersion = batchRemovalCommand(preview); ((ObjectNode) noVersion.path("nodes").get(0)).remove("expectedVersion");
        assertThat(send("DELETE", "/local-nodes/batch", noVersion).statusCode()).isEqualTo(400);
        assertThat(send("DELETE", "/local-nodes/batch", json.createObjectNode()).statusCode()).isEqualTo(400);
        assertThat(snapshots.read(owner)).isEqualTo(before);
    }
    private ObjectNode batchRemovalCommand(JsonNode preview) {
        var value = json.createObjectNode().put("ownerRevision", preview.path("ownerRevision").asString());
        var selected = value.putArray("nodes");
        for (var node : preview.path("nodes")) { selected.addObject().put("id", node.path("id").asString()).put("expectedVersion", node.path("version").asString()); }
        return value;
    }

    @Test void subscriptionUsesThePublishedManualGroupUntilTheNextManualRelease() throws Exception {
        var folder = ok(send("POST", "/local-node-groups", json.createObjectNode().put("name", "朋友,分享")));
        String folderId = folder.path("id").asString();
        var gateway = ok(send("POST", "/local-nodes", input("香港 01", "fixture-gateway").put("localGroupId", folderId)));
        UUID gatewayId = UUID.fromString(gateway.path("id").asString());
        var child = input("Leaf", "fixture-leaf");
        child.set("dialerRef", json.valueToTree(TargetRef.node(gatewayId)));
        UUID leaf = UUID.fromString(ok(send("POST", "/local-nodes", child)).path("id").asString());
        UUID remote = source("上游", yaml("Remote"));
        UUID profile = profile("Mixed", List.of(gatewayId, leaf, node(remote, "Remote").id()), gatewayId);
        String yaml = artifact(profile);
        var output = new SafeYaml().load(yaml).value();
        String label = "香港 01 · 朋友，分享";
        assertThat(output.path("proxy-groups").get(0).path("proxies").get(0).asString()).isEqualTo(label);
        assertThat(output.path("proxies")).anySatisfy(proxy -> assertThat(proxy.path("name").asString()).isEqualTo(label));
        assertThat(output.path("proxies")).anySatisfy(proxy -> {
            assertThat(proxy.path("name").asString()).isEqualTo("Leaf · 本地节点");
            assertThat(proxy.path("dialer-proxy").asString()).isEqualTo(label);
        });
        assertThat(output.path("rules").get(0).asString()).isEqualTo("IP-CIDR,192.0.2.0/24," + label + ",no-resolve");

        long version = profiles.load(owner, profile).version();
        String rulePath = "/profiles/" + profile + "/rule-text";
        var read = ok(send("POST", rulePath, json.createObjectNode().put("expectedVersion", version)));
        assertThat(read.path("text").asString()).contains(label);
        ok(send("PATCH", "/local-node-groups/" + folderId, json.createObjectNode().put("name", "TAG").put("expectedVersion", 0)));
        assertThat(send("POST", rulePath + "/preview", json.createObjectNode().put("expectedVersion", version)
                .put("ownerRevision", read.path("ownerRevision").asString()).put("text", read.path("text").asString())).statusCode()).isEqualTo(409);
        assertThat(publisher.preview(owner, profile, version, null).yaml()).contains("香港 01 · TAG");
        assertThat(profileRow(profile).path("hasUnpublishedNodeChanges").asBoolean()).isTrue();
        assertThat(artifact(profile)).isEqualTo(yaml);
        respond(remote, yaml("Remote").replace("fixture-first", "fixture-new"));
        assertThat(run(remote).status()).isEqualTo("SUCCESS");
        assertThat(artifact(profile)).contains(label, "fixture-new").doesNotContain("香港 01 · TAG");

        publisher.publish(owner, profile, version, null);
        assertThat(artifact(profile)).contains("香港 01 · TAG").doesNotContain(label);
        assertThat(profileRow(profile).path("hasUnpublishedNodeChanges").asBoolean()).isFalse();
        ok(send("DELETE", "/local-node-groups/" + folderId + "?expectedVersion=1", null));
        publisher.publish(owner, profile, profiles.load(owner, profile).version(), null);
        assertThat(artifact(profile)).contains("香港 01 · 本地节点").doesNotContain("香港 01 · TAG");
    }

    @Test void sourceFoldersOrganizeExistingNodesWithoutChangingProfilesOrPublishedYaml() throws Exception {
        UUID a = UUID.fromString(ok(send("POST", "/local-nodes", input("First", "fixture-only"))).path("id").asString());
        UUID b = UUID.fromString(ok(send("POST", "/local-nodes", input("Second", "fixture-only"))).path("id").asString());
        UUID profile = profile("Published fixture", List.of(a, b), a);
        var before = snapshots.read(owner);
        String published = artifact(profile);
        var timestamps = jdbc.queryForList("SELECT update_time FROM node WHERE owner_id=? ORDER BY id", owner.toString());
        var group = ok(send("POST", "/local-node-groups", json.createObjectNode().put("name", " 自建 ")));
        String id = group.path("id").asString();
        assertThat(group.path("name").asString()).isEqualTo("自建");
        assertThat(ok(send("GET", "/local-node-groups", null)).path("ungroupedCount").asInt()).isEqualTo(2);
        ok(send("PUT", "/local-node-groups/" + id + "/members", batchSelection(a, b)));
        assertThat(publisher.preview(owner, profile, profiles.load(owner, profile).version(), null).yaml())
                .contains("First · 自建", "Second · 自建");
        var page = ok(send("GET", "/nodes?localGroupId=" + id + "&size=1&page=2", null));
        assertThat(page.path("total").asInt()).isEqualTo(2);
        assertThat(page.path("items").size()).isOne();
        assertThat(page.path("items").get(0).path("localGroupName").asString()).isEqualTo("自建");
        assertThat(page.path("items").get(0).path("localGroupId").asString()).isEqualTo(id);
        assertThat(ok(send("GET", "/nodes?ungrouped=true", null)).path("total").asInt()).isZero();
        ok(send("PUT", "/local-node-groups/ungrouped/members", batchSelection(a)));
        var renamed = ok(send("PATCH", "/local-node-groups/" + id, json.createObjectNode()
                .put("name", "朋友分享").put("expectedVersion", group.path("version").asString())));
        assertThat(ok(send("GET", "/local-node-groups", null)).path("groups").get(0).path("nodeCount").asInt()).isOne();
        assertThat(ok(send("GET", "/nodes?name=Second", null)).path("items").get(0).path("localGroupName").asString()).isEqualTo("朋友分享");
        assertThat(send("DELETE", "/local-node-groups/" + id + "?expectedVersion=" + group.path("version").asString(), null).statusCode()).isEqualTo(409);
        ok(send("DELETE", "/local-node-groups/" + id + "?expectedVersion=" + renamed.path("version").asString(), null));
        assertThat(ok(send("GET", "/local-node-groups", null)).path("groups").size()).isZero();
        assertThat(ok(send("GET", "/nodes?ungrouped=true", null)).path("total").asInt()).isEqualTo(2);
        assertThat(nodes.requireLocal(owner, a).snapshotVersion()).isZero();
        assertThat(jdbc.queryForList("SELECT update_time FROM node WHERE owner_id=? ORDER BY id", owner.toString())).isEqualTo(timestamps);
        var after = snapshots.read(owner);
        assertThat(after.dataRevision()).isGreaterThan(before.dataRevision());
        assertThat(after.nodes()).isEqualTo(before.nodes());
        assertThat(after.profiles()).isEqualTo(before.profiles());
        assertThat(artifact(profile)).isEqualTo(published);
    }

    @Test void importedNodesKeepTheirChosenFolderAndADeletedDestinationDoesNotPartiallyImport() throws Exception {
        var group = ok(send("POST", "/local-node-groups", json.createObjectNode().put("name", "实验室")));
        String id = group.path("id").asString();
        var one = ok(send("POST", "/local-nodes", input("Single", "fixture-only").put("localGroupId", id)));
        ok(send("PUT", "/local-nodes/" + one.path("id").asString(), input("Renamed", "fixture-only").put("expectedVersion", "0")));
        var batch = json.createObjectNode().put("text", yaml("Gateway", "Leaf"));
        var preview = ok(send("POST", "/local-nodes/batch/preview", batch));
        batch.put("ownerRevision", preview.path("ownerRevision").asString()).put("localGroupId", id);
        ok(send("POST", "/local-nodes/batch", batch));
        assertThat(ok(send("GET", "/nodes?localGroupId=" + id, null)).path("total").asInt()).isEqualTo(3);
        preview = ok(send("POST", "/local-nodes/batch/preview", batch));
        batch.put("ownerRevision", preview.path("ownerRevision").asString());
        ok(send("DELETE", "/local-node-groups/" + id + "?expectedVersion=" + group.path("version").asString(), null));
        var before = snapshots.read(owner);
        assertThat(send("POST", "/local-nodes/batch", batch).statusCode()).isEqualTo(409);
        batch.put("ownerRevision", before.dataRevision());
        assertThat(send("POST", "/local-nodes/batch", batch).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/local-nodes", input("Other", "fixture-only").put("localGroupId", id)).statusCode()).isEqualTo(404);
        assertThat(snapshots.read(owner)).isEqualTo(before);
        assertThat(ok(send("GET", "/nodes?ungrouped=true", null)).path("total").asInt()).isEqualTo(3);
    }

    @Test void folderWritesAreOwnerScopedAndInvalidMembersNeverPartiallyMove() throws Exception {
        var group = ok(send("POST", "/local-node-groups", json.createObjectNode().put("name", "Work")));
        String id = group.path("id").asString(), members = "/local-node-groups/" + id + "/members";
        for (String invalid : List.of("work", " ", "未分组", "全部分组", "X".repeat(65))) {
            assertThat(send("POST", "/local-node-groups", json.createObjectNode().put("name", invalid)).statusCode()).isEqualTo(400);
        }
        var local = UUID.fromString(ok(send("POST", "/local-nodes", input("Local", "fixture-only"))).path("id").asString());
        var upstream = source("Remote", yaml("Remote"));
        var remote = node(upstream, "Remote").id();
        UUID other = UUID.randomUUID(), foreignGroup = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", other.toString(), "other-" + other, "fixture-hash");
        jdbc.update("INSERT INTO local_node_group(id,owner_id,name) VALUES(?,?,?)", foreignGroup.toString(), other.toString(), "Other");
        var foreignNode = localNodes.save(other, null, new LocalNodeCommand(null, "Foreign", "ss", (ObjectNode) input("Foreign", "fixture-only").path("connection"), null));
        for (UUID bad : List.of(remote, foreignNode.id(), UUID.randomUUID())) {
            assertThat(send("PUT", members, batchSelection(local, bad)).statusCode()).isEqualTo(404);
        }
        assertThat(send("PUT", members, batchSelection(local, local)).statusCode()).isEqualTo(400);
        assertThat(send("PUT", members, batchSelection()).statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/local-node-groups/" + foreignGroup + "/members", batchSelection(local)).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/local-node-groups/" + foreignGroup + "?expectedVersion=0", null).statusCode()).isEqualTo(404);
        assertThat(ok(send("GET", "/local-node-groups", null)).path("groups").size()).isOne();
        assertThat(ok(send("GET", "/nodes?localGroupId=" + id, null)).path("total").asInt()).isZero();
        assertThat(ok(send("GET", "/nodes?ungrouped=true", null)).path("total").asInt()).isOne();
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
