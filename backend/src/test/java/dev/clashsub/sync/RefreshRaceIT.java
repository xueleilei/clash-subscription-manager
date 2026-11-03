package dev.clashsub.sync;

import dev.clashsub.profile.*;
import dev.clashsub.rule.*;
import dev.clashsub.source.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class RefreshRaceIT extends RefreshTestSupport {
    @Test void newProfileAndNewDraftReferencesDuringValidationAreReconciledOnRetry() throws Exception {
        var source = source("Race", yaml("Old")); var old = node(source, "Old").id(); var first = profile("Choice", List.of(old), old);
        respond(source, yaml("Renamed")); var gate = gate();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> run(source));
            UUID created;
            try { gate.entered(); created = profile("Created during validation", List.of(old), old); }
            finally { gate.release(); }
            assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
            assertThat(published(created).groups().getFirst().members()).isEmpty();
            assertThat(profiles.load(owner, created).document().rules().getFirst().target()).isEqualTo(TargetRef.reject());
        }
        assertThat(published(first).groups().getFirst().members()).isEmpty();
    }
    @Test void draftRuleRetargetAndUnpublishedSettingsSurviveConcurrentCleanup() throws Exception {
        var source = source("Draft race", yaml("Old", "Keep")); var old = node(source, "Old").id(); var keep = node(source, "Keep").id();
        var profile = profile("Choice", List.of(old), old); respond(source, yaml("Keep")); var gate = gate();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> run(source));
            try {
                gate.entered(); var draft = profiles.load(owner, profile); var rules = new ArrayList<>(draft.document().rules()); var rule = rules.getFirst();
                rules.set(0, new RuleDefinition(rule.id(), rule.expression(), TargetRef.node(keep), rule.modifiers(), "", List.of()));
                profiles.save(owner, profile, draft.version(), new ProfileDocument(1, "mode: rule\nmixed-port: 7999\n", draft.document().groups(), rules, List.of(), List.of()));
            } finally { gate.release(); }
            assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
        }
        assertThat(profiles.load(owner, profile).document().rules().getFirst().target()).isEqualTo(TargetRef.node(keep));
        assertThat(profiles.load(owner, profile).document().baseYaml()).contains("7999");
        assertThat(published(profile).rules().getFirst().target()).isEqualTo(TargetRef.reject()); assertThat(artifact(profile)).doesNotContain("7999");
    }
    @Test void twoSourcesUpdatingOnePublishedProfileDoNotLoseEitherChange() throws Exception {
        var a = source("A", yaml("NodeA")); var b = source("B", yaml("NodeB")); var profile = profile("Choice", List.of(node(a,"NodeA").id(), node(b,"NodeB").id()), null);
        respond(a, yaml("NodeA").replace("fixture-first", "fixture-a-new")); respond(b, yaml("NodeB").replace("fixture-first", "fixture-b-new")); var gate = gate();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> run(a));
            try { gate.entered(); assertThat(run(b).status()).isEqualTo("SUCCESS"); } finally { gate.release(); }
            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
        }
        assertThat(artifact(profile)).contains("fixture-a-new", "fixture-b-new");
    }
    @Test void manualPublicationDuringRefreshIsTheDefinitionUsedByRetry() throws Exception {
        var source = source("Publish race", yaml("Old")); var profile = profile("Choice", List.of(node(source,"Old").id()), null);
        respond(source, yaml("Old").replace("fixture-first", "fixture-new")); var gate = gate();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> run(source));
            try {
                gate.entered(); var draft = profiles.load(owner, profile);
                publisher.publish(owner, profile, draft.version(), new ProfileDocument(1, "mode: rule\nmixed-port: 7895\n", draft.document().groups(), draft.document().rules(), List.of(), List.of()));
            } finally { gate.release(); }
            assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
        }
        assertThat(artifact(profile)).contains("7895", "fixture-new");
    }
    @Test void configurationChangedDuringDownloadRejectsTheOldResponse() throws Exception {
        var source = source("Fetch race", yaml("Old")); var before = snapshots.read(owner);
        respond(source, yaml("Renamed")); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing fetch gate"); }
            SourceRequest request = call.getArgument(0); return responses.get(request.url());
        }).when(fetcher).fetch(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> run(source));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                sources.save(owner, new SourceCommand(source, sourceRepository.require(owner, source).configVersion(), "Edited during fetch", null, null, null, null, null, null, null));
            } finally { proceed.countDown(); }
            assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo("CONFLICT");
        }
        assertThat(nodes.catalog(owner)).isEqualTo(before.nodes()); assertThat(sourceRepository.require(owner, source).snapshotVersion()).isEqualTo(1);
    }
    @Test void continualOwnerChangesStopAfterThreeAttemptsWithoutChangingNodes() {
        var source = source("Busy owner", yaml("Old")); var before = nodes.catalog(owner); var calls = new AtomicInteger();
        doAnswer(call -> { calls.incrementAndGet(); profiles.create(owner, "Concurrent draft"); return call.callRealMethod(); }).when(core).validate(anyString());
        respond(source, yaml("Changed")); assertThat(run(source).status()).isEqualTo("CONFLICT");
        assertThat(calls.get()).isEqualTo(3); assertThat(nodes.catalog(owner)).isEqualTo(before);
        assertThat(sourceRepository.require(owner, source).snapshotVersion()).isEqualTo(1);
    }
    private Gate gate() {
        var gate = new Gate(); var once = new AtomicBoolean();
        doAnswer(call -> {
            String yaml = call.getArgument(0);
            if (yaml.contains("name: Choice") && once.compareAndSet(false, true)) { gate.entered.countDown(); if (!gate.proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing race gate"); } }
            return call.callRealMethod();
        }).when(core).validate(anyString()); return gate;
    }
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1); final CountDownLatch proceed = new CountDownLatch(1);
        void entered() throws InterruptedException { assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); }
        void release() { proceed.countDown(); }
    }
}
