package dev.clashsub.source;

import dev.clashsub.common.*;
import dev.clashsub.importer.*;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.sync.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;

class SourceRemovalIT extends RefreshTestSupport {
    @Autowired SourceRemovalService removal;
    @Autowired RefreshJobStore jobs;
    @Autowired RefreshTaskRunner runner;
    @Test void keepLocalPreservesIdsParametersAndRoutingWithLocalSourceLabel() {
        var source = source("Keep", yaml("Old")); var node = node(source, "Old"); var profile = profile("Choice", List.of(node.id()), node.id());
        var before = artifact(profile); var preview = removal.preview(owner, source); assertThat(preview.affectedProfiles()).contains(profile);
        removal.apply(owner, preview.id(), RemovalMode.KEEP_LOCAL);
        assertThatThrownBy(() -> sourceRepository.require(owner, source)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        var local = nodes.connection(owner, node.id()); assertThat(local.sourceId()).isNull(); assertThat(local.connection()).isEqualTo(node.connection());
        assertThat(artifact(profile)).isEqualTo(before.replace("Old · Keep", "Old · 本地节点"));
        assertThat(published(profile).groups().getFirst().members()).contains(TargetRef.node(node.id()));
        assertThat(snapshots.read(owner).profiles().get(profile).published().sourceVersions()).doesNotContainKey(source);
    }
    @Test void clearUsesRejectCleanupAndIsAtomicWhenCoreRejects() {
        var source = source("Clear", yaml("Old")); var node = node(source, "Old"); var profile = profile("Choice", List.of(node.id()), node.id());
        removal.apply(owner, removal.preview(owner, source).id(), RemovalMode.CLEAR);
        assertThat(nodes.catalog(owner).nodes()).isEmpty(); assertThat(published(profile).groups().getFirst().members()).isEmpty();
        assertThat(published(profile).rules().getFirst().target()).isEqualTo(TargetRef.reject());
        var other = source("Rejected", yaml("Other")); var otherProfile = profile("Second", List.of(node(other,"Other").id()), null); var before = snapshots.read(owner);
        var preview = removal.preview(owner, other);
        doReturn(ValidationReport.of(List.of(Diagnostic.error("CORE_REJECTED", "$", "Fixture rejection")))).when(core).validate(anyString());
        assertThatThrownBy(() -> removal.apply(owner, preview.id(), RemovalMode.CLEAR)).isInstanceOf(ApiValidationException.class);
        assertUnchanged(before, other, otherProfile);
    }
    @Test void staleForeignRemovalAndQueuedRefreshCannotRecreateADeletedSource() {
        var source = source("Protected", yaml("Old")); var preview = removal.preview(owner, source);
        assertThatThrownBy(() -> removal.apply(UUID.randomUUID(), preview.id(), RemovalMode.CLEAR)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        sources.save(owner, new SourceCommand(source, 1L, "Edited", null, null, null, null, null, null, null));
        assertThatThrownBy(() -> removal.apply(owner, preview.id(), RemovalMode.CLEAR)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        var queued = jobs.enqueue(owner, source, Trigger.MANUAL, java.time.Instant.now());
        removal.apply(owner, removal.preview(owner, source).id(), RemovalMode.CLEAR);
        assertThat(jobs.get(owner, queued.id()).status()).isEqualTo("CANCELLED");
        assertThat(jobs.claim(owner, queued.id(), java.time.Instant.now())).isFalse();
        assertThatThrownBy(() -> run(source)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
    }
    @Test void publicationDuringRemovalRequiresNewPreviewAndPreservesBothSourceAndNewRelease() throws Exception {
        var source = source("Publication race", yaml("Old")); var profile = profile("Choice", List.of(node(source,"Old").id()), null);
        var preview = removal.preview(owner, source); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1); var once = new AtomicBoolean();
        doAnswer(call -> { if (once.compareAndSet(false, true)) { entered.countDown(); if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing removal gate"); } } return call.callRealMethod(); }).when(core).validate(anyString());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deleting = executor.submit(() -> removal.apply(owner, preview.id(), RemovalMode.CLEAR));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); var draft = profiles.load(owner, profile);
                publisher.publish(owner, profile, draft.version(), new dev.clashsub.profile.ProfileDocument(1, "mode: rule\nmixed-port: 7896\n", draft.document().groups(), draft.document().rules(), List.of(), List.of()));
            } finally { proceed.countDown(); }
            assertThatThrownBy(() -> deleting.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).cause().isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        }
        assertThat(sourceRepository.require(owner, source).snapshotVersion()).isEqualTo(1); assertThat(artifact(profile)).contains("7896");
    }
    @Test void deletingDuringDownloadCancelsWorkerAndNoLateResponseRestoresNodes() throws Exception {
        var source = source("Download race", yaml("Old")); var profile = profile("Choice", List.of(node(source,"Old").id()), null);
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); try { proceed.await(10, TimeUnit.SECONDS); } catch (InterruptedException exception) { interrupted.countDown(); }
            return new FetchResult(yaml("Late").getBytes(java.nio.charset.StandardCharsets.UTF_8), 200, "application/yaml", java.time.Instant.now(), null);
        }).when(fetcher).fetch(any());
        var task = runner.enqueue(owner, source, Trigger.MANUAL);
        try { assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); removal.apply(owner, removal.preview(owner, source).id(), RemovalMode.CLEAR); assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue(); }
        finally { proceed.countDown(); }
        assertThat(jobs.get(owner, task.id()).status()).isEqualTo("CANCELLED"); assertThat(nodes.catalog(owner).nodes()).isEmpty();
        assertThat(published(profile).groups().getFirst().members()).isEmpty();
    }
}
