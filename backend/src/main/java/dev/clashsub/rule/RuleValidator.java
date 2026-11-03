package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.common.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleValidator {
    public ValidationReport validate(List<RuleDefinition> rules) {
        var issues = new ArrayList<Diagnostic>(); int matchCount = 0;
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i); String path = "rules[" + i + "]";
            issues.addAll(rule.issues());
            if (rule.expression() == null) { issues.add(Diagnostic.error("MISSING_EXPRESSION", path, "规则表达式缺失")); continue; }
            var expression = rule.expression();
            if ("MATCH".equals(expression.type())) {
                matchCount++; if (i != rules.size() - 1) { issues.add(Diagnostic.error("MATCH_NOT_LAST", path, "MATCH 必须位于规则列表末尾")); }
            }
            try {
                RuleGrammar.check(expression.type(), "RULE-SET".equals(expression.type()) && expression.providerId() != null ? "managed-provider" : expression.value(), rule.modifiers());
                if ("RULE-SET".equals(expression.type()) != (expression.providerId() != null)) {
                    issues.add(Diagnostic.error("INVALID_PROVIDER_REFERENCE", path + ".expression", "规则集引用结构不正确"));
                }
            } catch (RuleSyntaxException exception) { issues.add(Diagnostic.error(exception.code(), path + ".expression", exception.getMessage())); }
        }
        if (matchCount != 1) { issues.add(Diagnostic.error("MATCH_COUNT", "rules", "规则列表必须且只能包含一个末尾 MATCH")); }
        return ValidationReport.of(issues);
    }
}
