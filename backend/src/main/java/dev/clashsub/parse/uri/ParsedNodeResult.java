package dev.clashsub.parse.uri;

import dev.clashsub.common.Diagnostic;
import dev.clashsub.parse.ParsedNode;
import java.util.List;

public record ParsedNodeResult(ParsedNode node, List<Diagnostic> errors) {
    public ParsedNodeResult { errors = List.copyOf(errors); if (!errors.isEmpty()) { node = null; } }
    @Override public String toString() { return "ParsedNodeResult[errors=" + errors.size() + "]"; }
}
