package dev.codei18n.idea;

import java.util.Arrays;
import java.util.Objects;

/** Display-mode settings parsing and comment geometry used by replace mode. Run from MappingIndexTest. */
final class DisplayModeLogicTest {
    private static int assertions;

    static int run() {
        modes();
        wholeLine();
        caretSpans();
        indentation();
        edits();
        placeholders();
        System.out.println("PASS: " + assertions + " display mode assertions");
        return assertions;
    }

    private static void modes() {
        equal(DisplayMode.BELOW, DisplayMode.parse(null));
        equal(DisplayMode.BELOW, DisplayMode.parse(""));
        equal(DisplayMode.BELOW, DisplayMode.parse("unknown"));
        equal(DisplayMode.REPLACE, DisplayMode.parse("REPLACE"));
        equal(DisplayMode.REPLACE, DisplayMode.parse(" replace "));
        equal(DisplayMode.REPLACE, DisplayMode.BELOW.toggled());
        equal(DisplayMode.BELOW, DisplayMode.REPLACE.toggled());
        // Default persisted value is below mode.
        equal(DisplayMode.BELOW, DisplayMode.parse(new TranslationSettings.Values().displayMode));
    }

    private static void wholeLine() {
        String text = "class A {\n    // own line\n    int x; // trailing\n\t/* a\n\t b */  \n/* x */ int y;\n}";
        equal(true, whole(text, "// own line"));
        equal(false, whole(text, "// trailing"));
        equal(true, whole(text, "/* a\n\t b */"));
        equal(false, whole(text, "/* x */"));
        equal(true, CommentGeometry.isWholeLine("// only", 0, 7));
        equal(true, CommentGeometry.isWholeLine("a\r\n  // c\r\nb", 5, 9));
    }

    private static void caretSpans() {
        String text = "x;\n    // own\ny; // t\n";
        int own = text.indexOf("// own"), trailing = text.indexOf("// t");
        int[] ownSpan = CommentGeometry.caretSpan(text, own, own + 6);
        equal("[3, 13]", Arrays.toString(ownSpan));
        equal(true, CommentGeometry.contains(ownSpan, 3)); // Line start: the fold covers the whole line.
        equal(true, CommentGeometry.contains(ownSpan, 13)); // Line end.
        equal(false, CommentGeometry.contains(ownSpan, 2)); // End of the previous line.
        equal(false, CommentGeometry.contains(ownSpan, 14)); // Start of the next line.
        int[] trailingSpan = CommentGeometry.caretSpan(text, trailing, trailing + 4);
        equal(true, CommentGeometry.contains(trailingSpan, trailing));
        equal(true, CommentGeometry.contains(trailingSpan, trailing + 4));
        equal(false, CommentGeometry.contains(trailingSpan, trailing - 1)); // On the code before it.
        equal(0, CommentGeometry.lineStart(text, 0));
        equal(2, CommentGeometry.lineEnd(text, 0));
        equal(text.length(), CommentGeometry.lineStart(text, text.length()));
    }

    private static void indentation() {
        equal(4, CommentGeometry.indentColumns("    // c", 4, 4));
        equal(4, CommentGeometry.indentColumns("\t// c", 1, 4));
        equal(8, CommentGeometry.indentColumns("  \t// c", 3, 8));
        equal(0, CommentGeometry.indentColumns("a\n// c", 2, 4));
    }

    private static void edits() {
        // Comment occupies [10, 20).
        equal(true, CommentGeometry.affects(10, 20, 12, 0, "x"));
        equal(false, CommentGeometry.affects(10, 20, 10, 0, "    ")); // Indenting before it.
        equal(false, CommentGeometry.affects(10, 20, 5, 0, "x"));
        equal(true, CommentGeometry.affects(10, 20, 20, 0, "x")); // Extends a line comment.
        equal(false, CommentGeometry.affects(10, 20, 20, 0, "\nnext")); // New line after it.
        equal(true, CommentGeometry.affects(10, 20, 20, 1, "")); // May join the next line.
        equal(true, CommentGeometry.affects(10, 20, 0, 11, ""));
        equal(false, CommentGeometry.affects(10, 20, 0, 10, ""));
        equal(false, CommentGeometry.affects(10, 20, 21, 3, ""));
    }

    private static void placeholders() {
        equal("[zh-CN] 计算 账户余额", CommentGeometry.placeholder("zh-CN", " 计算\n  账户余额\n", 50));
        String cut = CommentGeometry.placeholder("ja", "😀".repeat(30), 10);
        equal(10, cut.codePointCount(0, cut.length()));
        equal(true, cut.endsWith("…"));
        equal("[ja] 😀😀😀😀…", cut);
    }

    private static boolean whole(String text, String comment) {
        int start = text.indexOf(comment);
        return CommentGeometry.isWholeLine(text, start, start + comment.length());
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
