package dev.clashsub.rule;

import java.util.ArrayList;
import java.util.List;

/** Comments and blank lines belong to the adjacent rule, never to its executable expression. */
public record RuleTrivia(List<String> leading, String inline, List<String> trailing) {
    public static final RuleTrivia EMPTY = new RuleTrivia(List.of(), "", List.of());

    public RuleTrivia {
        leading = leading == null ? List.of() : List.copyOf(leading);
        trailing = trailing == null ? List.of() : List.copyOf(trailing);
        inline = inline == null ? "" : inline;
        if (!leading.stream().allMatch(RuleTrivia::commentOrBlank) || !trailing.stream().allMatch(RuleTrivia::commentOrBlank)
                || !commentOrBlank(inline)) { throw new IllegalArgumentException("规则注释必须为单行注释或空白"); }
        if (!inline.isBlank() && !Character.isWhitespace(inline.charAt(0))) { inline = " " + inline; }
    }

    private static boolean commentOrBlank(String value) {
        return value != null && value.codePoints().noneMatch(c -> c < 32 && c != '\t' || c == 127 || c == 0x85 || c == 0x2028 || c == 0x2029)
                && (value.isBlank() || value.stripLeading().startsWith("#"));
    }

    public String render(String rule) {
        var lines = new ArrayList<>(leading); lines.add(rule + inline); lines.addAll(trailing);
        return String.join("\n", lines);
    }

    @Override public String toString() { return "RuleTrivia[redacted]"; }
}
