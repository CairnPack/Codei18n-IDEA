package dev.codei18n.idea;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

public final class TranslationRenderer implements EditorCustomElementRenderer {
    private final String text;
    private final String language;

    public TranslationRenderer(String text, String language) {
        this.text = text;
        this.language = language;
    }

    private Font font(Editor editor) {
        Font configured = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        // Logical Dialog uses the JBR's composite fallback fonts for CJK translations.
        return configured.canDisplayUpTo(text) == -1 ? configured
                : new Font(Font.DIALOG, Font.PLAIN, configured.getSize()).deriveFont(configured.getSize2D());
    }

    private List<String> lines(Editor editor) {
        var metrics = editor.getContentComponent().getFontMetrics(font(editor));
        // Character-aware wrapping also works for Chinese and preserves explicit newlines.
        int width = Math.max(240, editor.getScrollingModel().getVisibleArea().width - 48);
        List<String> lines = new ArrayList<>();
        for (String paragraph : ("[" + language + "] " + text).split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            for (int cp : paragraph.codePoints().toArray()) {
                String next = new String(Character.toChars(cp));
                if (!line.isEmpty() && metrics.stringWidth(line + next) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    @Override public int calcWidthInPixels(Inlay inlay) {
        var editor = inlay.getEditor();
        var metrics = editor.getContentComponent().getFontMetrics(font(editor));
        return Math.max(1, lines(editor).stream().mapToInt(metrics::stringWidth).max().orElse(1) + 16);
    }

    @Override public int calcHeightInPixels(Inlay inlay) {
        return lines(inlay.getEditor()).size() * inlay.getEditor().getLineHeight();
    }

    @Override public void paint(Inlay inlay, Graphics graphics, Rectangle bounds, TextAttributes attributes) {
        Graphics g = graphics.create();
        try {
            Editor editor = inlay.getEditor();
            g.setFont(font(editor));
            g.setColor(JBColor.GRAY);
            int y = bounds.y + editor.getAscent();
            for (String line : lines(editor)) {
                g.drawString(line, bounds.x + 12, y);
                y += editor.getLineHeight();
            }
        } finally { g.dispose(); }
    }
}
