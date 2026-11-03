package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.source.*;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefreshStateStore {
    private final RefreshMapper mapper;
    private final EncryptedDocuments documents;
    private final OwnerRevisionGuard revisions;
    private final SourceRepository sources;
    private final Clock clock;
    public RefreshStateStore(RefreshMapper mapper, EncryptedDocuments documents, OwnerRevisionGuard revisions, SourceRepository sources, Clock clock) {
        this.mapper = mapper; this.documents = documents; this.revisions = revisions; this.sources = sources; this.clock = clock;
    }
    @Transactional public RefreshOutcome begin(UUID owner, SourceDO source, UUID attempt) {
        mapper.insertAttempt(new AttemptDO(attempt.toString(), owner.toString(), source.id(), source.configVersion(), source.snapshotVersion(), "RUNNING", null, null, null));
        var row = requireAttempt(owner, attempt);
        if (!row.sourceId().equals(source.id())) { throw ApiException.missing(); }
        if (row.payloadCipher() != null) { return outcome(row); }
        if (mapper.running(owner.toString(), attempt.toString(), source.configVersion(), source.snapshotVersion()) != 1) { throw ApiException.conflict(); }
        return null;
    }
    public AttemptDO requireAttempt(UUID owner, UUID id) { var row = mapper.attempt(owner.toString(), id.toString()); if (row == null) { throw ApiException.missing(); } return row; }
    public RefreshOutcome outcome(AttemptDO row) { return row.payloadCipher() == null ? null : documents.read(row.payloadCipher(), attemptContext(row.ownerId(), row.id()), RefreshOutcome.class); }
    public List<AttemptDO> history(UUID owner, UUID source) { sources.require(owner, source); return mapper.history(owner.toString(), source.toString()); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(UUID owner, RefreshOutcome outcome) {
        if (mapper.finish(owner.toString(), outcome.attemptId().toString(), outcome.status(), documents.write(outcome, attemptContext(owner.toString(), outcome.attemptId().toString()))) != 1) { throw ApiException.conflict(); }
    }
    @Transactional public RefreshOutcome failure(UUID owner, SourceDO base, RefreshOutcome outcome) {
        sources.lock(owner, UUID.fromString(base.id()));
        var row = requireAttempt(owner, outcome.attemptId());
        if (row.payloadCipher() != null) { return outcome(row); }
        String code = outcome.report().errors().isEmpty() ? "REFRESH_FAILED" : outcome.report().errors().getFirst().code();
        sources.attemptStatus(owner, UUID.fromString(base.id()), base.configVersion(), base.snapshotVersion(), "FAILED", code, Instant.now());
        finish(owner, outcome); return outcome;
    }
    @Transactional public RefreshOutcome pending(UUID owner, SourceDO base, UUID attempt, byte[] body) {
        revisions.compareAndIncrement(owner, revisions.current(owner)); sources.lock(owner, UUID.fromString(base.id())); assertRunning(owner, attempt); assertSource(owner, base);
        String digest = DefinitionHasher.sha256(body); var existing = mapper.matching(owner.toString(), base.id(), base.configVersion(), base.snapshotVersion(), digest);
        if (existing == null) {
            UUID id = UUID.randomUUID(); existing = new EmptyDecisionDO(id.toString(), owner.toString(), base.id(), base.configVersion(), base.snapshotVersion(), digest, "PENDING",
                    documents.write(body, decisionContext(owner.toString(), id.toString())), Instant.now()); mapper.insertDecision(existing);
        }
        String status = existing.state().equals("KEPT") ? "EMPTY_KEPT" : "EMPTY_PENDING";
        sources.attemptStatus(owner, UUID.fromString(base.id()), base.configVersion(), base.snapshotVersion(), status, null, Instant.now());
        var outcome = new RefreshOutcome(attempt, UUID.fromString(base.id()), status, UUID.fromString(existing.id()), ChangeSummary.none(), ValidationReport.ok()); finish(owner, outcome); return outcome;
    }
    public EmptyDecisionDO requireDecision(UUID owner, UUID id) { var row = mapper.decision(owner.toString(), id.toString()); if (row == null) { throw ApiException.missing(); } return row; }
    public byte[] candidate(EmptyDecisionDO row) { return documents.read(row.payloadCipher(), decisionContext(row.ownerId(), row.id()), byte[].class); }
    public List<EmptyDecisionView> pending(UUID owner) { return mapper.pending(owner.toString()).stream().map(row -> new EmptyDecisionView(UUID.fromString(row.id()), UUID.fromString(row.sourceId()), Long.toString(row.configVersion()), Long.toString(row.snapshotVersion()), row.state(), row.createTime())).toList(); }
    public void assertPending(UUID owner, UUID decision, SourceDO source) {
        var row = requireDecision(owner, decision);
        if (!row.state().equals("PENDING") || !row.sourceId().equals(source.id()) || row.configVersion() != source.configVersion() || row.snapshotVersion() != source.snapshotVersion()) { throw ApiException.conflict(); }
    }
    @Transactional public DecisionResult keep(UUID owner, UUID id) {
        var row = requireDecision(owner, id); var source = sources.require(owner, UUID.fromString(row.sourceId()));
        revisions.compareAndIncrement(owner, revisions.current(owner)); assertPending(owner, id, source); markDecision(owner, id, "KEPT");
        sources.attemptStatus(owner, UUID.fromString(row.sourceId()), row.configVersion(), row.snapshotVersion(), "EMPTY_KEPT", null, Instant.now());
        return new DecisionResult(id, "KEPT", null);
    }
    @Transactional(propagation = Propagation.MANDATORY) public void markDecision(UUID owner, UUID id, String state) {
        if (mapper.decide(owner.toString(), id.toString(), state) != 1) { throw ApiException.conflict(); }
    }
    public void assertSource(UUID owner, SourceDO expected) {
        var current = sources.require(owner, UUID.fromString(expected.id()));
        if (current.configVersion() != expected.configVersion() || current.snapshotVersion() != expected.snapshotVersion()) { throw ApiException.conflict(); }
    }
    @Transactional(propagation = Propagation.MANDATORY) public void assertRunning(UUID owner, UUID attempt) {
        var now = clock.instant();
        if (mapper.lockRunning(owner.toString(), attempt.toString(), now) == null) {
            var deadline = mapper.deadline(owner.toString(), attempt.toString());
            if (deadline != null && !deadline.isAfter(now)) {
                throw new ApiValidationException(ValidationReport.of(List.of(Diagnostic.error("TASK_TIMEOUT", "source", "任务超过总时限，旧数据保留"))));
            }
            throw ApiException.conflict();
        }
    }
    private String attemptContext(String owner, String id) { return "refresh-attempt:" + owner + ":" + id; }
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> cancelSource(UUID owner, UUID source) {
        var cancelled = new java.util.ArrayList<UUID>();
        for (var row : mapper.activeForSource(owner.toString(), source.toString())) {
            var id = UUID.fromString(row.id());
            var outcome = new RefreshOutcome(id, source, "CANCELLED", null, ChangeSummary.none(), ValidationReport.of(List.of(Diagnostic.warning("SOURCE_REMOVED", "source", "来源已删除，任务已取消"))));
            if (mapper.cancel(owner.toString(), row.id(), documents.write(outcome, attemptContext(owner.toString(), row.id()))) == 1) { cancelled.add(id); }
        }
        return List.copyOf(cancelled);
    }
    private String decisionContext(String owner, String id) { return "empty-decision:" + owner + ":" + id; }
}
