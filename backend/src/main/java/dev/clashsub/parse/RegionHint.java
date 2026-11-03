package dev.clashsub.parse;

import java.util.List;
import java.util.regex.Pattern;

public final class RegionHint {
    private record Hint(String code, Pattern pattern) { }
    private static final List<Hint> HINTS = List.of(
            hint("HK", "🇭🇰|香港|Hong.?Kong|\\bHK\\b"), hint("TW", "🇹🇼|台湾|台灣|Taiwan|\\bTW\\b"),
            hint("JP", "🇯🇵|日本|东京|東京|Japan|\\bJP\\b"), hint("SG", "🇸🇬|新加坡|狮城|Singapore|\\bSG\\b"),
            hint("US", "🇺🇸|美国|美國|United.?States|\\bUS(A)?\\b"), hint("KR", "🇰🇷|韩国|韓國|Korea|\\bKR\\b"),
            hint("DE", "🇩🇪|德国|德國|Germany|\\bDE\\b"), hint("GB", "🇬🇧|英国|英國|Britain|\\b(UK|GB)\\b"));
    private RegionHint() { }
    private static Hint hint(String code, String regex) { return new Hint(code, Pattern.compile(regex, Pattern.CASE_INSENSITIVE)); }
    public static String fromName(String name) {
        return HINTS.stream().filter(hint -> hint.pattern().matcher(name).find()).map(Hint::code).findFirst().orElse("UNKNOWN");
    }
}
