package dev.clashsub.release;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiValidationException;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.compile.MihomoValidator;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.node.NodeSpec;
import dev.clashsub.profile.GroupDefinition;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileRepository;
import dev.clashsub.profile.ProfileMapper;
import dev.clashsub.profile.ProfileService;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import dev.clashsub.source.SourceCommand;
import dev.clashsub.source.SourceRepository;
import dev.clashsub.source.SourceService;
import dev.clashsub.support.MySqlIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

class PublicationIT extends MySqlIntegrationTest {
    @Autowired ReleaseService releases;
    @Autowired ReleaseRepository releaseRepository;
    @Autowired SubscriptionTokenService tokens;
    @Autowired ProfileService profiles;
    @Autowired ProfileRepository profileRepository;
    @MockitoSpyBean ProfileMapper profileMapper;
    @Autowired SourceService sources;
    @Autowired SourceRepository sourceRepository;
    @Autowired NodeRepository nodes;
    @Autowired OwnerRevisionGuard revisions;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoSpyBean MihomoValidator core;
    UUID owner;
    @BeforeEach void owner() {
        owner = UUID.randomUUID(); jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", owner.toString(), "fixture-" + owner, "fixture-hash");
    }

    @Test void submittedDraftAndImmutableDefinitionNodeSnapshotAndYamlCommitTogether() {
        var draft = profiles.create(owner, "Publish"); var source = sources.save(owner, sourceCommand()); var nodeId = UUID.randomUUID(); var groupId = UUID.randomUUID();
        replaceNode(source.id(), nodeId, 0, "first-fixture-secret");
        var doc = new ProfileDocument(1, "mode: rule\n", List.of(new GroupDefinition(groupId, "Chosen", "select", List.of(TargetRef.node(nodeId)), json.createObjectNode())),
                List.of(new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.group(groupId), List.of(), "", List.of())), List.of(), List.of());
        var link = tokens.link(owner, draft.profileId()); long before = revisions.current(owner);
        var first = releases.publish(owner, draft.profileId(), 1, doc);
        assertThat(profiles.load(owner, draft.profileId()).document()).isEqualTo(doc);
        assertThat(profiles.load(owner, draft.profileId()).version()).isEqualTo(2);
        assertThat(revisions.current(owner)).isEqualTo(before + 1);
        var stored = releaseRepository.require(owner, draft.profileId(), first.id());
        assertThat(stored.snapshot().definition()).isEqualTo(doc);
        assertThat(stored.nodes().get(nodeId).connection().path("password").asString()).isEqualTo("first-fixture-secret");
        assertThat(tokens.readPublished(link.token()).yaml()).isEqualTo(stored.snapshot().yaml());
        assertThat(jdbc.queryForObject("SELECT payload_cipher FROM profile_release WHERE id=?", String.class, first.id().toString()))
                .doesNotContain("first-fixture-secret", "Chosen", "example.invalid");
        replaceNode(source.id(), nodeId, 1, "second-fixture-secret");
        var second = releases.publish(owner, draft.profileId(), 2, doc);
        assertThat(second.version()).isEqualTo("2"); assertThat(second.definitionHash()).isEqualTo(first.definitionHash());
        assertThat(second.yamlDigest()).isNotEqualTo(first.yamlDigest());
        var view = profiles.list(owner).getFirst();
        assertThat(view.draftDefinitionHash()).isEqualTo(view.publishedDefinitionHash());
        assertThat(view.publishedVersion()).isEqualTo("2");
        assertThat(releaseRepository.require(owner, draft.profileId(), first.id()).snapshot().yaml()).contains("first-fixture-secret").doesNotContain("second-fixture-secret");
        assertThat(tokens.link(owner, draft.profileId()).token()).isEqualTo(link.token());
    }

    @Test void coreRejectionPreservesSavedDraftReleaseAndToken() {
        var draft = profiles.create(owner, "Rejected"); var first = releases.publish(owner, draft.profileId(), 1, draft.document());
        var saved = profiles.load(owner, draft.profileId()); var link = tokens.link(owner, draft.profileId()); var previousYaml = tokens.readPublished(link.token()).yaml();
        var invalid = new ProfileDocument(1, "mode: rule\ndns: {enable: true, enhanced-mode: invalid-mode}\n", List.of(), draft.document().rules(), List.of(), List.of());
        assertThatThrownBy(() -> releases.publish(owner, draft.profileId(), saved.version(), invalid)).isInstanceOf(ApiValidationException.class)
                .satisfies(error -> assertThat(((ApiValidationException) error).report().errors()).extracting(dev.clashsub.common.Diagnostic::code).contains("CORE_REJECTED"));
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(saved);
        assertThat(profileRepository.require(owner, draft.profileId()).currentReleaseId()).isEqualTo(first.id().toString());
        assertThat(tokens.link(owner, draft.profileId()).token()).isEqualTo(link.token());
        assertThat(tokens.readPublished(link.token()).yaml()).isEqualTo(previousYaml);
    }

    @Test void validationRunsOutsideTransactionAndOwnerChangeCausesRecomputation() throws Exception {
        var draft = profiles.create(owner, "Race"); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var calls = new AtomicInteger(); var activeTransaction = new AtomicBoolean();
        doAnswer(invocation -> {
            activeTransaction.set(activeTransaction.get() || TransactionSynchronizationManager.isActualTransactionActive());
            if (calls.incrementAndGet() == 1) { entered.countDown(); if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing release gate"); } }
            return invocation.callRealMethod();
        }).when(core).validate(anyString());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var publishing = executor.submit(() -> releases.publish(owner, draft.profileId(), 1, draft.document()));
            try { assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); sources.save(owner, sourceCommand()); }
            finally { proceed.countDown(); }
            assertThat(publishing.get(20, TimeUnit.SECONDS).version()).isEqualTo("1");
        }
        assertThat(calls.get()).isEqualTo(2); assertThat(activeTransaction.get()).isFalse();
    }

    @Test void repeatedConcurrentChangesReturnConflictWithoutPartialPublish() {
        var draft = profiles.create(owner, "Busy"); var calls = new AtomicInteger();
        doAnswer(invocation -> { calls.incrementAndGet(); sources.save(owner, sourceCommand()); return invocation.callRealMethod(); }).when(core).validate(anyString());
        assertThatThrownBy(() -> releases.publish(owner, draft.profileId(), 1, draft.document())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(calls.get()).isEqualTo(3); assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft);
        assertThat(profileRepository.require(owner, draft.profileId()).currentReleaseId()).isNull();
        assertThat(releaseRepository.list(owner, draft.profileId())).isEmpty();
    }

    @Test void staleManualSaveAndForeignNodeIdsCannotPublish() {
        var draft = profiles.create(owner, "Protected"); profiles.save(owner, draft.profileId(), 1, draft.document());
        assertThatThrownBy(() -> releases.publish(owner, draft.profileId(), 1, draft.document())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        var foreign = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null),
                TargetRef.node(UUID.randomUUID()), List.of(), "", List.of())), List.of(), List.of());
        assertThatThrownBy(() -> releases.publish(owner, draft.profileId(), 2, foreign)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
    }

    @Test void failureAtPointerUpdateRollsBackDraftReleaseAndOwnerRevision() {
        var draft = profiles.create(owner, "Rollback"); long before = revisions.current(owner);
        doThrow(new IllegalStateException("Injected transaction failure")).when(profileMapper).pointRelease(any(), any(), anyLong(), any());
        assertThatThrownBy(() -> releases.publish(owner, draft.profileId(), 1, draft.document())).isInstanceOf(IllegalStateException.class);
        assertThat(profiles.load(owner, draft.profileId())).isEqualTo(draft);
        assertThat(releaseRepository.list(owner, draft.profileId())).isEmpty();
        assertThat(revisions.current(owner)).isEqualTo(before);
        assertThat(profileRepository.require(owner, draft.profileId()).currentReleaseId()).isNull();
    }

    @Test void draftSavedDuringValidationCannotBeOverwrittenByPublishRetry() {
        var draft = profiles.create(owner, "Concurrent edit");
        var newer = new ProfileDocument(1, "mode: rule\nmixed-port: 7999\n", List.of(), draft.document().rules(), List.of(), List.of());
        doAnswer(invocation -> { profiles.save(owner, draft.profileId(), 1, newer); return invocation.callRealMethod(); }).when(core).validate(anyString());
        assertThatThrownBy(() -> releases.publish(owner, draft.profileId(), 1, draft.document())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(profiles.load(owner, draft.profileId()).document()).isEqualTo(newer);
        assertThat(releaseRepository.list(owner, draft.profileId())).isEmpty();
    }

    private SourceCommand sourceCommand() { return new SourceCommand(null, null, "Fixture", true, null, null, "https://example.invalid/sub", Map.of(), "", ""); }
    private void replaceNode(UUID source, UUID nodeId, long previous, String password) {
        var node = new NodeSpec(nodeId, source, "Fixture", "ss", json.createObjectNode().put("server", "example.invalid").put("port", 443).put("cipher", "aes-128-gcm").put("password", password), null);
        transaction.executeWithoutResult(tx -> {
            revisions.compareAndIncrement(owner, revisions.current(owner)); nodes.replaceSource(owner, source, previous + 1, List.of(node));
            sourceRepository.advanceSnapshot(owner, source, 1, previous, previous + 1, Instant.now());
        });
    }
}
