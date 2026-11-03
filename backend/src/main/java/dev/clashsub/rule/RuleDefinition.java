package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.profile.TargetRef;
import java.util.List;
import java.util.UUID;

public record RuleDefinition(UUID id, RuleExpression expression, TargetRef target, List<String> modifiers,
        String rawText, List<Diagnostic> issues) {
    public RuleDefinition { modifiers = List.copyOf(modifiers); issues = List.copyOf(issues); }
    @Override public String toString() { return "RuleDefinition[id=" + id + "]"; }
}
