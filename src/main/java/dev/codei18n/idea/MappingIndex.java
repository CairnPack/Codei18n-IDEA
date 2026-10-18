package dev.codei18n.idea;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view of one CodeI18n root: mappings.json (ID → language → text), config.json and, when present,
 * index.json. Matching priority (docs/index-format.md §9):
 * <ol>
 *   <li>index.json, file content unchanged: the entry starting at the comment's position;</li>
 *   <li>index.json, file changed: an entry of the same file with the same textHash and parts;</li>
 *   <li>conservative global text match (a text with conflicting translations is never shown).</li>
 * </ol>
 * Without index.json only the global text match applies, as in earlier versions.
 */
public final class MappingIndex {
    /** The file being matched: path relative to the root ('/'), SHA-1 of the saved bytes (null if unsaved), text. */
    public record FileContext(String relativePath, String contentHash, String text) {}

    /** A translation for document range [start, end); {@code type} is the index comment type (line/block/doc). */
    public record Located(int start, int end, String text, String type) {}

    /** Result of matching one comment group: whether a mapping entry was identified, and the text to show (may be null). */
    public record Match(boolean found, String text) {
        static final Match NONE = new Match(false, null);
    }

    private final String language;
    private final Map<String, Map<String, String>> comments;
    private final Map<String, String> global;
    private final LocationIndex locations;

    private MappingIndex(String language, Map<String, Map<String, String>> comments, Map<String, String> global,
                         LocationIndex locations) {
        this.language = language;
        this.comments = comments;
        this.global = Map.copyOf(global);
        this.locations = locations;
    }

    public static MappingIndex parse(String mappings, String config, String languageOverride) {
        return parse(mappings, config, null, languageOverride);
    }

    /** {@code index} may be null (no index.json); an unsupported index version is ignored. */
    public static MappingIndex parse(String mappings, String config, String index, String languageOverride) {
        JsonObject data = object(JsonParser.parseString(stripBom(mappings)), "mappings");
        JsonObject settings = object(JsonParser.parseString(stripBom(config)), "config");
        String target = languageOverride.isBlank()
                ? value(settings, "localLanguage", value(data, "targetLanguage", "zh-CN"))
                : languageOverride.trim();
        Map<String, Map<String, String>> comments = new HashMap<>();
        JsonElement element = data.get("comments");
        if (element != null && !element.isJsonNull()) {
            for (var entry : object(element, "comments").entrySet()) {
                JsonObject languages = object(entry.getValue(), "comment " + entry.getKey());
                Map<String, String> texts = new HashMap<>();
                for (var lang : languages.entrySet()) texts.put(lang.getKey(), value(languages, lang.getKey(), ""));
                comments.put(entry.getKey(), Map.copyOf(texts));
            }
        }
        LocationIndex locations = index == null ? null : LocationIndex.parse(index);
        return new MappingIndex(target, Map.copyOf(comments), globalIndex(comments, target), locations);
    }

