package dev.clashsub.sync;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name = "clash.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingTicker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SchedulingTicker.class);
    private final RefreshScheduler scheduler;
    private final RetentionService retention;
    private final Clock clock;
    public SchedulingTicker(RefreshScheduler scheduler, RetentionService retention, Clock clock) { this.scheduler = scheduler; this.retention = retention; this.clock = clock; }
    @Scheduled(fixedDelay = 2000, initialDelay = 2000) public void tick() {
        try { scheduler.dispatchDue(clock.instant()); } catch (RuntimeException exception) { LOG.warn("Scheduled dispatch deferred: type={}", exception.getClass().getSimpleName()); }
    }
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC") public void retain() {
        try { retention.clean(clock.instant()); } catch (RuntimeException exception) { LOG.warn("Retention deferred: type={}", exception.getClass().getSimpleName()); }
    }
}
