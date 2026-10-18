package dev.codei18n.idea;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parsed {@code .codei18n/index.json} (docs/index-format.md, version 1). Immutable. */
public final class LocationIndex {
    /** 1-based lines, 1-based UTF-16 columns, end exclusive. */
    public record Range(int startLine, int startCol, int endLine, int endCol) {}

    public record Entry(String id, Range range, String type, String lang, String langSource, String sourceKey,
                        String textHash, int parts) {}

    public record FileEntries(String contentHash, String language, List<Entry> comments) {}

    private final Map<String, FileEntries> files;

    private LocationIndex(Map<String, FileEntries> files) { this.files = Map.copyOf(files); }

    /**
     * Parses index.json; returns null when the major version is not supported (callers then behave as if there were
     * no index). Malformed JSON or schema errors throw IllegalArgumentException / JsonParseException.
     */
    public static LocationIndex parse(String json) {
        JsonElement root = JsonParser.parseString(stripBom(json));
        if (!root.isJsonObject()) throw new IllegalArgumentException("index.json must be a JSON object");
        JsonObject data = root.getAsJsonObject();
        String version = string(data, "version", "");
        int dot = version.indexOf('.');
        if (!(dot < 0 ? version : version.substring(0, dot)).equals("1")) return null;
        Map<String, FileEntries> files = new HashMap<>();
        JsonElement fileElement = data.get("files");
        if (fileElement != null && fileElement.isJsonObject()) {
            for (var file : fileElement.getAsJsonObject().entrySet()) {
                if (!file.getValue().isJsonObject()) continue;
                JsonObject f = file.getValue().getAsJsonObject();
                List<Entry> comments = new ArrayList<>();
                JsonElement list = f.get("comments");
                if (list != null && list.isJsonArray()) {
                    for (JsonElement item : list.getAsJsonArray()) {
                        if (!item.isJsonObject()) continue;
                        JsonObject c = item.getAsJsonObject();
                        JsonObject r = c.has("range") && c.get("range").isJsonObject() ? c.getAsJsonObject("range") : new JsonObject();
                        comments.add(new Entry(string(c, "id", ""),
                                new Range(integer(r, "startLine"), integer(r, "startCol"), integer(r, "endLine"), integer(r, "endCol")),
                                string(c, "type", ""), string(c, "lang", ""), string(c, "langSource", ""),
                                string(c, "sourceKey", ""), string(c, "textHash", ""), Math.max(1, integer(c, "parts"))));
                    }
                }
                files.put(file.getKey(), new FileEntries(string(f, "contentHash", ""), string(f, "language", ""), List.copyOf(comments)));
            }
        }
        return new LocationIndex(files);
    }

    /** Entries of {@code relativePath} (relative to the .codei18n parent, '/' separators), or null. */
    public FileEntries file(String relativePath) { return files.get(relativePath); }

    public int fileCount() { return files.size(); }

    private static String stripBom(String text) { return text.startsWith("﻿") ? text.substring(1) : text; }

    private static String string(JsonObject o, String key, String fallback) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : fallback;
    }

    private static int integer(JsonObject o, String key) {
        JsonElement v = o.get(key);
        try {
            return v != null && v.isJsonPrimitive() ? v.getAsInt() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
