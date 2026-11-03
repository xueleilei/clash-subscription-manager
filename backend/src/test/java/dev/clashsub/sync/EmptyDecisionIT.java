package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.source.SourceCommand;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;

class EmptyDecisionIT extends RefreshTestSupport {
    @Test void repeatedEmptyWaitsAndKeepDoesNotClearOrPromptAgain() {
        var source = source("Empty", yaml("Old")); var profile = profile("Choice", List.of(node(source, "Old").id()), null); var before = snapshots.read(owner);
        respond(source, "proxies: []\n"); var first = run(source); var again = run(source);
        assertThat(first.status()).isEqualTo("EMPTY_PENDING"); assertThat(again.decisionId()).isEqualTo(first.decisionId()); assertUnchanged(before, source, profile);
        decisions.decide(owner, first.decisionId(), EmptyChoice.KEEP); assertUnchanged(before, source, profile);
        assertThat(run(source).status()).isEqualTo("EMPTY_KEPT"); assertThat(decisions.pending(owner)).isEmpty();
    }
    @Test void clearRetainsEmptyGroupsAndRuleConditionsWithReject() {
        var source = source("Clear", yaml("Old")); var old = node(source, "Old").id(); var profile = profile("Choice", List.of(old), old);
        var beforeRule = published(profile).rules().getFirst(); respond(source, "proxies: []\n"); var pending = run(source);
        decisions.decide(owner, pending.decisionId(), EmptyChoice.CLEAR);
        assertThat(nodes.catalog(owner).nodes()).isEmpty(); assertThat(published(profile).groups().getFirst().members()).isEmpty();
        assertThat(published(profile).rules().getFirst().target()).isEqualTo(TargetRef.reject());
        assertThat(published(profile).rules().getFirst().expression()).isEqualTo(beforeRule.expression());
        assertThat(artifact(profile)).contains("empty-fallback: REJECT", "IP-CIDR,192.0.2.0/24,REJECT,no-resolve");
        assertThat(decisions.pending(owner)).isEmpty();
    }
    @Test void recoveryEvenWithUnchangedNodesAndConfigurationEditExpireOldDecisions() {
        var source = source("Recover", yaml("Old")); respond(source, "proxies: []\n"); var old = run(source).decisionId();
        respond(source, yaml("Old")); assertThat(run(source).status()).isEqualTo("UNCHANGED");
        assertThatThrownBy(() -> decisions.decide(owner, old, EmptyChoice.CLEAR)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        respond(source, "proxies: []\n"); var second = run(source).decisionId(); assertThat(second).isNotEqualTo(old);
        sources.save(owner, new SourceCommand(source, sourceRepository.require(owner, source).configVersion(), "Edited", null, null, null, null, null, null, null));
        assertThatThrownBy(() -> decisions.decide(owner, second, EmptyChoice.CLEAR)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(nodes.catalog(owner).nodes()).hasSize(1);
    }
    @Test void failedClearPreservesPendingDecisionAndEveryEffectiveValue() {
        var source = source("Rejected clear", yaml("Old")); var profile = profile("Choice", List.of(node(source, "Old").id()), null);
        respond(source, "proxies: []\n"); var decision = run(source).decisionId(); var before = snapshots.read(owner);
        doReturn(ValidationReport.of(List.of(Diagnostic.error("CORE_REJECTED", "$", "Fixture rejection")))).when(core).validate(anyString());
        assertThatThrownBy(() -> decisions.decide(owner, decision, EmptyChoice.CLEAR)).isInstanceOf(ApiValidationException.class);
        assertUnchanged(before, source, profile); assertThat(decisions.pending(owner)).hasSize(1);
        assertThatThrownBy(() -> decisions.decide(UUID.randomUUID(), decision, EmptyChoice.CLEAR)).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
    }
    @Test void recoveryWhileClearIsBeingValidatedCannotBeErasedByOldConfirmation() throws Exception {
        var source = source("Overlapping recovery", yaml("Old")); var profile = profile("Choice", List.of(node(source, "Old").id()), null);
        respond(source, "proxies: []\n"); var pending = run(source).decisionId();
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1); var once = new AtomicBoolean();
        doAnswer(call -> {
            if (once.compareAndSet(false, true)) { entered.countDown(); if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing clear gate"); } }
            return call.callRealMethod();
        }).when(core).validate(anyString());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var clearing = executor.submit(() -> decisions.decide(owner, pending, EmptyChoice.CLEAR));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                respond(source, yaml("Old").replace("fixture-first", "fixture-recovered")); assertThat(run(source).status()).isEqualTo("SUCCESS");
            } finally { proceed.countDown(); }
            assertThatThrownBy(() -> clearing.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).cause().isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        }
        assertThat(nodes.catalog(owner).nodes()).hasSize(1); assertThat(artifact(profile)).contains("fixture-recovered");
    }
}
