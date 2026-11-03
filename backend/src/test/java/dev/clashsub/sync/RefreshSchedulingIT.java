package dev.clashsub.sync;

import dev.clashsub.ClashSubApplication;
import dev.clashsub.common.ApiException;
import dev.clashsub.source.*;
import dev.clashsub.support.ApiTestClient;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshSchedulingIT extends RefreshTestSupport {
    @Autowired RefreshTaskRunner runner;
    @Autowired RefreshScheduler scheduler;
    @Autowired RefreshJobStore jobs;
    @MockitoBean Clock clock;
    @LocalServerPort int port;
    Instant now;
    @BeforeEach void clock() {
        now = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS); when(clock.instant()).thenReturn(now);
        jdbc.update("UPDATE subscription_source SET is_enabled=0 WHERE owner_id<>?", owner.toString());
    }

    @Test void duplicatesShareAnActiveTaskAndPausedSourcesRemainManuallyRefreshable() throws Exception {
        var source = source("Paused", yaml("Old")); var before = nodes.catalog(owner);
        assertThat(sourceRepository.require(owner, source).refreshIntervalSeconds()).isEqualTo(21_600);
        sources.save(owner, new SourceCommand(source, 1L, null, false, null, null, null, null, null, null));
        assertThat(scheduler.dispatchDue(now)).isZero(); assertThat(nodes.catalog(owner)).isEqualTo(before);
        assertThat(jobs.enqueue(owner, source, Trigger.SCHEDULED, now)).isNull();
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing worker gate"); }
            SourceRequest request = call.getArgument(0); return responses.get(request.url()); }).when(fetcher).fetch(any());
        TaskView first;
        try {
            first = runner.enqueue(owner, source, Trigger.MANUAL); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.enqueue(owner, source, Trigger.MANUAL).id()).isEqualTo(first.id());
        } finally { proceed.countDown(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(runner.get(owner, first.id()).status()).isEqualTo("UNCHANGED"));
        assertThat(nodes.catalog(owner)).isEqualTo(before);
        assertThatThrownBy(() -> sources.save(owner, new SourceCommand(source, 2L, null, null, 299, null, null, null, null, null))).isInstanceOf(ApiException.class);
    }

    @Test void longDowntimeIsCoalescedAndDifferentSourcesFinishIndependently() {
        var good = source("Good", yaml("Old")); var bad = source("Bad", yaml("Other"));
        responses.put(sources.credentials(owner, bad).url(), FetchResult.failure("UPSTREAM_HTTP"));
        jdbc.update("UPDATE subscription_source SET next_refresh_time=? WHERE owner_id=?", java.sql.Timestamp.from(now.minus(Duration.ofDays(90))), owner.toString());
        assertThat(scheduler.dispatchDue(now)).isEqualTo(2); assertThat(scheduler.dispatchDue(now)).isZero();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(runner.history(owner, good).getFirst().status()).isEqualTo("UNCHANGED");
            assertThat(runner.history(owner, bad).getFirst().status()).isEqualTo("FAILED");
        });
        assertThat(sourceRepository.require(owner, good).nextRefreshTime()).isEqualTo(now.plusSeconds(21_600));
        assertThat(runner.history(owner, bad).getFirst().outcome().report().errors().toString()).doesNotContain("https://", "fixture-first");
    }

    @Test void expiredWorkerCannotCommitAfterReplacementTaskAndIsRecordedAsTimedOut() throws Exception {
        var source = source("Lease fence", yaml("Old")); var original = node(source, "Old").id();
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var once = new AtomicBoolean();
        doAnswer(call -> {
            SourceRequest request = call.getArgument(0);
            if (once.compareAndSet(false, true)) { entered.countDown();
                try { if (!proceed.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Missing stale worker gate"); } }
                catch (InterruptedException exception) { interrupted.countDown(); return FetchResult.failure("UPSTREAM_IO"); }
                return new FetchResult(yaml("Stale").getBytes(java.nio.charset.StandardCharsets.UTF_8), 200, "application/yaml", now, null); }
            return responses.get(request.url());
        }).when(fetcher).fetch(any());
        TaskView old;
        try {
            old = runner.enqueue(owner, source, Trigger.MANUAL); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var futureTime = now.plus(Duration.ofMinutes(10)); when(clock.instant()).thenReturn(futureTime);
            assertThat(scheduler.dispatchDue(futureTime)).isEqualTo(1);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(runner.history(owner, source).getFirst().status()).isEqualTo("UNCHANGED"));
        } finally { proceed.countDown(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(runner.get(owner, old.id()).status()).isEqualTo("FAILED"));
        assertThat(runner.get(owner, old.id()).outcome().report().errors()).extracting(dev.clashsub.common.Diagnostic::code).contains("TASK_TIMEOUT");
        assertThat(node(source, "Old").id()).isEqualTo(original); assertThat(nodes.catalog(owner).nodes()).hasSize(1);
    }

    @Test void workerThatReachesDeadlineBeforeSchedulerTickIsAlsoFailedAndCannotCommit() {
        var source = source("Deadline", yaml("Old")); var previous = nodes.catalog(owner);
        doAnswer(call -> {
            when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(10)));
            return new FetchResult(yaml("Late").getBytes(java.nio.charset.StandardCharsets.UTF_8), 200, "application/yaml", now, null);
        }).when(fetcher).fetch(any());
        var task = runner.enqueue(owner, source, Trigger.MANUAL);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(runner.get(owner, task.id()).status()).isEqualTo("FAILED"));
        assertThat(runner.get(owner, task.id()).outcome().report().errors()).extracting(dev.clashsub.common.Diagnostic::code).contains("TASK_TIMEOUT");
        assertThat(nodes.catalog(owner)).isEqualTo(previous);
    }

    @Test void boundedWorkersLeaveOverflowQueuedAndEventuallyDrainIt() throws Exception {
        var entered = new CountDownLatch(4); var proceed = new CountDownLatch(1); var ids = new ArrayList<UUID>();
        doAnswer(call -> { entered.countDown(); if (!proceed.await(15, TimeUnit.SECONDS)) { throw new AssertionError("Missing saturation gate"); } return FetchResult.failure("UPSTREAM_HTTP"); }).when(fetcher).fetch(any());
        try {
            for (int i = 0; i < 72; i++) {
                var source = sources.save(owner, new SourceCommand(null, null, "Queue " + i, false, null, null, "https://fixture.example.invalid/" + i, Map.of(), "", ""));
                ids.add(runner.enqueue(owner, source.id(), Trigger.MANUAL).id());
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_attempt WHERE owner_id=? AND status='RUNNING'", Integer.class, owner.toString())).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_attempt WHERE owner_id=? AND status='QUEUED'", Integer.class, owner.toString())).isEqualTo(68);
        } finally { proceed.countDown(); }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_attempt WHERE owner_id=? AND status IN ('RUNNING','QUEUED')", Integer.class, owner.toString())).isZero());
        assertThat(ids).hasSize(72).doesNotHaveDuplicates();
    }

    @Test void queuedAndInterruptedJobsSurviveARebuiltApplicationContext() throws Exception {
        var source = source("Restart", yaml("Old")); var first = jobs.enqueue(owner, source, Trigger.MANUAL, now);
        assertThat(jobs.claim(owner, first.id(), now)).isTrue();
        jdbc.update("UPDATE refresh_attempt SET lease_until=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)), first.id().toString());
        var key = Files.createTempFile("restart-fixture-key", ".secret"); Files.write(key, new byte[32]);
        try (var restarted = new SpringApplicationBuilder(ClashSubApplication.class, RestartFixtures.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(), "--clash.admin.password-file=/missing-unused-bootstrap",
                        "--clash.encryption-key-file=" + key, "--clash.scheduling.enabled=false")) {
            var restartedScheduler = restarted.getBean(RefreshScheduler.class); var restartedRunner = restarted.getBean(RefreshTaskRunner.class);
            assertThat(restartedScheduler.dispatchDue(Instant.now())).isEqualTo(1);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(restartedRunner.history(owner, source).getFirst().status()).isEqualTo("FAILED"));
            assertThat(restartedRunner.history(owner, source).stream().filter(row -> row.trigger() == Trigger.RECOVERY)).hasSize(1);
            assertThat(restartedScheduler.dispatchDue(Instant.now())).isZero();
        } finally { Files.deleteIfExists(key); }
        assertThat(sourceRepository.require(owner, source).snapshotVersion()).isEqualTo(1);
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RestartFixtures {
        @Bean @Primary UpstreamFetcher unavailableUpstream() { var mock = mock(UpstreamFetcher.class); when(mock.fetch(any())).thenReturn(FetchResult.failure("UPSTREAM_HTTP")); return mock; }
    }

    @Test void taskAndDecisionHttpEndpointsEnforceOwnerAndReturnSafeResults() throws Exception {
        UUID admin = UUID.fromString(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, ADMIN));
        var source = sources.save(admin, new SourceCommand(null, null, "HTTP", false, null, null, "https://fixture.example.invalid/api", Map.of(), "", ""));
        var client = new ApiTestClient(port); client.login(ADMIN, PASSWORD);
        var response = client.send("POST", "/api/v1/sources/" + source.id() + "/refresh", Map.of()); assertThat(response.statusCode()).isEqualTo(202);
        var id = UUID.fromString(client.data(response).path("id").asString());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(runner.get(admin, id).status()).isEqualTo("FAILED"));
        assertThat(client.get("/api/v1/tasks/" + id).statusCode()).isEqualTo(200);
        var foreign = source("Foreign", yaml("Other")); var task = jobs.enqueue(owner, foreign, Trigger.MANUAL, now);
        assertThat(client.get("/api/v1/tasks/" + task.id()).statusCode()).isEqualTo(404);
        assertThat(client.get("/api/v1/sources/" + foreign + "/history").statusCode()).isEqualTo(404);
        respond(foreign, "proxies: []\n"); var pending = run(foreign).decisionId();
        assertThat(client.send("POST", "/api/v1/empty-decisions/" + pending + "/decision", Map.of("choice", "CLEAR")).statusCode()).isEqualTo(404);
        assertThat(client.get("/api/v1/empty-decisions").statusCode()).isEqualTo(200);
    }
}
