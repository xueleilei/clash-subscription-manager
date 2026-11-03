package dev.clashsub.rule;

import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileService;
import dev.clashsub.source.SourceCommand;
import dev.clashsub.source.SourceService;
import dev.clashsub.support.ApiTestClient;
import dev.clashsub.support.MySqlIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RuleImportIT extends MySqlIntegrationTest {
    @Autowired ProfileService profiles;
    @Autowired SourceService sources;
    @Autowired NodeRepository nodes;
    @Autowired OwnerRevisionGuard revisions;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @LocalServerPort int port;
    private UUID admin() { return UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN)); }
    private ApiTestClient client() throws Exception { var client = new ApiTestClient(port); client.login(ADMIN, PASSWORD); return client; }

    @Test void previewUsesCurrentUnsavedDocumentAndOwnedNodesWithoutSaving() throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Import preview"); var group = UUID.randomUUID(); var node = UUID.randomUUID();
        var source = sources.save(owner, new SourceCommand(null, null, "Fixture", true, null, null, "https://example.invalid/sub", Map.of(), "", ""));
        transaction.executeWithoutResult(tx -> nodes.replaceSource(owner, source.id(), 1, List.of(new NodeSpec(node, source.id(), "Fixture node", "ss",
                json.createObjectNode().put("server", "example.invalid").put("port", 443).put("cipher", "aes-128-gcm").put("password", "fixture-secret"), null))));
        var edited = new ProfileDocument(1, draft.document().baseYaml(), List.of(new GroupDefinition(group, "New group", "select", List.of(), json.createObjectNode())),
                draft.document().rules(), List.of(), List.of());
        var revision = revisions.current(owner); var client = client();
        var response = client.send("POST", "/api/v1/profiles/" + draft.profileId() + "/rule-import/preview",
                Map.of("expectedVersion", "1", "text", "DOMAIN,a.invalid,New group\nPROCESS-NAME,fixture,Fixture node", "matchPolicy", "KEEP", "document", edited));
        assertThat(response.statusCode()).isEqualTo(200);
        var data = client.data(response); assertThat(data.path("applicable").asBoolean()).isTrue();
        assertThat(data.path("rules").path(0).path("target").path("id").asString()).isEqualTo(group.toString());
        assertThat(data.path("rules").path(1).path("target").path("id").asString()).isEqualTo(node.toString());
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft); assertThat(revisions.current(owner)).isEqualTo(revision);
    }

    @Test void previewChecksProfileOwnershipAndDraftVersion() throws Exception {
        var foreign = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", foreign.toString(), "fixture-" + foreign, "fixture-hash");
        var foreignDraft = profiles.create(foreign, "Foreign"); var draft = profiles.create(admin(), "Version");
        var body = Map.of("expectedVersion", "1", "text", "DOMAIN,a.invalid,DIRECT", "matchPolicy", "KEEP");
        var client = client();
        assertThat(client.send("POST", "/api/v1/profiles/" + foreignDraft.profileId() + "/rule-import/preview", body).statusCode()).isEqualTo(404);
        profiles.save(admin(), draft.profileId(), 1, draft.document());
        assertThat(client.send("POST", "/api/v1/profiles/" + draft.profileId() + "/rule-import/preview", body).statusCode()).isEqualTo(409);
    }
}
