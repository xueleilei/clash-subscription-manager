package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.source.SourceDO;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefreshJobStore {
    private final RefreshJobMapper mapper;
    private final RefreshStateStore state;
    private final int timeoutSeconds;
    public RefreshJobStore(RefreshJobMapper mapper, RefreshStateStore state, @Value("${clash.scheduling.timeout-seconds:300}") int timeoutSeconds) {
        if (timeoutSeconds < 60 || timeoutSeconds > 3600) { throw new IllegalArgumentException("Refresh timeout must be between 60 and 3600 seconds"); }
        this.mapper = mapper; this.state = state; this.timeoutSeconds = timeoutSeconds;
    }
    @Transactional public TaskView enqueue(UUID owner, UUID source, Trigger trigger, Instant now) {
        var row = lock(owner, source); String active = mapper.active(owner.toString(), source.toString());
        if (trigger == Trigger.SCHEDULED && (!row.enabled() || row.nextRefreshTime() == null || row.nextRefreshTime().isAfter(now))) { return null; }
        if (active != null) {
            var job = mapper.job(owner.toString(), active);
            if (job != null && Set.of("QUEUED", "RUNNING").contains(job.status())) { return trigger == Trigger.SCHEDULED ? null : get(owner, UUID.fromString(active)); }
        }
        return create(owner, row, trigger, now);
    }
    private TaskView create(UUID owner, SourceDO source, Trigger trigger, Instant now) {
        UUID id = UUID.randomUUID();
        mapper.insert(owner.toString(), source.id(), id.toString(), source.configVersion(), source.snapshotVersion(), trigger.name(), now);
        mapper.activate(owner.toString(), source.id(), id.toString(), now.plusSeconds(source.refreshIntervalSeconds()));
        return get(owner, id);
    }
    @Transactional public boolean claim(UUID owner, UUID attempt, Instant now) {
        var job = mapper.job(owner.toString(), attempt.toString()); if (job == null) { return false; }
        var source = mapper.lockSource(owner.toString(), job.sourceId()); if (source == null) { return false; }
        if (!attempt.toString().equals(mapper.active(owner.toString(), job.sourceId()))) { return false; }
        if (mapper.claim(owner.toString(), attempt.toString(), now.plusSeconds(timeoutSeconds), now) != 1) { return false; }
        mapper.running(owner.toString(), job.sourceId(), attempt.toString(), now); return true;
    }
    @Transactional public void release(UUID owner, UUID attempt) {
        var job = mapper.job(owner.toString(), attempt.toString()); if (job == null) { return; }
        if (mapper.lockSource(owner.toString(), job.sourceId()) == null) { return; }
        // An interrupted worker must not clear a replacement attempt's pointer.
        var current = mapper.job(owner.toString(), attempt.toString());
        if (!Set.of("QUEUED", "RUNNING").contains(current.status())) { mapper.release(owner.toString(), job.sourceId(), attempt.toString()); }
    }
    @Transactional public TaskView recover(JobDO expired, Instant now) {
        UUID owner = UUID.fromString(expired.ownerId()), sourceId = UUID.fromString(expired.sourceId());
        var source = mapper.lockSource(expired.ownerId(), expired.sourceId()); if (source == null) { return null; }
        var job = mapper.job(expired.ownerId(), expired.id());
        if (job == null || !job.status().equals("RUNNING") || job.leaseUntil() == null || job.leaseUntil().isAfter(now)) { return null; }
        var outcome = new RefreshOutcome(UUID.fromString(job.id()), sourceId, "FAILED", null, ChangeSummary.none(),
                ValidationReport.of(List.of(Diagnostic.error("TASK_TIMEOUT", "source", "任务超时或运行被中断，旧数据保留，已安排恢复任务"))));
        state.finish(owner, outcome);
        if (!job.id().equals(mapper.active(expired.ownerId(), expired.sourceId()))) { return null; }
        return create(owner, source, Trigger.RECOVERY, now);
    }
    public List<JobDO> queued() { return mapper.queued(); }
    public List<JobDO> expired(Instant now) { return mapper.expired(now); }
    public List<SourceDO> due(Instant now) { return mapper.due(now); }
    public TaskView get(UUID owner, UUID id) {
        var row = state.requireAttempt(owner, id); var job = mapper.job(owner.toString(), id.toString());
        return new TaskView(id, UUID.fromString(row.sourceId()), row.status(), Trigger.valueOf(job.triggerKind()), row.createTime(), row.updateTime(), state.outcome(row));
    }
    public List<TaskView> history(UUID owner, UUID source) { return state.history(owner, source).stream().map(row -> get(owner, UUID.fromString(row.id()))).toList(); }
    private SourceDO lock(UUID owner, UUID source) { var row = mapper.lockSource(owner.toString(), source.toString()); if (row == null) { throw ApiException.missing(); } return row; }
}
