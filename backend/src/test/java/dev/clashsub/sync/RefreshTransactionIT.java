package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.profile.*;
import dev.clashsub.rule.*;
import dev.clashsub.source.FetchResult;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshTransactionIT extends RefreshTestSupport {
    @Test void parametersRenameTombstoneAndReappearancePreserveIdentityWithoutRestoringMemberships() {
        var source = source("Identity", yaml("Old", "Keep")); var old = node(source, "Old").id(); var keep = node(source, "Keep").id();
        var profile = profile("Choice", List.of(old, keep), old); var saved = profiles.load(owner, profile);
        var editedRules = new ArrayList<>(saved.document().rules());
        var original = editedRules.getFirst();
        editedRules.set(0, new RuleDefinition(original.id(), original.expression(), TargetRef.node(keep), original.modifiers(), "", List.of()));
        editedRules.add(1, new RuleDefinition(UUID.randomUUID(), new RuleExpression("DOMAIN", "unpublished.example.invalid", null), TargetRef.direct(), List.of(), "", List.of()));
        profiles.save(owner, profile, saved.version(), new ProfileDocument(1, "mode: rule\nmixed-port: 7988\n", saved.document().groups(), editedRules, List.of(), List.of()));
        respond(source, yaml("Old", "Keep").replace("fixture-first", "fixture-second"));
        assertThat(run(source).status()).isEqualTo("SUCCESS"); assertThat(node(source, "Old").id()).isEqualTo(old);
        assertThat(published(profile).groups().getFirst().members()).contains(TargetRef.node(old));
        assertThat(artifact(profile)).contains("fixture-second").doesNotContain("unpublished.example.invalid", "7988");
        respond(source, yaml("New", "Keep")); assertThat(run(source).status()).isEqualTo("SUCCESS");
        var released = published(profile); assertThat(released.groups().getFirst().members()).containsExactly(TargetRef.node(keep));
        assertThat(released.rules().getFirst().target()).isEqualTo(TargetRef.reject());
        assertThat(released.rules().getFirst().expression()).isEqualTo(original.expression());
        assertThat(released.rules().getFirst().modifiers()).containsExactly("no-resolve");
        assertThat(profiles.load(owner, profile).document().rules().getFirst().target()).isEqualTo(TargetRef.node(keep));
        assertThat(profiles.load(owner, profile).document().baseYaml()).contains("7988");
        respond(source, yaml("Old", "Keep")); assertThat(run(source).status()).isEqualTo("SUCCESS");
        assertThat(node(source, "Old").id()).isEqualTo(old);
        assertThat(published(profile).groups().getFirst().members()).doesNotContain(TargetRef.node(old));
        assertThat(published(profile).rules().getFirst().target()).isEqualTo(TargetRef.reject());
        int count = releases.list(owner, profile).size(); long version = sourceRepository.require(owner, source).snapshotVersion();
        assertThat(run(source).status()).isEqualTo("UNCHANGED"); assertThat(releases.list(owner, profile)).hasSize(count);
        assertThat(sourceRepository.require(owner, source).snapshotVersion()).isEqualTo(version);
    }

    @Test void downloadPartialParseAndInvalidUnusedCoreNodeNeverReplaceEffectiveData() {
        var source = source("Failure", yaml("Old")); var profile = profile("Choice", List.of(node(source, "Old").id()), null); var before = snapshots.read(owner);
        responses.put(sources.credentials(owner, source).url(), FetchResult.failure("UPSTREAM_HTTP"));
        assertThat(run(source).status()).isEqualTo("FAILED"); assertUnchanged(before, source, profile);
        respond(source, yaml("Old") + "- {name: Broken, type: mystery, server: fixture.invalid}\n");
        assertThat(run(source).status()).isEqualTo("FAILED"); assertUnchanged(before, source, profile);
        respond(source, yaml("Old") + "- {name: Unused, type: hysteria2, server: fixture.example.invalid, ports: '443-444', hop-interval: invalid-interval, password: fixture-only}\n");
        var failed = run(source); assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.report().errors()).extracting(Diagnostic::code).contains("CORE_REJECTED"); assertUnchanged(before, source, profile);
    }

    @Test void everyAffectedReleaseMustValidateBeforeAnyCommit() {
        var source = source("Multi", yaml("Old")); var node = node(source, "Old").id();
        var first = profile("First", List.of(node), null); var second = profile("Second", List.of(node), null); var before = snapshots.read(owner);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String yaml = call.getArgument(0);
            return yaml.contains("name: Second") ? ValidationReport.of(List.of(Diagnostic.error("CORE_REJECTED", "$", "Fixture rejection"))) : call.callRealMethod();
        }).when(core).validate(anyString());
        respond(source, yaml("Old").replace("fixture-first", "fixture-second"));
        assertThat(run(source).status()).isEqualTo("FAILED"); assertUnchanged(before, source, first, second);
    }

    @Test void databaseFailureRollsBackAllPointersNodesDraftPatchesAndAttemptSuccess() {
        var source = source("Rollback", yaml("Old")); var node = node(source, "Old").id();
        var first = profile("First", List.of(node), node); var second = profile("Second", List.of(node), node); var before = snapshots.read(owner);
        var calls = new AtomicInteger();
        doAnswer(call -> { if (calls.incrementAndGet() == 2) { throw new IllegalStateException("Injected write failure"); } return call.callRealMethod(); })
                .when(profileMapper).pointRelease(anyString(), anyString(), anyLong(), anyString());
        respond(source, yaml("Renamed")); var outcome = run(source);
        assertThat(outcome.status()).isEqualTo("FAILED"); assertUnchanged(before, source, first, second);
        assertThat(releases.list(owner, first)).hasSize(1); assertThat(releases.list(owner, second)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM refresh_attempt WHERE id=?", String.class, outcome.attemptId().toString())).isEqualTo("FAILED");
    }

    @Test void missingDialerDependencyFailsInsteadOfDroppingChain() {
        var source = source("Chain", yaml("Gateway", "Leaf").replace("name: 'Leaf',", "name: 'Leaf', dialer-proxy: Gateway,"));
        var profile = profile("Choice", List.of(node(source, "Leaf").id()), null); var before = snapshots.read(owner);
        respond(source, yaml("Leaf").replace("name: 'Leaf',", "name: 'Leaf', dialer-proxy: Gateway,"));
        assertThat(run(source).status()).isEqualTo("FAILED"); assertUnchanged(before, source, profile);
    }
}
