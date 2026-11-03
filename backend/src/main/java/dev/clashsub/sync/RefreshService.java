package dev.clashsub.sync;

import dev.clashsub.common.*;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.parse.*;
import dev.clashsub.profile.*;
import dev.clashsub.release.*;
import dev.clashsub.rule.*;
import dev.clashsub.source.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RefreshService {
    private final SourceRepository sources;
    private final NodeRepository nodes;
    private final UpstreamFetcher fetcher;
    private final SubscriptionParser parser;
    private final OwnerSnapshotReader snapshots;
    private final ReleaseService publisher;
    private final RefreshPlanner planner;
    private final RefreshCommitter committer;
    private final RefreshStateStore state;
    private final JsonMapper json;
    public RefreshService(SourceRepository sources, NodeRepository nodes, UpstreamFetcher fetcher, SubscriptionParser parser,
            OwnerSnapshotReader snapshots, ReleaseService publisher, RefreshPlanner planner, RefreshCommitter committer, RefreshStateStore state, JsonMapper json) {
        this.sources = sources; this.nodes = nodes; this.fetcher = fetcher; this.parser = parser; this.snapshots = snapshots;
        this.publisher = publisher; this.planner = planner; this.committer = committer; this.state = state; this.json = json;
    }
    @Transactional(propagation = Propagation.NEVER)
    public RefreshOutcome refresh(UUID owner, UUID sourceId, UUID attemptId) {
        var base = sources.require(owner, sourceId); var completed = state.begin(owner, base, attemptId); if (completed != null) { return completed; }
        try {
            var fetched = fetcher.fetch(sources.request(base));
            if (!fetched.success()) {
                String message = "UPSTREAM_ADDRESS_BLOCKED".equals(fetched.errorCode())
                        ? "上游地址解析到受限网段，已保留旧节点和配置。若使用 Clash / Mihomo 的 Fake-IP，请选择部署者配置的可信出站代理，或让订阅域名返回真实 IP。"
                        : "上游请求失败，已保留旧节点和配置";
                return fail(owner, base, attemptId, "FAILED", ValidationReport.of(List.of(Diagnostic.error(fetched.errorCode() == null ? "UPSTREAM_FAILED" : fetched.errorCode(), "source", message))));
            }
            var result = parser.parse(fetched.body(), InputFormat.valueOf(base.inputFormat()));
            if (result.hasErrors()) { return fail(owner, base, attemptId, "FAILED", new ValidationReport(result.errors(), result.warnings())); }
            if (result.validEmpty()) { return state.pending(owner, base, attemptId, fetched.body()); }
            return applyCandidate(owner, base, attemptId, fetched.body(), result.nodes(), null);
        } catch (ApiValidationException exception) { return fail(owner, base, attemptId, "FAILED", exception.report()); }
        catch (ApiException exception) {
            if (exception.status() != 409 && exception.status() != 404) { throw exception; }
            return fail(owner, base, attemptId, "CONFLICT", ValidationReport.of(List.of(Diagnostic.error("REFRESH_CONFLICT", "source", "来源或配置已变化，本次候选未提交，请重新刷新"))));
        } catch (RuntimeException exception) {
            // SQL/parser exceptions can contain credentials; persist only a fixed public category.
            return fail(owner, base, attemptId, "FAILED", ValidationReport.of(List.of(Diagnostic.error("REFRESH_INTERNAL", "source", "刷新未完成，已保留旧节点和配置"))));
        }
    }
    @Transactional(propagation = Propagation.NEVER)
    public RefreshOutcome clear(UUID owner, UUID decisionId) {
        var decision = state.requireDecision(owner, decisionId); var source = sources.require(owner, UUID.fromString(decision.sourceId()));
        state.assertPending(owner, decisionId, source); UUID attempt = UUID.randomUUID(); state.begin(owner, source, attempt);
        try { return applyCandidate(owner, source, attempt, state.candidate(decision), List.of(), decisionId); }
        catch (ApiValidationException exception) { fail(owner, source, attempt, "FAILED", exception.report()); throw exception; }
        catch (RuntimeException exception) {
            fail(owner, source, attempt, exception instanceof ApiException api && api.status() == 409 ? "CONFLICT" : "FAILED",
                    ValidationReport.of(List.of(Diagnostic.error("CLEAR_FAILED", "source", "清空未提交，旧数据和待处理事项已保留")))); throw exception;
        }
    }
    private RefreshOutcome applyCandidate(UUID owner, SourceDO source, UUID attempt, byte[] body, List<ParsedNode> parsed, UUID decision) {
        UUID sourceId = UUID.fromString(source.id());
        for (int retry = 0; retry < 3; retry++) {
            var before = snapshots.read(owner); var captured = before.sources().get(sourceId);
            if (captured == null || captured.configVersion() != source.configVersion() || captured.snapshotVersion() != source.snapshotVersion()) { throw ApiException.conflict(); }
            if (decision != null) { state.assertPending(owner, decision, source); }
            var diff = NodeDiff.calculate(sourceId, parsed, nodes.sourceHistory(owner, sourceId), before.nodes());
            var versions = new LinkedHashMap<>(before.sources());
            versions.put(sourceId, new SourceState(sourceId, captured.configVersion(), captured.snapshotVersion() + (diff.changed() || captured.snapshotVersion() == 0 ? 1 : 0), captured.name()));
            var candidate = new OwnerSnapshot(owner, before.dataRevision(), versions, diff.catalog(), before.profiles());
            if (!parsed.isEmpty()) { validateEveryNode(sourceId, diff, candidate); }
            var proposal = planner.plan(before, candidate, source, diff, attempt, decision, body, false);
            try { return committer.commit(proposal).outcome(); }
            catch (ApiException exception) { if (exception.status() != 409 || retry == 2) { throw exception; } }
        }
        throw ApiException.conflict();
    }
    private void validateEveryNode(UUID source, NodeDiff diff, OwnerSnapshot candidate) {
        UUID groupId = UUID.randomUUID();
        var group = new GroupDefinition(groupId, "候选节点校验", "select", diff.replacement().stream().map(node -> TargetRef.node(node.id())).toList(), json.createObjectNode());
        var rule = new RuleDefinition(UUID.randomUUID(), new RuleExpression("MATCH", "", null), TargetRef.group(groupId), List.of(), "", List.of());
        var document = new ProfileDocument(1, "mode: rule\n", List.of(group), List.of(rule), List.of(), List.of());
        var prepared = publisher.prepare(source, document, candidate);
        if (!prepared.valid()) { throw new ApiValidationException(prepared.validation()); }
    }
    private RefreshOutcome fail(UUID owner, SourceDO source, UUID attempt, String status, ValidationReport report) {
        return state.failure(owner, source, new RefreshOutcome(attempt, UUID.fromString(source.id()), status, null, ChangeSummary.none(), report));
    }
}
