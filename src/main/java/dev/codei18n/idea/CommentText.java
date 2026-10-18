package dev.codei18n.idea;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Text normalization shared with core (docs/index-format.md §6.2) and the legacy ID normalization. */
public final class CommentText {
    private static final String[] OPEN_MARKERS = {"/**", "/*!", "/*", "///", "//!", "//"};

    private CommentText() {}

    /**
     * Unicode White_Space, exactly as Go's unicode.IsSpace. Java's Character.isWhitespace / String.strip differ
     * (they exclude U+00A0 and include U+001C–U+001F), so they must not be used here.
     */
    public static boolean isWhiteSpace(int cp) {
        return switch (cp) {
            case '\t', '\n', 0x0B, '\f', '\r', ' ', 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true;
            default -> cp >= 0x2000 && cp <= 0x200A;
        };
    }

    /** Trims White_Space from both ends. */
    public static String trim(String s) {
        int start = 0, end = s.length();
        while (start < end && isWhiteSpace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start));
        while (end > start && isWhiteSpace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    /** Collapses runs of White_Space to one space and trims. */
    public static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isWhiteSpace(cp)) {
                space = true;
            } else {
                if (space && !out.isEmpty()) out.append(' ');
                space = false;
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    /** docs/index-format.md §6.2, version 1: per line strip one opening marker / leading star / closing marker. */
    public static String normalize(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder joined = new StringBuilder();
        for (String raw : lines) {
            String line = trim(raw);
            boolean stripped = false;
            for (String marker : OPEN_MARKERS) {
                if (line.startsWith(marker)) {
                    line = line.substring(marker.length());
                    stripped = true;
                    break;
                }
            }
            if (!stripped && line.startsWith("*") && !line.startsWith("*/")) line = line.substring(1);
            if (line.endsWith("*/")) line = line.substring(0, line.length() - 2);
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(line);
        }
        return collapse(joined.toString());
    }

    /** SHA-1 of the normalized text, lowercase hex (index.json textHash). */
    public static String textHash(String text) {
        return sha1(normalize(text).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The legacy normalization used for comment IDs and global text matching (core/utils/id.go). */
    public static String legacy(String text) {
        String value = trim(text);
        if (value.startsWith("//")) value = value.substring(2);
        if (value.startsWith("/*")) value = value.substring(2);
        if (value.endsWith("*/")) value = value.substring(0, value.length() - 2);
        return collapse(value);
    }
}
