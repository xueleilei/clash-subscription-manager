package dev.clashsub.parse;

import dev.clashsub.common.Diagnostic;
import java.util.List;

public record ParseResult(List<ParsedNode> nodes, List<Diagnostic> errors, List<Diagnostic> warnings, boolean validEmpty) {
    public ParseResult {
        errors = List.copyOf(errors); warnings = List.copyOf(warnings);
        nodes = errors.isEmpty() ? List.copyOf(nodes) : List.of();
        validEmpty = errors.isEmpty() && nodes.isEmpty() && validEmpty;
    }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean committable() { return errors.isEmpty(); }
    public static ParseResult failure(Diagnostic diagnostic) { return new ParseResult(List.of(), List.of(diagnostic), List.of(), false); }
    @Override public String toString() { return "ParseResult[nodes=" + nodes.size() + ", errors=" + errors.size() + "]"; }
}
