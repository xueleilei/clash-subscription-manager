package dev.clashsub.rule;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.profile.TargetRef;
import java.util.List;
import java.util.UUID;

public record RuleDefinition(UUID id, RuleExpression expression, TargetRef target, List<String> modifiers,
        String rawText, List<Diagnostic> issues, RuleTrivia trivia) {
    public RuleDefinition {
        modifiers = List.copyOf(modifiers); issues = List.copyOf(issues);
        trivia = trivia == null ? RuleTrivia.EMPTY : trivia;
    }
    public RuleDefinition(UUID id, RuleExpression expression, TargetRef target, List<String> modifiers, String rawText, List<Diagnostic> issues) {
        this(id, expression, target, modifiers, rawText, issues, RuleTrivia.EMPTY);
    }
    public RuleDefinition withTrivia(RuleTrivia value) { return new RuleDefinition(id, expression, target, modifiers, rawText, issues, value); }
    @Override public String toString() { return "RuleDefinition[id=" + id + "]"; }
}
