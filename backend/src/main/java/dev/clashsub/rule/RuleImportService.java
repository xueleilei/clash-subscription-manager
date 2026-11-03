package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeCatalog;
import dev.clashsub.profile.DraftSnapshot;
import dev.clashsub.profile.ReferenceIndex;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RuleImportService {
    private final RuleParser parser;
    public RuleImportService(RuleParser parser) { this.parser = parser; }
    public RuleImportPreview preview(DraftSnapshot draft, String text, MatchPolicy policy) { return preview(draft, text, policy, NodeCatalog.empty()); }
    public RuleImportPreview preview(DraftSnapshot draft, String text, MatchPolicy policy, NodeCatalog catalog) {
        var parsed = parser.parse(text, ReferenceIndex.of(draft.document(), catalog)); var report = parsed.report();
        long incomingMatch = parsed.rules().stream().filter(this::match).count();
        if (parsed.rules().isEmpty()) { report = report.merge(error("EMPTY_RULE_IMPORT", "请输入要导入的规则")); }
        if (incomingMatch > 0 && policy != MatchPolicy.REPLACE) { report = report.merge(error("MATCH_REPLACE_REQUIRED", "导入 MATCH 需要明确选择替换原兜底规则")); }
        for (int i = 0; i < parsed.rules().size(); i++) {
            if (match(parsed.rules().get(i)) && i != parsed.rules().size() - 1) { report = report.merge(error("MATCH_NOT_LAST", "导入的 MATCH 必须位于最后一行")); }
        }
        if (incomingMatch > 1) { report = report.merge(error("MATCH_COUNT", "不能导入多个 MATCH")); }
        var candidate = new ArrayList<>(draft.document().rules().stream().filter(rule -> !match(rule)).toList());
        candidate.addAll(parsed.rules().stream().filter(rule -> !match(rule)).toList());
        candidate.addAll(incomingMatch > 0 ? parsed.rules().stream().filter(this::match).map(rule -> preserveFallbackNotes(draft.document().rules(), rule)).toList()
                : draft.document().rules().stream().filter(this::match).toList());
        report = report.merge(new RuleValidator().validate(candidate));
        if (candidate.size() > 10_000) { report = report.merge(error("RULE_INPUT_LIMIT", "合并后最多保留 10000 条规则")); }
        return new RuleImportPreview(report.valid(), Long.toString(draft.version()), report.valid() ? candidate : draft.document().rules(), parsed.rules(), report);
    }
    private boolean match(RuleDefinition rule) { return rule.expression() != null && "MATCH".equals(rule.expression().type()); }
    private RuleDefinition preserveFallbackNotes(List<RuleDefinition> existing, RuleDefinition incoming) {
        var leading = new ArrayList<String>(); var trailing = new ArrayList<>(incoming.trivia().trailing());
        existing.stream().filter(this::match).forEach(rule -> {
            leading.addAll(rule.trivia().leading());
            if (!rule.trivia().inline().isBlank()) { leading.add(rule.trivia().inline().stripLeading()); }
            trailing.addAll(rule.trivia().trailing());
        });
        leading.addAll(incoming.trivia().leading());
        return incoming.withTrivia(new RuleTrivia(leading, incoming.trivia().inline(), trailing));
    }
    private ValidationReport error(String code, String message) { return ValidationReport.of(List.of(Diagnostic.error(code, "rules", message))); }
}
