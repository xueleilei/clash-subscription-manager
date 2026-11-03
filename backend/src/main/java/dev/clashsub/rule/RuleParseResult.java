package dev.clashsub.rule;

import dev.clashsub.common.ValidationReport;
import java.util.List;

public record RuleParseResult(List<RuleDefinition> rules, ValidationReport report) {
    public RuleParseResult { rules = List.copyOf(rules); }
}