    /**
     * Reverse index for the global fallback. Any language other than the display language may be the original
     * (comments written in the local language are stored under the local key, C1.2); a text whose duplicates disagree,
     * including a missing translation on one of them, is ambiguous and never shown.
     */
    private static Map<String, String> globalIndex(Map<String, Map<String, String>> comments, String target) {
        Map<String, String> values = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map<String, String> texts : comments.values()) {
            String key0 = LanguageCodes.findKey(texts, target);
            String translated = key0 == null ? "" : texts.get(key0);
            for (var original : texts.entrySet()) {
                if (original.getValue().isBlank() || LanguageCodes.equivalent(original.getKey(), target)) continue;
                String key = CommentText.legacy(original.getValue());
                String previous = values.putIfAbsent(key, translated);
                if (previous != null && !previous.equals(translated)) ambiguous.add(key);
            }
        }
        ambiguous.forEach(values::remove);
        values.entrySet().removeIf(e -> e.getKey().isBlank() || e.getValue().isBlank()
                || CommentText.legacy(e.getValue()).equals(e.getKey()));
        return values;
    }

    /** The display language. */
    public String language() { return language; }

    public boolean hasLocations() { return locations != null; }

    /** Global text match only (legacy behaviour). */
    public String translation(String comment) { return global.get(CommentText.legacy(comment)); }

    /**
     * Matches the comment group occupying [start, end) of {@code file.text()} and made of {@code parts} physical
     * comments. An identified entry whose original is already in the display language yields found=true, text=null.
     */
    public Match match(FileContext file, int start, int end, int parts) {
        return match(file, start, end, parts, -1, -1);
    }

    /**
     * Like {@link #match(FileContext, int, int, int)}; {@code rank} is this group's 0-based position among the groups
     * of the file with the same textHash and parts, {@code total} their count (-1 when unknown). When the file changed
     * and the indexed entries with that hash are exactly as many, they are paired by order, which survives shifted lines.
     */
    public Match match(FileContext file, int start, int end, int parts, int rank, int total) {
        LocationIndex.FileEntries entries = locations == null ? null : locations.file(file.relativePath());
        if (entries != null) {
            LocationIndex.Entry entry = null;
            if (file.contentHash() != null && file.contentHash().equals(entries.contentHash())) {
                int[] starts = CommentGroups.lineStarts(file.text());
                int line = CommentGroups.line(starts, start) + 1, col = CommentGroups.column(starts, start) + 1;
                for (LocationIndex.Entry e : entries.comments()) {
                    if (e.range().startLine() == line && e.range().startCol() == col && e.parts() == parts) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) entry = byTextHash(entries, file.text(), start, end, parts, rank, total);
            if (entry != null) return new Match(true, translationFor(entry));
        }
        String text = global.get(CommentText.legacy(file.text().substring(start, end)));
        return text == null ? Match.NONE : new Match(true, text);
    }

    /**
     * Within the file, the entry with the same textHash and parts: paired by order when the counts agree, else the
     * nearest line; a tie matches nothing.
     */
    private static LocationIndex.Entry byTextHash(LocationIndex.FileEntries entries, String text, int start, int end,
                                                  int parts, int rank, int total) {
        String hash = CommentText.textHash(text.substring(start, end));
        List<LocationIndex.Entry> same = entries.comments().stream()
                .filter(e -> e.textHash().equals(hash) && e.parts() == parts)
                .sorted(java.util.Comparator.comparingInt((LocationIndex.Entry e) -> e.range().startLine())
                        .thenComparingInt(e -> e.range().startCol()))
                .toList();
        if (rank >= 0 && same.size() == total && rank < same.size()) return same.get(rank);
        int line = CommentGroups.line(CommentGroups.lineStarts(text), start) + 1;
        LocationIndex.Entry best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (LocationIndex.Entry e : entries.comments()) {
            if (!e.textHash().equals(hash) || e.parts() != parts) continue;
            int distance = Math.abs(e.range().startLine() - line);
            if (distance < bestDistance) {
                best = e;
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** The display-language text of an index entry; null when the original already is in the display language. */
    String translationFor(LocationIndex.Entry entry) {
        Map<String, String> texts = comments.get(entry.id());
        if (texts == null) return null;
        if (LanguageCodes.equivalent(entry.lang(), language) || LanguageCodes.equivalent(entry.sourceKey(), language)) {
            return null; // The original already is in the display language: never translate a comment into itself.
        }
        String key = LanguageCodes.findKey(texts, language);
        String text = key == null ? null : texts.get(key);
        if (text == null || text.isBlank()) return null;
        String original = texts.get(entry.sourceKey());
        if (original != null && CommentText.legacy(original).equals(CommentText.legacy(text))) return null;
        return text;
    }

    /**
     * Translations located purely from index.json, for files without usable comment PSI (Rider C#, Dart without its
     * plugin): an entry is used only when the document text in its range still has the indexed textHash.
     */
    public List<Located> fallback(FileContext file) {
        LocationIndex.FileEntries entries = locations == null ? null : locations.file(file.relativePath());
        if (entries == null) return List.of();
        String text = file.text();
        int[] starts = CommentGroups.lineStarts(text);
        List<Located> out = new ArrayList<>();
        for (LocationIndex.Entry e : entries.comments()) {
            int start = offset(starts, text, e.range().startLine(), e.range().startCol());
            int end = offset(starts, text, e.range().endLine(), e.range().endCol());
            if (start < 0 || end <= start) continue;
            if (!CommentText.textHash(text.substring(start, end)).equals(e.textHash())) continue; // better nothing than wrong
            String translated = translationFor(e);
            if (translated != null) out.add(new Located(start, end, translated, e.type()));
        }
        return out;
    }

    /** Document offset of a 1-based line and UTF-16 column; -1 when outside the text. */
    private static int offset(int[] starts, String text, int line, int col) {
        if (line < 1 || line > starts.length || col < 1) return -1;
        int lineEnd = line < starts.length ? starts[line] - 1 : text.length();
        int offset = starts[line - 1] + col - 1;
        return offset <= lineEnd ? offset : -1;
    }

    // Deliberately matches core/utils/id.go. Do not strip Javadoc stars: upstream keeps them.
    public static String normalize(String text) { return CommentText.legacy(text); }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
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
