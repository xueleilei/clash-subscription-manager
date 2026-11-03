package dev.clashsub.rule;

import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileService;
import dev.clashsub.profile.TargetRef;
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

class RuleTextIT extends MySqlIntegrationTest {
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
    private RuleDefinition rule(String value, TargetRef target) {
        return new RuleDefinition(UUID.randomUUID(), new RuleExpression("DOMAIN", value, null), target, List.of(), "", List.of());
    }

    @Test void fullTextUsesUnsavedRulesAndSourceQualifiedNodesWithoutChangingTheDraft() throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Text edit");
        var first = sources.save(owner, new SourceCommand(null, null, "机场甲", false, null, null, "https://fixture.invalid/one", Map.of(), "", ""));
        var second = sources.save(owner, new SourceCommand(null, null, "机场乙", false, null, null, "https://fixture.invalid/two", Map.of(), "", ""));
        var one = UUID.randomUUID(); var two = UUID.randomUUID(); var group = UUID.randomUUID();
        var connection = json.createObjectNode().put("server", "fixture.invalid").put("port", 443).put("cipher", "aes-128-gcm").put("password", "fixture-secret");
        transaction.executeWithoutResult(tx -> {
            nodes.replaceSource(owner, first.id(), 1, List.of(new NodeSpec(one, first.id(), "香港", "ss", connection, null)));
            nodes.replaceSource(owner, second.id(), 1, List.of(new NodeSpec(two, second.id(), "香港", "ss", connection, null)));
        });
        var nodeRule = rule("old.invalid", TargetRef.node(two));
        var edited = new ProfileDocument(1, draft.document().baseYaml(),
                List.of(new GroupDefinition(group, "新组", "select", List.of(TargetRef.node(one)), json.createObjectNode())),
                List.of(nodeRule, rule("group.invalid", TargetRef.group(group)), draft.document().rules().getLast()), List.of(), List.of());
        var client = client(); long revision = revisions.current(owner);
        String root = "/api/v1/profiles/" + draft.profileId();
        var exported = client.send("POST", root + "/rule-text", Map.of("expectedVersion", "1", "document", edited));
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String text = client.data(exported).path("text").asString();
        assertThat(text).contains("DOMAIN,old.invalid,香港 · 机场乙", "DOMAIN,group.invalid,新组", "MATCH,REJECT");
        assertThat(text).doesNotContain("fixture-secret", "https://fixture.invalid");
        var preview = client.send("POST", root + "/rule-text/preview",
                Map.of("expectedVersion", "1", "document", edited, "text", text.replace("old.invalid", "changed.invalid"), "ownerRevision", revision));
        assertThat(preview.statusCode()).isEqualTo(200); var data = client.data(preview);
        assertThat(data.path("applicable").asBoolean()).isTrue(); assertThat(data.path("rules").size()).isEqualTo(3);
        assertThat(data.path("rules").path(0).path("target").path("id").asString()).isEqualTo(two.toString());
        assertThat(data.path("rules").path(1).path("id").asString()).isEqualTo(edited.rules().get(1).id().toString());
        assertThat(data.path("rules").path(2).path("id").asString()).isEqualTo(edited.rules().get(2).id().toString());
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft); assertThat(revisions.current(owner)).isEqualTo(revision);
    }

    @Test void fullReplacementRemovesOldRulesAndInvalidInputNeverPartiallyApplies() throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Replace all"); var client = client();
        var edited = new ProfileDocument(1, draft.document().baseYaml(), List.of(),
                List.of(rule("removed.invalid", TargetRef.direct()), draft.document().rules().getLast()), List.of(), List.of());
        String path = "/api/v1/profiles/" + draft.profileId() + "/rule-text/preview";
        var result = client.send("POST", path, Map.of("expectedVersion", "1", "document", edited,
                "text", "DOMAIN,replacement.invalid,DIRECT\nMATCH,REJECT", "ownerRevision", revisions.current(owner)));
        assertThat(result.statusCode()).isEqualTo(200); var data = client.data(result);
        assertThat(data.path("applicable").asBoolean()).isTrue(); assertThat(data.path("rules").size()).isEqualTo(2);
        assertThat(data.path("rules").path(0).path("expression").path("value").asString()).isEqualTo("replacement.invalid");
        for (String text : List.of("DOMAIN,ok.invalid,DIRECT\nBROKEN\nMATCH,REJECT", "DOMAIN,ok.invalid,DIRECT", "MATCH,REJECT\nMATCH,DIRECT", "")) {
            var invalid = client.data(client.send("POST", path, Map.of("expectedVersion", "1", "document", edited, "text", text, "ownerRevision", revisions.current(owner))));
            assertThat(invalid.path("applicable").asBoolean()).isFalse();
            assertThat(invalid.path("rules")).isEqualTo(json.valueToTree(edited.rules()));
        }
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft);
    }

    @Test void textEndpointsEnforceOwnershipAndVersion() throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Version check"); var client = client();
        var foreign = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", foreign.toString(), "rule-text-" + foreign, "fixture-hash");
        var other = profiles.create(foreign, "Foreign");
        profiles.save(owner, draft.profileId(), 1, draft.document());
        for (String suffix : List.of("/rule-text", "/rule-text/preview")) {
            var body = Map.of("expectedVersion", "1", "text", "MATCH,REJECT");
            assertThat(client.send("POST", "/api/v1/profiles/" + other.profileId() + suffix, body).statusCode()).isEqualTo(404);
            assertThat(client.send("POST", "/api/v1/profiles/" + draft.profileId() + suffix, body).statusCode()).isEqualTo(409);
        }
    }

    @Test void sourceRenameInvalidatesOldTextEvenWhenDraftVersionDidNotChange() throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Source revision"); var client = client();
        var source = sources.save(owner, new SourceCommand(null, null, "Old label", false, null, null, "https://fixture.invalid/revision", Map.of(), "", ""));
        String path = "/api/v1/profiles/" + draft.profileId();
        var text = client.data(client.send("POST", path + "/rule-text", Map.of("expectedVersion", "1")));
        sources.save(owner, new SourceCommand(source.id(), 1L, "New label", null, null, null, null, null, null, null));
        var response = client.send("POST", path + "/rule-text/preview", Map.of("expectedVersion", "1",
                "ownerRevision", text.path("ownerRevision").asString(), "text", text.path("text").asString()));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft);
    }
}
