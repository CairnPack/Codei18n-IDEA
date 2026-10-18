package dev.codei18n.idea;

/** Pure text geometry of a comment range {@code [start, end)}; shared by both display modes. */
final class CommentGeometry {
    private CommentGeometry() {}

    /** Whether the comment occupies whole lines: only whitespace before it and after it on its first and last line. */
    static boolean isWholeLine(CharSequence text, int start, int end) {
        for (int i = start - 1; i >= 0 && !isLineBreak(text.charAt(i)); i--) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        for (int i = end; i < text.length() && !isLineBreak(text.charAt(i)); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    /** Offset of the start of the line containing {@code offset}. */
    static int lineStart(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0 && !isLineBreak(text.charAt(i - 1))) i--;
        return i;
    }

    /** Offset of the end of the line containing {@code offset}, before its line break. */
    static int lineEnd(CharSequence text, int offset) {
        int i = Math.max(0, offset);
        while (i < text.length() && !isLineBreak(text.charAt(i))) i++;
        return i;
    }

    /**
     * The range in which a caret reveals the original comment: its whole lines when it occupies them (the fold covers
     * them), otherwise the comment itself including both boundaries (typing at either end edits it).
     */
    static int[] caretSpan(CharSequence text, int start, int end) {
        return isWholeLine(text, start, end) ? new int[] {lineStart(text, start), lineEnd(text, end)} : new int[] {start, end};
    }

    static boolean contains(int[] span, int offset) { return span[0] <= offset && offset <= span[1]; }

    /** Visual columns before {@code start} on its line, expanding tabs to {@code tabSize}. */
    static int indentColumns(CharSequence text, int start, int tabSize) {
        int columns = 0, size = Math.max(1, tabSize);
        for (int i = lineStart(text, start); i < start; i++) {
            columns = text.charAt(i) == '\t' ? (columns / size + 1) * size : columns + 1;
        }
        return columns;
    }

    /**
     * Whether an edit at {@code offset} replacing {@code oldLength} characters with {@code inserted} may change the
     * comment {@code [begin, end)} (offsets before the change), making its translation stale.
     */
    static boolean affects(int begin, int end, int offset, int oldLength, CharSequence inserted) {
        // Removing text right after the comment may join the next line into it.
        if (oldLength > 0) return offset <= end && offset + oldLength > begin;
        // Text typed at the very end may extend a line comment, unless it starts a new line.
        if (offset == end) return !(inserted.length() > 0 && isLineBreak(inserted.charAt(0)));
        // Insertions before the comment (indentation) or after it leave the comment intact.
        return begin < offset && offset < end;
    }

    /** One-line fold placeholder for a comment sharing its line with code: "[language] text", whitespace collapsed. */
    static String placeholder(String language, String translation, int maxCodePoints) {
        String flat = translation.strip().replaceAll("\\s+", " ");
        String text = "[" + language + "] " + flat;
        if (text.codePointCount(0, text.length()) <= maxCodePoints) return text;
        return text.substring(0, text.offsetByCodePoints(0, Math.max(1, maxCodePoints - 1))) + "…";
    }

    private static boolean isLineBreak(char c) { return c == '\n' || c == '\r'; }
}
