package dev.codei18n.idea;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, conservative reverse index of the upstream ID -> language -> text format. */
public final class MappingIndex {
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private final String language;
    private final Map<String, String> translations;

    private MappingIndex(String language, Map<String, String> translations) {
        this.language = language;
        this.translations = Map.copyOf(translations);
    }

    public static MappingIndex parse(String mappings, String config, String languageOverride) {
        JsonObject data = object(JsonParser.parseString(stripBom(mappings)), "mappings");
        JsonObject settings = object(JsonParser.parseString(stripBom(config)), "config");
        String source = value(settings, "sourceLanguage", value(data, "sourceLanguage", "en"));
        String target = languageOverride.isBlank()
                ? value(settings, "localLanguage", value(data, "targetLanguage", "zh-CN"))
                : languageOverride.trim();
        Map<String, String> values = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        JsonElement comments = data.get("comments");
        if (comments != null && !comments.isJsonNull()) {
            for (var entry : object(comments, "comments").entrySet()) {
                JsonObject languages = object(entry.getValue(), "comment " + entry.getKey());
                String original = value(languages, source, "");
                if (original.isBlank()) continue;
                String key = normalize(original);
                String translated = value(languages, target, "");
                // A missing translation on one duplicate is also ambiguous.
                String previous = values.putIfAbsent(key, translated);
                if (previous != null && !previous.equals(translated)) ambiguous.add(key);
            }
        }
        ambiguous.forEach(values::remove);
        values.entrySet().removeIf(e -> e.getKey().isBlank() || e.getValue().isBlank()
                || normalize(e.getValue()).equals(e.getKey()));
        return new MappingIndex(target, values);
    }

    public String language() { return language; }
    public String translation(String comment) { return translations.get(normalize(comment)); }

    // Deliberately matches core/utils/id.go. Do not strip Javadoc stars: upstream keeps them.
    public static String normalize(String text) {
        String value = text.strip();
        if (value.startsWith("//")) value = value.substring(2);
        if (value.startsWith("/*")) value = value.substring(2);
        if (value.endsWith("*/")) value = value.substring(0, value.length() - 2);
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static JsonObject object(JsonElement element, String label) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(label + " must be a JSON object");
        return element.getAsJsonObject();
    }

    private static String value(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString().isBlank() ? fallback : value.getAsString();
    }
}
