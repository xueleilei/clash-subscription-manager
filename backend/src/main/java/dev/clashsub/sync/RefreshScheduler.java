package dev.clashsub.sync;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class RefreshScheduler {
    private final RefreshJobStore jobs;
    private final RefreshTaskRunner runner;
    public RefreshScheduler(RefreshJobStore jobs, RefreshTaskRunner runner) { this.jobs = jobs; this.runner = runner; }
    public int dispatchDue(Instant now) {
        int count = 0;
        for (var job : jobs.expired(now)) {
            if (jobs.recover(job, now) != null) { runner.interrupt(UUID.fromString(job.id())); count++; }
        }
        for (var source : jobs.due(now)) {
            if (jobs.enqueue(UUID.fromString(source.ownerId()), UUID.fromString(source.id()), Trigger.SCHEDULED, now) != null) { count++; }
        }
        runner.pump(); return count;
    }
}
