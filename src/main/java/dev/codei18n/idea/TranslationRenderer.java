package dev.codei18n.idea;

import com.intellij.ide.todo.TodoConfiguration;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paints a translation as styled, wrapped lines; the layout is cached until width, font or scheme change. */
public final class TranslationRenderer implements EditorCustomElementRenderer {
    private static final int LEFT_PADDING = 12;
    private static final int RIGHT_PADDING = 4;
    private static final int MIN_WRAP_WIDTH = 240;
    private static final int WRAP_MARGIN = 48;

    private record Run(String text, TextAttributes style, Font font, int width) {}
    private record Line(List<Run> runs, int width) {}
    private record Layout(EditorColorsScheme scheme, Font font, int available, List<Line> lines) {
        boolean matches(EditorColorsScheme scheme, Font font, int available) {
            return this.scheme == scheme && this.font.equals(font) && this.available == available;
        }
    }

    private final String text;
    /** What is painted: the translation with documentation markup simplified; styles index this text. */
    private final String display;
    private final String language;
    private final CommentHighlighting.Result highlighting;
    private Layout layout; // EDT only.

    TranslationRenderer(String text, String language, CommentHighlighting.Result highlighting) {
        this.text = text;
        this.language = language;
        this.highlighting = highlighting;
        this.display = highlighting.displayOf(text);
    }

    /** The raw translation (not the simplified display text). */
    public String text() { return text; }

    /** Drops the cached layout, e.g. after the scheme's attributes or TODO patterns changed in place. */
    void invalidateLayout() { layout = null; }

    @Override public int calcWidthInPixels(Inlay inlay) {
        int widest = lines(inlay.getEditor()).stream().mapToInt(Line::width).max().orElse(0);
        return Math.max(1, LEFT_PADDING + widest + RIGHT_PADDING);
    }

    @Override public int calcHeightInPixels(Inlay inlay) {
        return lines(inlay.getEditor()).size() * inlay.getEditor().getLineHeight();
    }

    @Override public void paint(Inlay inlay, Graphics graphics, Rectangle bounds, TextAttributes attributes) {
        Graphics g = graphics.create();
        try {
            Editor editor = inlay.getEditor();
            int lineHeight = editor.getLineHeight(), ascent = editor.getAscent();
            int y = bounds.y + ascent;
            for (Line line : lines(editor)) {
                int x = bounds.x + LEFT_PADDING;
                for (Run run : line.runs) {
                    g.setFont(run.font);
                    if (run.style.getBackgroundColor() != null) {
                        g.setColor(run.style.getBackgroundColor());
                        g.fillRect(x, y - ascent, run.width, lineHeight);
                    }
                    g.setColor(run.style.getForegroundColor() != null ? run.style.getForegroundColor()
                            : editor.getColorsScheme().getDefaultForeground());
                    g.drawString(run.text, x, y);
                    x += run.width;
                }
                y += lineHeight;
            }
        } finally {
            g.dispose();
        }
    }

    private List<Line> lines(Editor editor) {
        EditorColorsScheme scheme = editor.getColorsScheme();
        Font font = scheme.getFont(EditorFontType.PLAIN);
        int available = Math.max(MIN_WRAP_WIDTH, editor.getScrollingModel().getVisibleArea().width - WRAP_MARGIN);
        if (layout == null || !layout.matches(scheme, font, available)) {
            layout = new Layout(scheme, font, available, wrap(editor, available));
        }
        return layout.lines;
    }

    /** One style per character of {@code display}: comment base, lexer spans, then the IDE's TODO patterns. */
    private TextAttributes[] styles(Editor editor) {
        var scheme = editor.getColorsScheme();
        TextAttributes base = baseStyle(scheme);
        TextAttributes[] styles = new TextAttributes[display.length()];
        Arrays.fill(styles, base);
        for (var span : highlighting.spans()) {
            TextAttributes style = base;
            for (var key : span.keys()) {
                var overlay = scheme.getAttributes(key);
                if (overlay != null) style = TextAttributes.merge(style, overlay);
            }
            Arrays.fill(styles, span.start(), span.end(), style);
        }
        // TODO highlighting is a separate IDE pass, not part of the language lexer.
        for (var pattern : TodoConfiguration.getInstance().getTodoPatterns()) {
            if (pattern.getPattern() == null) continue;
            var attributes = pattern.getAttributes();
            var overlay = attributes.shouldUseCustomTodoColor() ? attributes.getTextAttributes()
                    : scheme.getAttributes(CodeInsightColors.TODO_DEFAULT_ATTRIBUTES);
            if (overlay == null) continue;
            var matcher = pattern.getPattern().matcher(display);
            while (matcher.find()) {
                for (int i = matcher.start(); i < matcher.end(); i++) styles[i] = TextAttributes.merge(styles[i], overlay);
            }
        }
        return styles;
    }

    /** Splits "[language] text" into lines of same-style runs, breaking at line breaks and at {@code available}. */
    private List<Line> wrap(Editor editor, int available) {
        TextAttributes base = baseStyle(editor.getColorsScheme());
        TextAttributes[] styles = styles(editor);
        String label = "[" + language + "] ";
        String shown = label + display;
        Map<Integer, Font> fonts = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        int width = 0;
        for (int i = 0; i < shown.length(); ) {
            int cp = shown.codePointAt(i);
            if (isLineBreak(cp)) {
                lines.add(new Line(runs, width));
                runs = new ArrayList<>();
                width = 0;
                i += cp == '\r' && i + 1 < shown.length() && shown.charAt(i + 1) == '\n' ? 2 : 1;
                continue;
            }
            var style = i < label.length() ? base : styles[i - label.length()];
            Font font = fonts.computeIfAbsent(style.getFontType(), type -> font(editor, type));
            var metrics = editor.getContentComponent().getFontMetrics(font);
            String next = Character.toString(cp);
            Run previous = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            boolean join = previous != null && previous.style.equals(style) && previous.font.equals(font);
            // Measure the joined run, not the sum of characters, so kerning matches what paint draws.
            int increment = join ? metrics.stringWidth(previous.text + next) - previous.width : metrics.stringWidth(next);
            if (width > 0 && width + increment > available) {
                lines.add(new Line(runs, width));
                runs = new ArrayList<>();
                width = 0;
                join = false;
                increment = metrics.stringWidth(next);
            }
            if (join) runs.set(runs.size() - 1, new Run(previous.text + next, style, font, previous.width + increment));
            else runs.add(new Run(next, style, font, increment));
            width += increment;
            i += Character.charCount(cp);
        }
        lines.add(new Line(runs, width));
        return lines;
    }

    private Font font(Editor editor, int fontType) {
        Font configured = editor.getColorsScheme().getFont(EditorFontType.PLAIN).deriveFont(fontType);
        // Logical Dialog uses the JBR's composite fallback fonts for CJK translations.
        return configured.canDisplayUpTo(display) == -1 ? configured
                : new Font(Font.DIALOG, fontType, configured.getSize()).deriveFont(configured.getSize2D());
    }

    private TextAttributes baseStyle(EditorColorsScheme scheme) {
        TextAttributes base = scheme.getAttributes(highlighting.base());
        return base == null ? new TextAttributes() : base;
    }

    private static boolean isLineBreak(int cp) {
        return cp == '\r' || cp == '\n' || cp == 0x85 || cp == 0x2028 || cp == 0x2029 || cp == 0xB || cp == 0xC;
    }
}
