package dev.codei18n.idea;

import com.intellij.openapi.editor.CustomFoldRegion;
import com.intellij.openapi.editor.CustomFoldRegionRenderer;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Proxy;

/**
 * Replace mode: paints a translation in place of the whole comment lines a custom fold region hides. Layout and
 * painting are delegated to {@link TranslationRenderer}, so both modes look identical; the text is indented to the
 * comment's column.
 */
public final class TranslationFoldRenderer implements CustomFoldRegionRenderer {
    private final TranslationRenderer delegate;
    private final int indentColumns;
    private Editor viewEditor; // EDT only.
    private Inlay<?> view;

    TranslationFoldRenderer(TranslationRenderer delegate, int indentColumns) {
        this.delegate = delegate;
        this.indentColumns = indentColumns;
    }

    /** The translation shown. */
    public String text() { return delegate.text(); }

    TranslationRenderer delegate() { return delegate; }

    @Override public int calcWidthInPixels(CustomFoldRegion region) {
        Editor editor = region.getEditor();
        return indent(editor) + delegate.calcWidthInPixels(view(editor));
    }

    @Override public int calcHeightInPixels(CustomFoldRegion region) {
        return delegate.calcHeightInPixels(view(region.getEditor()));
    }

    @Override public void paint(CustomFoldRegion region, Graphics2D graphics, Rectangle2D target, TextAttributes attributes) {
        Editor editor = region.getEditor();
        int indent = indent(editor);
        Rectangle bounds = target.getBounds();
        bounds.x += indent;
        bounds.width = Math.max(0, bounds.width - indent);
        delegate.paint(view(editor), (Graphics) graphics, bounds, attributes);
    }

    private int indent(Editor editor) {
        var font = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        return indentColumns * editor.getContentComponent().getFontMetrics(font).charWidth(' ');
    }

    /**
     * {@link TranslationRenderer} only reads {@code getEditor()} and {@code getRenderer()} from its inlay. A dynamic
     * proxy supplies those without implementing {@link Inlay}, whose abstract methods differ across platform builds.
     */
    private Inlay<?> view(Editor editor) {
        if (view == null || viewEditor != editor) {
            viewEditor = editor;
            view = (Inlay<?>) Proxy.newProxyInstance(TranslationFoldRenderer.class.getClassLoader(),
                    new Class<?>[] {Inlay.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getEditor" -> editor;
                        case "getRenderer" -> delegate;
                        case "isValid" -> Boolean.TRUE;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> args != null && args.length == 1 && proxy == args[0];
                        case "toString" -> "TranslationFoldRenderer view";
                        default -> defaultValue(method.getReturnType());
                    });
        }
        return view;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return 0d;
    }
}
