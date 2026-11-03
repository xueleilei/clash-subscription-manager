package dev.clashsub.profile;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import dev.clashsub.support.MySqlIntegrationTest;
import dev.clashsub.support.ApiTestClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileDraftIT extends MySqlIntegrationTest {
    @Autowired ProfileService profiles;
    @Autowired ProfileRepository repository;
    @Autowired OwnerRevisionGuard revisions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @LocalServerPort int port;
    UUID owner;
    @BeforeEach void owner() {
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", owner.toString(), "fixture-" + owner, "fixture-hash");
    }

    @Test void createsIndependentProfilesWithRejectDefaultAndEncryptedDrafts() {
        var first = profiles.create(owner, "Home"); var other = profiles.create(owner, "Work");
        assertThat(first.document().baseYaml()).isEqualTo("mode: rule\n");
        assertThat(first.document().rules()).hasSize(1);
        assertThat(first.document().rules().getFirst().target()).isEqualTo(TargetRef.reject());
        assertThat(first.document().rules().getFirst().expression().type()).isEqualTo("MATCH");
        var id = UUID.randomUUID();
        var changed = profiles.save(owner, first.profileId(), first.version(), document(id, "Home group", "secret: fixture-secret\n"));
        assertThat(changed.version()).isEqualTo(first.version() + 1);
        assertThat(profiles.load(owner, other.profileId()).document()).isEqualTo(other.document());
        assertThat(revisions.current(owner)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT document_cipher FROM profile_draft WHERE profile_id=?", String.class, first.profileId().toString()))
                .doesNotContain("fixture-secret", "Home group");
    }

    @Test void staleSavesAndForeignReferencesCannotChangeDraft() {
        var first = profiles.create(owner, "First"); var second = profiles.create(owner, "Second");
        var groupId = UUID.randomUUID();
        var saved = profiles.save(owner, first.profileId(), 1, document(groupId, "Group", "mode: rule\n"));
        assertThatThrownBy(() -> profiles.save(owner, first.profileId(), 1, first.document()))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        var cross = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(rule(TargetRef.group(groupId))), List.of(), List.of());
        assertThatThrownBy(() -> profiles.save(owner, second.profileId(), 1, cross))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        assertThatThrownBy(() -> profiles.load(UUID.randomUUID(), first.profileId()))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        assertThat(profiles.load(owner, first.profileId())).isEqualTo(saved);
    }

    @Test void renamePreservesIdAndReferencedGroupMustBeReplacedBeforeDeletion() {
        var profile = profiles.create(owner, "Names"); var id = UUID.randomUUID();
        var saved = profiles.save(owner, profile.profileId(), 1, document(id, "Before", "mode: rule\n"));
        var renamed = profiles.save(owner, profile.profileId(), saved.version(), document(id, "After", "mode: rule\n"));
        assertThat(renamed.document().rules().getFirst().target()).isEqualTo(TargetRef.group(id));
        var dangling = new ProfileDocument(1, "mode: rule\n", List.of(), renamed.document().rules(), List.of(), List.of());
        assertThatThrownBy(() -> profiles.save(owner, profile.profileId(), renamed.version(), dangling)).isInstanceOf(ApiException.class);
        var cleared = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(rule(TargetRef.reject())), List.of(), List.of());
        assertThat(profiles.save(owner, profile.profileId(), renamed.version(), cleared).document().groups()).isEmpty();
    }

    @Test void foreignProviderAndNodeIdsAndDuplicateMembersAreNotPersisted() {
        var profile = profiles.create(owner, "Protected references");
        var providerRule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("RULE-SET", "", UUID.randomUUID()), TargetRef.reject(), List.of(), "", List.of());
        var provider = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(providerRule), List.of(), List.of());
        assertThatThrownBy(() -> profiles.save(owner, profile.profileId(), 1, provider))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        var node = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(rule(TargetRef.node(UUID.randomUUID()))), List.of(), List.of());
        assertThatThrownBy(() -> profiles.save(owner, profile.profileId(), 1, node))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        var duplicate = new GroupDefinition(UUID.randomUUID(), "Duplicate", "select", List.of(TargetRef.reject(), TargetRef.reject()), json.createObjectNode());
        var doc = new ProfileDocument(1, "mode: rule\n", List.of(duplicate), profile.document().rules(), List.of(), List.of());
        assertThatThrownBy(() -> profiles.save(owner, profile.profileId(), 1, doc))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
        assertThat(profiles.load(owner, profile.profileId())).isEqualTo(profile);
    }

    @Test void unfinishedYamlCanBeSavedWithoutChangingPublishedPointer() {
        var profile = profiles.create(owner, "Incomplete");
        var released = UUID.randomUUID();
        jdbc.update("UPDATE configuration_profile SET current_release_id=? WHERE id=?", released.toString(), profile.profileId().toString());
        var changed = profiles.save(owner, profile.profileId(), 1, new ProfileDocument(1, "dns: [", List.of(), profile.document().rules(), List.of(), List.of()));
        assertThat(changed.document().baseYaml()).isEqualTo("dns: [");
        assertThat(repository.require(owner, profile.profileId()).currentReleaseId()).isEqualTo(released.toString());
        assertThat(profiles.diagnostics(owner, profile.profileId()).valid()).isFalse();
    }

    @Test void deletionNeedsImpactVersionAndApiVersionsAreStrings() throws Exception {
        var admin = UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN));
        var profile = profiles.create(admin, "HTTP profile");
        var client = new ApiTestClient(port); client.login(ADMIN, PASSWORD);
        var loaded = client.data(client.get("/api/v1/profiles/" + profile.profileId() + "/draft"));
        assertThat(loaded.path("version").isString()).isTrue();
        var impact = profiles.deletionImpact(admin, profile.profileId());
        assertThat(impact.profileId()).isEqualTo(profile.profileId());
        profiles.rename(admin, profile.profileId(), Long.parseLong(impact.profileVersion()), "Renamed");
        assertThatThrownBy(() -> profiles.delete(admin, profile.profileId(), Long.parseLong(impact.profileVersion())))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        profiles.delete(admin, profile.profileId(), Long.parseLong(profiles.deletionImpact(admin, profile.profileId()).profileVersion()));
        assertThat(client.get("/api/v1/profiles/" + profile.profileId() + "/draft").statusCode()).isEqualTo(404);
    }

    private ProfileDocument document(UUID group, String name, String base) {
        return new ProfileDocument(1, base, List.of(new GroupDefinition(group, name, "select", List.of(), json.createObjectNode())),
                List.of(rule(TargetRef.group(group))), List.of(), List.of());
    }
    private RuleDefinition rule(TargetRef target) {
        return new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), target, List.of(), "", List.of());
    }
}
