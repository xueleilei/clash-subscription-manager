package dev.clashsub.release;

import dev.clashsub.common.ApiException;
import dev.clashsub.compile.MihomoValidator;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileService;
import dev.clashsub.support.ApiTestClient;
import dev.clashsub.support.MySqlIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(OutputCaptureExtension.class)
class SubscriptionTokenIT extends MySqlIntegrationTest {
    @Autowired SubscriptionTokenService tokens;
    @Autowired ReleaseService releases;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MihomoValidator core;
    @LocalServerPort int port;
    private UUID admin() { return UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN)); }
    private ApiTestClient client() throws Exception { var client = new ApiTestClient(port); client.login(ADMIN, PASSWORD); return client; }

    @Test void tokenIsEncryptedRotationRevokesImmediatelyAndDownloadsUseSavedArtifacts(CapturedOutput output) throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Subscription"); var link = tokens.link(owner, draft.profileId());
        assertThat(link.token()).matches("[A-Za-z0-9_-]{43}"); assertThat(link.url()).startsWith("https://fixture.example.invalid/sub/");
        var raw = jdbc.queryForMap("SELECT token_hash,token_cipher FROM configuration_profile WHERE id=?", draft.profileId().toString());
        assertThat(raw.toString()).doesNotContain(link.token(), link.url());
        var anonymous = new ApiTestClient(port);
        assertThat(anonymous.get("/api/v1/profiles/" + draft.profileId() + "/subscription-link").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/sub/" + link.token()).statusCode()).isEqualTo(409);
        assertThat(anonymous.get("/sub/not-a-valid-token").statusCode()).isEqualTo(404);
        releases.publish(owner, draft.profileId(), 1, draft.document());
        String yaml = tokens.readPublished(link.token()).yaml(); clearInvocations(core);
        var downloaded = anonymous.get("/sub/" + link.token());
        assertThat(downloaded.statusCode()).isEqualTo(200); assertThat(downloaded.body()).isEqualTo(yaml);
        assertThat(downloaded.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        verifyNoInteractions(core);
        var rotated = tokens.rotate(owner, draft.profileId()); assertThat(rotated.token()).isNotEqualTo(link.token());
        assertThat(anonymous.get("/sub/" + link.token()).statusCode()).isEqualTo(404);
        assertThat(anonymous.get("/sub/" + rotated.token()).body()).isEqualTo(yaml);
        var client = client(); var revealed = client.get("/api/v1/profiles/" + draft.profileId() + "/subscription-link");
        assertThat(revealed.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(output.getAll()).doesNotContain(link.token(), rotated.token(), link.url());
        var impact = profiles.deletionImpact(owner, draft.profileId()); profiles.delete(owner, draft.profileId(), Long.parseLong(impact.profileVersion()));
        assertThat(anonymous.get("/sub/" + rotated.token()).statusCode()).isEqualTo(404);
    }

    @Test void eachTokenReturnsOnlyItsOwnProfileAndEveryManagementEndpointEnforcesOwner() throws Exception {
        var owner = admin(); var first = profiles.create(owner, "First"); var second = profiles.create(owner, "Second");
        var firstDoc = new ProfileDocument(1, "mode: rule\nmixed-port: 7891\n", List.of(), first.document().rules(), List.of(), List.of());
        var secondDoc = new ProfileDocument(1, "mode: rule\nmixed-port: 7892\n", List.of(), second.document().rules(), List.of(), List.of());
        releases.publish(owner, first.profileId(), 1, firstDoc); releases.publish(owner, second.profileId(), 1, secondDoc);
        assertThat(tokens.readPublished(tokens.link(owner, first.profileId()).token()).yaml()).contains("7891").doesNotContain("7892");
        var foreign = UUID.randomUUID(); jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", foreign.toString(), "fixture-" + foreign, "fixture-hash");
        var foreignDraft = profiles.create(foreign, "Foreign"); var client = client(); String base = "/api/v1/profiles/" + foreignDraft.profileId();
        for (String endpoint : List.of("/subscription-link", "/releases")) { assertThat(client.get(base + endpoint).statusCode()).isEqualTo(404); }
        for (String endpoint : List.of("/validate", "/publish", "/token/rotate")) {
            assertThat(client.send("POST", base + endpoint, Map.of("expectedVersion", "1", "document", foreignDraft.document())).statusCode()).isEqualTo(404);
        }
        assertThatThrownBy(() -> tokens.rotate(owner, foreignDraft.profileId())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
    }

    @Test void validationPreviewIsReadOnlyAndPublishingAcceptsStringVersions() throws Exception {
        var owner = admin(); var draft = profiles.create(owner, "Preview"); var client = client();
        String base = "/api/v1/profiles/" + draft.profileId();
        var preview = client.send("POST", base + "/validate", Map.of("expectedVersion", "1", "document", draft.document()));
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(client.data(preview).path("yaml").asString()).contains("MATCH,REJECT");
        assertThat(client.data(preview).path("expectedVersion").asString()).isEqualTo("1");
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft);
        var published = client.send("POST", base + "/publish", Map.of("expectedVersion", "1", "document", draft.document()));
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(client.data(published).path("version").isString()).isTrue();
        assertThat(client.data(client.get(base + "/releases")).size()).isEqualTo(1);
    }
}
