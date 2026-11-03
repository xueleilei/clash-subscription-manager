package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.parse.YamlInputException;
import dev.clashsub.profile.ReferenceIndex;
import dev.clashsub.profile.TargetRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RuleParser {
    public RuleParseResult parse(String text, ReferenceIndex references) {
        var rules = new ArrayList<RuleDefinition>(); var issues = new ArrayList<Diagnostic>();
        if (text == null || text.length() > SafeYaml.MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > SafeYaml.MAX_BYTES) {
            return new RuleParseResult(List.of(), ValidationReport.of(List.of(Diagnostic.error("RULE_INPUT_LIMIT", "rules", "规则文本超过大小限制"))));
        }
        String[] lines = text.split("\\R", -1);
        for (int line = 0; line < lines.length; line++) {
            String raw = lines[line]; String value = raw.strip();
            if (value.isEmpty() || value.startsWith("#")) { continue; }
            if (rules.size() >= 10_000) { issues.add(at("RULE_INPUT_LIMIT", rules.size(), line + 1, "规则最多 10000 条")); break; }
            try {
                if (value.startsWith("- ")) {
                    var parsed = new SafeYaml().load("rules:\n  " + value + "\n").value().path("rules").path(0);
                    if (!parsed.isString()) { throw RuleLexer.invalid(); } value = parsed.asString();
                }
                int first = value.indexOf(',');
                if (first < 1 || value.length() > 65_536) { throw RuleLexer.invalid(); }
                String type = value.substring(0, first).strip().toUpperCase(Locale.ROOT);
                List<String> fields;
                if (RuleGrammar.REGEX.contains(type)) {
                    int last = value.lastIndexOf(',');
                    if (last == first) { throw RuleLexer.invalid(); }
                    fields = List.of(type, value.substring(first + 1, last).strip(), value.substring(last + 1).strip());
                } else { fields = RuleLexer.split(value); }
                boolean match = type.equals("MATCH"); int targetIndex = match ? 1 : 2;
                if (fields.size() <= targetIndex) { throw RuleLexer.invalid(); }
                String payload = match ? "" : fields.get(1);
                var modifiers = List.copyOf(fields.subList(targetIndex + 1, fields.size()));
                RuleGrammar.check(type, payload, modifiers);
                var rowIssues = new ArrayList<Diagnostic>();
                var target = references.resolve(RuleLexer.name(fields.get(targetIndex)));
                if (target.kind() == TargetRef.Kind.UNRESOLVED) { rowIssues.add(at("UNRESOLVED_TARGET", rules.size(), line + 1, "目标名称不存在或有歧义，请重新选择")); }
                UUID provider = null;
                if (type.equals("RULE-SET")) {
                    provider = references.providers().get(RuleLexer.name(payload));
                    if (provider == null) { rowIssues.add(at("MISSING_PROVIDER", rules.size(), line + 1, "规则集不在当前方案中")); }
                }
                rules.add(new RuleDefinition(UUID.randomUUID(), new RuleExpression(type, payload, provider), target, modifiers, raw, rowIssues));
                issues.addAll(rowIssues);
            } catch (RuleSyntaxException | YamlInputException exception) {
                String code = exception instanceof RuleSyntaxException syntax ? syntax.code() : "RULE_SYNTAX";
                var issue = at(code, rules.size(), line + 1, exception.getMessage()); issues.add(issue);
                rules.add(new RuleDefinition(UUID.randomUUID(), new RuleExpression("UNSUPPORTED", "", null), TargetRef.unresolved(""), List.of(), raw, List.of(issue)));
            }
        }
        return new RuleParseResult(rules, ValidationReport.of(issues));
    }
    private Diagnostic at(String code, int index, int line, String message) { return new Diagnostic(Diagnostic.Severity.ERROR, code, "rules[" + index + "]", line, 1, message); }
}
