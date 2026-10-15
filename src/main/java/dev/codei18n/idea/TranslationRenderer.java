package dev.codei18n.idea;

import com.intellij.ide.todo.TodoConfiguration;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public final class TranslationRenderer implements EditorCustomElementRenderer {
    private final String text;
    private final String language;
    private final CommentHighlighting.Result highlighting;
    private record Run(String text, TextAttributes style, Font font, int width) {}
    private record Line(List<Run> runs, int width) {}

    TranslationRenderer(String text, String language, CommentHighlighting.Result highlighting) {
        this.text = text;
        this.language = language;
        this.highlighting = highlighting;
    }

    private Font font(Editor editor, TextAttributes style) {
        Font configured = editor.getColorsScheme().getFont(EditorFontType.PLAIN).deriveFont(style.getFontType());
        // Logical Dialog uses the JBR's composite fallback fonts for CJK translations.
        return configured.canDisplayUpTo(text) == -1 ? configured
                : new Font(Font.DIALOG, style.getFontType(), configured.getSize()).deriveFont(configured.getSize2D());
    }

    private TextAttributes[] styles(Editor editor) {
        var scheme = editor.getColorsScheme();
        TextAttributes base = scheme.getAttributes(highlighting.base());
        if (base == null) base = new TextAttributes();
        TextAttributes[] styles = new TextAttributes[text.length()];
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
            var matcher = pattern.getPattern().matcher(text);
            var attributes = pattern.getAttributes();
            var overlay = attributes.shouldUseCustomTodoColor() ? attributes.getTextAttributes()
                    : scheme.getAttributes(CodeInsightColors.TODO_DEFAULT_ATTRIBUTES);
            if (overlay == null) continue;
            while (matcher.find()) {
                for (int i = matcher.start(); i < matcher.end(); i++) {
                    styles[i] = TextAttributes.merge(styles[i], overlay);
                }
            }
        }
        return styles;
    }

    private List<Line> lines(Editor editor) {
        var scheme = editor.getColorsScheme();
        var base = scheme.getAttributes(highlighting.base());
        if (base == null) base = new TextAttributes();
        TextAttributes[] styles = styles(editor);
        String label = "[" + language + "] ";
        String display = label + text;
        int available = Math.max(240, editor.getScrollingModel().getVisibleArea().width - 48);
        List<Line> lines = new ArrayList<>();
        var fonts = new HashMap<Integer, Font>();
        List<Run> runs = new ArrayList<>();
        int width = 0;
        for (int i = 0; i < display.length();) {
            int cp = display.codePointAt(i);
            if (cp == '\r' || cp == '\n' || cp == 0x85 || cp == 0x2028 || cp == 0x2029 || cp == 0xB || cp == 0xC) {
                lines.add(new Line(runs, width));
                runs = new ArrayList<>();
                width = 0;
                i += cp == '\r' && i + 1 < display.length() && display.charAt(i + 1) == '\n' ? 2 : 1;
                continue;
            }
            var style = i < label.length() ? base : styles[i - label.length()];
            Font font = fonts.computeIfAbsent(style.getFontType(), ignored -> font(editor, style));
            var metrics = editor.getContentComponent().getFontMetrics(font);
            String next = new String(Character.toChars(cp));
            Run previous = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            boolean join = previous != null && previous.style.equals(style) && previous.font.equals(font);
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

    @Override public int calcWidthInPixels(Inlay inlay) {
        return Math.max(1, lines(inlay.getEditor()).stream().mapToInt(Line::width).max().orElse(1) + 16);
    }

    @Override public int calcHeightInPixels(Inlay inlay) {
        return lines(inlay.getEditor()).size() * inlay.getEditor().getLineHeight();
    }

    @Override public void paint(Inlay inlay, Graphics graphics, Rectangle bounds, TextAttributes attributes) {
        Graphics g = graphics.create();
        try {
            Editor editor = inlay.getEditor();
            int y = bounds.y + editor.getAscent();
            for (Line line : lines(editor)) {
                int x = bounds.x + 12;
                for (Run run : line.runs) {
                    g.setFont(run.font);
                    if (run.style.getBackgroundColor() != null) {
                        g.setColor(run.style.getBackgroundColor());
                        g.fillRect(x, y - editor.getAscent(), run.width, editor.getLineHeight());
                    }
                    g.setColor(run.style.getForegroundColor() != null ? run.style.getForegroundColor()
                            : editor.getColorsScheme().getDefaultForeground());
                    g.drawString(run.text, x, y);
                    x += run.width;
                }
                y += editor.getLineHeight();
            }
        } finally { g.dispose(); }
    }
}
