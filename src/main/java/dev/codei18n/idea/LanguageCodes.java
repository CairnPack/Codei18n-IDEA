package dev.codei18n.idea;

import java.util.Locale;
import java.util.Map;

/**
 * BCP 47 canonicalization and equivalence, identical to core/langcode (docs/index-format.md §8).
 * Both sides run the shared vectors in testdata/language-codes.json.
 */
public final class LanguageCodes {
    public static final String UNKNOWN = "unknown";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("zho", "zh"), Map.entry("chi", "zh"), Map.entry("cmn", "zh"),
            Map.entry("eng", "en"), Map.entry("jpn", "ja"), Map.entry("kor", "ko"),
            Map.entry("deu", "de"), Map.entry("ger", "de"), Map.entry("fra", "fr"), Map.entry("fre", "fr"),
            Map.entry("spa", "es"), Map.entry("rus", "ru"),
            Map.entry("iw", "he"), Map.entry("in", "id"), Map.entry("ji", "yi"));

    private LanguageCodes() {}

    /** zh-Hans / zh-Hant for Chinese, else the lowercase primary subtag; "" stays "", und/unknown become "unknown". */
    public static String canonical(String code) {
        if (code == null) return "";
        String trimmed = CommentText.trim(code);
        if (trimmed.isEmpty()) return "";
        String[] parts = trimmed.replace('_', '-').split("-");
        String lang = parts[0].toLowerCase(Locale.ROOT);
        if (lang.equals("und") || lang.equals(UNKNOWN)) return UNKNOWN;
        lang = ALIASES.getOrDefault(lang, lang);
        if (!lang.equals("zh")) return lang;
        for (int i = 1; i < parts.length; i++) {
            switch (parts[i].toLowerCase(Locale.ROOT)) {
                case "hant", "tw", "hk", "mo" -> { return "zh-Hant"; }
                default -> { }
            }
        }
        return "zh-Hans";
    }

    /** Both non-empty and canonically equal. */
    public static boolean equivalent(String a, String b) {
        String ca = canonical(a);
        return !ca.isEmpty() && ca.equals(canonical(b));
    }

    /** The key for {@code code} in {@code map}: the exact spelling first, then an equivalent one; else null. */
    public static String findKey(Map<String, ?> map, String code) {
        if (map == null || code == null) return null;
        if (map.containsKey(code)) return code;
        for (String key : map.keySet()) if (equivalent(key, code)) return key;
        return null;
    }
}
