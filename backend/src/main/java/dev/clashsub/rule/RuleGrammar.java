package dev.clashsub.rule;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RuleGrammar {
    static final Set<String> LOGICAL = Set.of("AND", "OR", "NOT");
    static final Set<String> REGEX = Set.of("DOMAIN-REGEX", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX");
    private static final Set<String> SIMPLE = Set.of("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-WILDCARD",
            "PROCESS-NAME", "PROCESS-PATH", "PROCESS-NAME-WILDCARD", "PROCESS-PATH-WILDCARD", "GEOSITE", "GEOIP", "SRC-GEOIP",
            "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "IP-ASN", "SRC-IP-ASN", "IP-SUFFIX", "SRC-IP-SUFFIX", "DST-PORT", "SRC-PORT", "IN-PORT",
            "NETWORK", "UID", "IN-TYPE", "IN-USER", "IN-NAME", "DSCP", "RULE-SET", "MATCH");
    private static final Set<String> WITH_PARAMS = Set.of("IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "GEOIP", "IP-ASN", "RULE-SET");
    private RuleGrammar() { }

    static void check(String type, String value, List<String> modifiers) { check(type, value, modifiers, 0); }
    private static void check(String type, String value, List<String> modifiers, int depth) {
        if (type == null || (!SIMPLE.contains(type) && !LOGICAL.contains(type) && !REGEX.contains(type))) {
            throw new RuleSyntaxException("UNSUPPORTED_RULE", "此规则类型尚不支持，请保留草稿并调整后发布");
        }
        if (depth > 32 || value == null || value.length() > 65_536 || value.chars().anyMatch(c -> c < 32 || c == 127)) { throw RuleLexer.invalid(); }
        if (type.equals("MATCH")) {
            if (!value.isEmpty() || !modifiers.isEmpty()) { throw RuleLexer.invalid(); }
            return;
        }
        if (value.isBlank()) { throw new RuleSyntaxException("EMPTY_RULE_VALUE", "规则匹配内容不能为空"); }
        if (!modifiers.isEmpty() && (!WITH_PARAMS.contains(type) || modifiers.stream().anyMatch(item -> !Set.of("no-resolve", "src").contains(item))
                || new HashSet<>(modifiers).size() != modifiers.size())) {
            throw new RuleSyntaxException("UNSUPPORTED_RULE_MODIFIER", "此规则的附加参数不受支持");
        }
        if (LOGICAL.contains(type)) { logic(type, value, depth); return; }
        if (REGEX.contains(type)) { return; }
        if (value.contains(",")) { throw new RuleSyntaxException("UNSUPPORTED_QUOTED_PAYLOAD", "此规则类型的匹配内容不能包含逗号"); }
        if (Set.of("IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "IP-SUFFIX", "SRC-IP-SUFFIX").contains(type)) { network(value, type); }
        if (type.equals("NETWORK") && !Set.of("TCP", "UDP").contains(value.toUpperCase(Locale.ROOT))) { throw RuleLexer.invalid(); }
        if (Set.of("SRC-PORT", "DST-PORT", "IN-PORT").contains(type)) { ports(value); }
        if (Set.of("IP-ASN", "SRC-IP-ASN", "UID", "DSCP").contains(type) && !value.matches("[0-9]+(?:-[0-9]+)?(?:/[0-9]+(?:-[0-9]+)?)*")) { throw RuleLexer.invalid(); }
    }

    private static void logic(String type, String value, int depth) {
        if (!value.startsWith("(") || !value.endsWith(")")) { throw RuleLexer.invalid(); }
        var children = RuleLexer.split(value.substring(1, value.length() - 1));
        if (children.isEmpty() || type.equals("NOT") && children.size() != 1) { throw RuleLexer.invalid(); }
        for (String child : children) {
            if (!child.startsWith("(") || !child.endsWith(")")) { throw RuleLexer.invalid(); }
            var fields = RuleLexer.split(child.substring(1, child.length() - 1));
            if (fields.size() < 2) { throw RuleLexer.invalid(); }
            String childType = fields.getFirst().toUpperCase(Locale.ROOT);
            if (Set.of("RULE-SET", "SUB-RULE").contains(childType)) {
                throw new RuleSyntaxException("UNSUPPORTED_NESTED_REFERENCE", "逻辑表达式中的规则集或子规则引用尚不能安全改写，请拆为独立规则");
            }
            if (childType.equals("MATCH")) { throw RuleLexer.invalid(); }
            check(childType, fields.get(1), fields.subList(2, fields.size()), depth + 1);
        }
    }
    private static void network(String value, String type) {
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F:.]+") || !parts[1].matches("[0-9]{1,3}")) { throw RuleLexer.invalid(); }
        try {
            byte[] address = InetAddress.getByName(parts[0]).getAddress(); int bits = Integer.parseInt(parts[1]);
            if (bits > address.length * 8 || type.equals("IP-CIDR6") && !parts[0].contains(":")) { throw RuleLexer.invalid(); }
        } catch (UnknownHostException exception) { throw RuleLexer.invalid(); }
    }
    private static void ports(String value) {
        for (String item : value.split("/", -1)) {
            if (!item.matches("[0-9]{1,5}(?:-[0-9]{1,5})?")) { throw RuleLexer.invalid(); }
            var range = item.split("-"); int start = Integer.parseInt(range[0]), end = range.length == 1 ? start : Integer.parseInt(range[1]);
            if (start < 0 || end > 65_535 || start > end) { throw RuleLexer.invalid(); }
        }
    }
}
