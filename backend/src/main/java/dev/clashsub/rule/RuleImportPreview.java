package dev.clashsub.rule;

import dev.clashsub.common.ValidationReport;
import java.util.List;

public record RuleImportPreview(boolean applicable, String expectedVersion, List<RuleDefinition> rules,
        List<RuleDefinition> importedRules, ValidationReport report) {
    public RuleImportPreview { rules = List.copyOf(rules); importedRules = List.copyOf(importedRules); }
}
