package dev.clashsub.sync;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RefreshTaskRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RefreshTaskRunner.class);
    private final RefreshJobStore jobs;
    private final RefreshService refresh;
    private final Clock clock;
    private final ConcurrentMap<UUID, FutureTask<Void>> submitted = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().daemon(true).name("source-refresh-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public RefreshTaskRunner(RefreshJobStore jobs, RefreshService refresh, Clock clock) { this.jobs = jobs; this.refresh = refresh; this.clock = clock; }
    public TaskView enqueue(UUID owner, UUID source, Trigger trigger) {
        var task = jobs.enqueue(owner, source, trigger, clock.instant()); if (task != null) { submit(owner, task.id()); } return task;
    }
    public TaskView get(UUID owner, UUID id) { return jobs.get(owner, id); }
    public List<TaskView> history(UUID owner, UUID source) { return jobs.history(owner, source); }
    public void pump() { for (var job : jobs.queued()) { submit(UUID.fromString(job.ownerId()), UUID.fromString(job.id())); } }
    public void interrupt(UUID id) { var task = submitted.get(id); if (task != null) { task.cancel(true); } }
    private void submit(UUID owner, UUID id) {
        var future = new FutureTask<Void>(() -> {
                try {
                    if (jobs.claim(owner, id, clock.instant())) { var task = jobs.get(owner, id); refresh.refresh(owner, task.sourceId(), id); }
                } catch (RuntimeException exception) { LOG.warn("Refresh worker interrupted: task={}, type={}", id, exception.getClass().getSimpleName()); }
                finally {
                    try { jobs.release(owner, id); }
                    catch (RuntimeException exception) { LOG.warn("Refresh task release deferred: task={}, type={}", id, exception.getClass().getSimpleName()); }
                    finally {
                        submitted.remove(id);
                        if (!workers.isShutdown()) {
                            try { pump(); } catch (RuntimeException exception) { LOG.warn("Refresh dispatch deferred: type={}", exception.getClass().getSimpleName()); }
                        }
                    }
                }
            }, null);
        if (submitted.putIfAbsent(id, future) != null) { return; }
        try { workers.execute(future); }
        catch (RejectedExecutionException exception) { submitted.remove(id); /* Still QUEUED in MySQL; the next pump retries. */ }
    }
    @PreDestroy public void close() { workers.shutdownNow(); try { workers.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }
}
