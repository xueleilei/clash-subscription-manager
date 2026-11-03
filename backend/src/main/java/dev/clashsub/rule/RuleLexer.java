package dev.clashsub.rule;

import java.util.ArrayList;
import java.util.List;

/** Keeps commas inside balanced expressions or quoted text intact. */
final class RuleLexer {
    private RuleLexer() { }
    static int commentStart(String text) {
        char quote = 0; boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i++; }
                    else { quote = 0; }
                }
            } else if (c == '\'' || c == '"') { quote = c; }
            else if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) { return i; }
        }
        return -1;
    }
    static List<String> split(String text) {
        var result = new ArrayList<String>(); int start = 0, depth = 0; char quote = 0; boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i++; }
                    else { quote = 0; }
                }
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; }
            else if (c == '(') { if (++depth > 64) { throw invalid(); } }
            else if (c == ')') { if (--depth < 0) { throw invalid(); } }
            else if (c == ',' && depth == 0) { result.add(text.substring(start, i).strip()); start = i + 1; }
        }
        if (quote != 0 || depth != 0) { throw invalid(); }
        result.add(text.substring(start).strip()); return List.copyOf(result);
    }
    static String name(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(0) == text.charAt(text.length() - 1)) {
            char quote = text.charAt(0); return text.substring(1, text.length() - 1).replace("" + quote + quote, "" + quote);
        }
        return text;
    }
    static RuleSyntaxException invalid() { return new RuleSyntaxException("RULE_SYNTAX", "规则的逗号、括号或引号结构不正确"); }
}
