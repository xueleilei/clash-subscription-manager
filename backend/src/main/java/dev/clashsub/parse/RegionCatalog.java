package dev.clashsub.parse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** One immutable catalog supplies both the UI options and name-only recognition. */
public final class RegionCatalog {
    public static final String UNKNOWN = "UNKNOWN";
    public record Option(String code, String name) { }
    public record Group(String name, List<Option> regions) {
        public Group { regions = List.copyOf(regions); }
    }
    private static final Pattern FLAG = Pattern.compile("[\\x{1F1E6}-\\x{1F1FF}]{2}");
    private static final Pattern CODE = Pattern.compile("[A-Z]{2}");
    private static final Pattern SHORT_ALIAS = Pattern.compile("[a-z]{2,3}");
    private static final Pattern NODE_NUMBER = Pattern.compile("[\\s_#.-]*[0-9]");
    private static final int FLAG_A = 0x1F1E6;
    private final List<Group> groups;
    private final Map<String, String> names, abbreviations;
    private final Set<String> codes, strictCodes;
    private final Pattern namePattern, abbreviationPattern;

    private RegionCatalog(List<Group> groups, Set<String> codes, Set<String> strictCodes, Map<String, String> terms) {
        this.groups = List.copyOf(groups); this.codes = Set.copyOf(codes); this.strictCodes = Set.copyOf(strictCodes);
        var words = new HashMap<String, String>();
        var shortNames = new HashMap<String, String>();
        terms.forEach((term, code) -> (SHORT_ALIAS.matcher(term).matches() ? shortNames : words).put(term, code));
        names = Map.copyOf(words); abbreviations = Map.copyOf(shortNames);
        namePattern = pattern(names); abbreviationPattern = pattern(abbreviations);
    }
    public List<Group> groups() { return groups; }

    public static RegionCatalog fromYaml(String yaml) {
        var root = new SafeYaml().load(yaml).value();
        fields(root, Set.of("groups"), "$");
        var entries = root.path("groups");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > 64) { throw invalid("groups 需为含 1 至 64 项的列表"); }
        var groups = new ArrayList<Group>(); var groupNames = new HashSet<String>();
        var terms = new HashMap<String, String>(); var codes = new HashSet<String>(); var strictCodes = new HashSet<String>();
        for (var entry : entries) {
            fields(entry, Set.of("name", "regions"), "groups");
            var name = text(entry.path("name"), "分组名称");
            var rows = entry.path("regions");
            if (!groupNames.add(name) || !rows.isArray() || rows.isEmpty()) { throw invalid("分组重复或 regions 列表为空：" + name); }
            var options = new ArrayList<Option>();
            for (var row : rows) {
                fields(row, Set.of("code", "name", "aliases", "strictCode"), name);
                var code = text(row.path("code"), name + ".code");
                if (!CODE.matcher(code).matches() || !codes.add(code)) { throw invalid("代码必须是唯一的两位大写字母：" + code); }
                var label = text(row.path("name"), code + ".name");
                if (row.has("strictCode")) {
                    if (!row.path("strictCode").isBoolean()) { throw invalid(code + ".strictCode 需为布尔值"); }
                    if (row.path("strictCode").asBoolean()) { strictCodes.add(code); }
                }
                options.add(new Option(code, label)); addTerm(terms, code, code); addTerm(terms, label, code);
                if (row.has("aliases")) {
                    var aliases = row.path("aliases");
                    if (!aliases.isArray() || aliases.size() > 64) { throw invalid(code + ".aliases 需为最多 64 项的列表"); }
                    for (var alias : aliases) { addTerm(terms, text(alias, code + ".aliases"), code); }
                }
            }
            groups.add(new Group(name, options));
        }
        return new RegionCatalog(groups, codes, strictCodes, terms);
    }

    public String fromName(String name) {
        if (name == null || name.isBlank()) { return UNKNOWN; }
        String text = Normalizer.normalize(name.strip(), Normalizer.Form.NFKC);
        var flags = FLAG.matcher(text);
        while (flags.find()) {
            String flag = flags.group();
            String code = "" + (char) ('A' + flag.codePointAt(0) - FLAG_A) + (char) ('A' + flag.codePointAt(2) - FLAG_A);
            if (codes.contains(code)) { return code; }
        }
        var words = namePattern.matcher(text.toLowerCase(Locale.ROOT));
        if (words.find()) { return names.get(words.group()); }
        var shortNames = abbreviationPattern.matcher(text);
        while (shortNames.find()) {
            String token = shortNames.group();
            String code = abbreviations.get(fold(token));
            // Lowercase English words such as "in", "no" or "my" are not location hints in prose.
            if (!strictCodes.contains(code) || token.equals(token.toUpperCase(Locale.ROOT)) || token.length() == text.length()
                    || NODE_NUMBER.matcher(text).region(shortNames.end(), text.length()).lookingAt()) {
                return code;
            }
        }
        return UNKNOWN;
    }
    private static Pattern pattern(Map<String, String> terms) {
        if (terms.isEmpty()) { return Pattern.compile("(?!)"); }
        String alternatives = terms.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(term -> (latin(term.codePointAt(0)) ? "(?<![\\p{IsLatin}])" : "") + Pattern.quote(term)
                        + (latin(term.codePointBefore(term.length())) ? "(?![\\p{IsLatin}])" : "")).collect(Collectors.joining("|"));
        return Pattern.compile(alternatives, Pattern.CASE_INSENSITIVE);
    }
    private static boolean latin(int codePoint) { return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN; }
    private static String fold(String term) { return Normalizer.normalize(term, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static void addTerm(Map<String, String> terms, String term, String code) {
        String previous = terms.putIfAbsent(fold(term), code);
        if (previous != null && !previous.equals(code)) { throw invalid("名称或别名同时指向 " + previous + " 和 " + code + "：" + term); }
    }
    private static String text(JsonNode node, String field) {
        if (!node.isString() || node.asString().isBlank() || node.asString().length() > 100
                || node.asString().codePoints().anyMatch(Character::isISOControl)) { throw invalid(field + " 需为 1 至 100 字符的文本"); }
        return node.asString().strip();
    }
    private static void fields(JsonNode node, Set<String> allowed, String field) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames())) { throw invalid(field + " 含未知字段或格式错误"); }
    }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("节点地区配置无效：" + reason); }
}
