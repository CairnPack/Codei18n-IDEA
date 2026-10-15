package dev.codei18n.idea;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;

public final class HighlightingTest {
    public static void run(Project project, Editor editor, Path imagePath) {
        try {
            var type = FileDocumentManager.getInstance().getFile(editor.getDocument()).getFileType();
            var file = PsiFileFactory.getInstance(project).createFileFromText("Highlight.java", type,
                    "/** Description.\n * @param value input\n */\nclass Highlight {}\n// ordinary\n/* block */");
            var comments = new ArrayList<PsiComment>();
            file.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override public void visitElement(PsiElement element) {
                    if (element instanceof PsiComment comment) comments.add(comment);
                    else super.visitElement(element);
                }
            });
            require(comments.size() == 3, "doc, line and block fixtures parsed");
            String text = "计算账户余额😀\n@param value 输入值\n{@code value} 与 {@link String}\nTODO: 检查余额\n" + "中文换行测试".repeat(30);
            var highlighting = translate(comments.get(0), text);
            require(call(highlighting, "base") == DefaultLanguageHighlighterColors.DOC_COMMENT, "documentation context retained");
            require(call(translate(comments.get(1), "普通\n第二行"), "base")
                    == DefaultLanguageHighlighterColors.LINE_COMMENT, "line comment context retained");
            require(call(translate(comments.get(2), "普通"), "base")
                    == DefaultLanguageHighlighterColors.BLOCK_COMMENT, "block comment context retained");
            int tag = text.indexOf("@param");
            var spans = (java.util.List<?>) call(highlighting, "spans");
            Object tagSpan = spans.stream().filter(s -> (int) call(s, "start") <= tag && (int) call(s, "end") > tag).findFirst().orElseThrow();
            var tagKeys = (com.intellij.openapi.editor.colors.TextAttributesKey[]) call(tagSpan, "keys");
            require(java.util.Arrays.stream(tagKeys).anyMatch(k -> k.getExternalName().equals("JAVA_DOC_TAG")),
                    "Java lexer recognizes translated Javadoc tag");
            var wrapped = (java.util.List<?>) call(translate(comments.get(0), "/**\n * @param value 输入值\n */"), "spans");
            require(wrapped.stream().anyMatch(s -> (int) call(s, "start") <= 8 && (int) call(s, "end") > 8
                    && java.util.Arrays.stream((com.intellij.openapi.editor.colors.TextAttributesKey[]) call(s, "keys"))
                    .anyMatch(k -> k.getExternalName().equals("JAVA_DOC_TAG"))),
                    "Javadoc tags remain highlighted when translation keeps comment delimiters");
            var scheme = (com.intellij.openapi.editor.colors.EditorColorsScheme) com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().clone();
            var previousScheme = editor.getColorsScheme();
            ((com.intellij.openapi.editor.ex.EditorEx) editor).setColorsScheme(scheme);
            try {
                var key = tagKeys[tagKeys.length - 1];
                editor.getColorsScheme().setAttributes(key, new TextAttributes(Color.MAGENTA, null, null, null, Font.BOLD));
                editor.getColorsScheme().setAttributes(CodeInsightColors.TODO_DEFAULT_ATTRIBUTES,
                        new TextAttributes(Color.ORANGE, null, null, null, Font.BOLD));
                var constructor = TranslationRenderer.class.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                var renderer = (TranslationRenderer) constructor.newInstance(text, "zh-CN", highlighting);
                var method = TranslationRenderer.class.getDeclaredMethod("styles", Editor.class);
                method.setAccessible(true);
                var styles = (TextAttributes[]) method.invoke(renderer, editor);
                require(Color.MAGENTA.equals(styles[tag].getForegroundColor()) && (styles[tag].getFontType() & Font.BOLD) != 0,
                        "Javadoc tag uses editor color and bold font");
                require(!Color.MAGENTA.equals(styles[0].getForegroundColor()), "ordinary prose remains comment-colored");
                require(Color.ORANGE.equals(styles[text.indexOf("TODO")].getForegroundColor()), "TODO uses IDE theme color");
                editor.getColorsScheme().setAttributes(key, new TextAttributes(Color.CYAN, null, null, null, Font.ITALIC));
                styles = (TextAttributes[]) method.invoke(renderer, editor);
                require(Color.CYAN.equals(styles[tag].getForegroundColor()), "renderer resolves updated theme without relexing");
                var inlay = editor.getInlayModel().addBlockElement(0, true, false, 0, renderer);
                require(inlay != null && inlay.getHeightInPixels() > 5 * editor.getLineHeight(), "styled Chinese text wraps");
                try {
                    var image = new BufferedImage(inlay.getWidthInPixels(), inlay.getHeightInPixels(), BufferedImage.TYPE_INT_ARGB);
                    var graphics = image.createGraphics();
                    try {
                        graphics.setColor(scheme.getDefaultBackground());
                        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                        renderer.paint(inlay, (java.awt.Graphics) graphics,
                                new Rectangle(0, 0, image.getWidth(), image.getHeight()), new TextAttributes());
                    } finally { graphics.dispose(); }
                    javax.imageio.ImageIO.write(image, "png", imagePath.toFile());
                } finally { inlay.dispose(); }
                verifyJavadocReferences(comments.get(0), editor);
                verifyKdoc(project, editor, imagePath.resolveSibling("kdoc-render.png"));
            } finally { ((com.intellij.openapi.editor.ex.EditorEx) editor).setColorsScheme(previousScheme); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void verifyJavadocReferences(PsiComment comment, Editor editor) throws Exception {
        var key = com.intellij.openapi.editor.colors.TextAttributesKey.find("DOC_COMMENT_TAG_VALUE");
        editor.getColorsScheme().setAttributes(key, new TextAttributes(Color.GREEN, null, null, null, Font.PLAIN));
        String body = "使用 {@link String} 和 {@linkplain java.util.List 标签}，{@link String#length()}、{@link Integer#MAX_VALUE}。\n * @see String\n"
                + " * @param value 输入\n * {@code unchanged} 普通文字";
        var constructor = TranslationRenderer.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var method = TranslationRenderer.class.getDeclaredMethod("styles", Editor.class);
        method.setAccessible(true);
        for (String text : java.util.List.of(body, "/**\n * " + body + "\n */")) {
            var renderer = (TranslationRenderer) constructor.newInstance(text, "zh-CN", translate(comment, text));
            var styles = (TextAttributes[]) method.invoke(renderer, editor);
            for (String reference : java.util.List.of("String", "java.util.List", "String#length()", "Integer#MAX_VALUE", "value")) {
                int start = text.indexOf(reference);
                require(java.util.stream.IntStream.range(start, start + reference.length())
                        .allMatch(i -> Color.GREEN.equals(styles[i].getForegroundColor())),
                        "Javadoc reference uses tag-value style: " + reference);
            }
            require(Color.GREEN.equals(styles[text.lastIndexOf("String")].getForegroundColor()), "Javadoc @see reference highlighted");
            require(!Color.GREEN.equals(styles[text.indexOf("标签")].getForegroundColor()), "Javadoc link label stays prose");
            require(!Color.GREEN.equals(styles[text.indexOf("unchanged")].getForegroundColor()), "Javadoc code is not a reference");
        }
    }

    private static void verifyKdoc(Project project, Editor editor, Path imagePath) throws Exception {
        var language = com.intellij.lang.Language.findLanguageByID("kotlin");
        require(language != null, "real Kotlin plugin loaded");
        var file = PsiFileFactory.getInstance(project).createFileFromText("Highlight.kt", language,
                "/** Resolves [versions] and [manifestRefreshInterval]. */\nfun resolveVersion() {}\n// [ordinary]");
        var comments = new ArrayList<PsiComment>();
        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                if (element instanceof PsiComment comment) comments.add(comment);
                else super.visitElement(element);
            }
        });
        require(comments.size() == 2, "Kotlin KDoc and ordinary comment parsed");
        var linkKey = com.intellij.openapi.editor.colors.TextAttributesKey.find("KDOC_LINK");
        editor.getColorsScheme().setAttributes(linkKey,
                new TextAttributes(Color.ORANGE, null, null, null, Font.BOLD));
        var constructor = TranslationRenderer.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var stylesMethod = TranslationRenderer.class.getDeclaredMethod("styles", Editor.class);
        stylesMethod.setAccessible(true);
        String body = "从本地元数据缓存解析。\n * 刷新策略：除每次显式 [versions] 调用外，当上次\n"
                + " * 成功获取距今超过 [manifestRefreshInterval] 时同步；失败后\n"
                + " * 在 [manifestRetryInterval] 内不会重试。\n * @param value 输入值\n * `code [notALink]`";
        for (String text : java.util.List.of(body, "/**\n * " + body + "\n */")) {
            var renderer = (TranslationRenderer) constructor.newInstance(text, "zh-CN", translate(comments.get(0), text));
            var styles = (TextAttributes[]) stylesMethod.invoke(renderer, editor);
            for (String link : java.util.List.of("[versions]", "[manifestRefreshInterval]", "[manifestRetryInterval]")) {
                int start = text.indexOf(link);
                require(java.util.stream.IntStream.range(start, start + link.length())
                        .allMatch(i -> Color.ORANGE.equals(styles[i].getForegroundColor())),
                        "KDoc reference uses theme link color: " + link);
            }
            require(!Color.ORANGE.equals(styles[text.indexOf("从")].getForegroundColor()), "KDoc prose stays comment-colored");
            require(!Color.ORANGE.equals(styles[text.indexOf("notALink")].getForegroundColor()), "inline code is not a KDoc reference");
            editor.getColorsScheme().setAttributes(linkKey, new TextAttributes(Color.CYAN, null, null, null, Font.ITALIC));
            var recolored = (TextAttributes[]) stylesMethod.invoke(renderer, editor);
            require(Color.CYAN.equals(recolored[text.indexOf("versions")].getForegroundColor()),
                    "KDoc link follows changed editor theme");
            editor.getColorsScheme().setAttributes(linkKey, new TextAttributes(Color.ORANGE, null, null, null, Font.BOLD));
            var inlay = editor.getInlayModel().addBlockElement(0, true, false, 0, renderer);
            require(inlay != null, "KDoc translation inlay created");
            try {
                var image = new BufferedImage(inlay.getWidthInPixels(), inlay.getHeightInPixels(), BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.DARK_GRAY);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    renderer.paint(inlay, (java.awt.Graphics) graphics,
                            new Rectangle(0, 0, image.getWidth(), image.getHeight()), new TextAttributes());
                } finally { graphics.dispose(); }
                javax.imageio.ImageIO.write(image, "png", imagePath.toFile());
            } finally { inlay.dispose(); }
        }
        String ordinary = "普通 [ordinary]";
        var renderer = (TranslationRenderer) constructor.newInstance(ordinary, "zh-CN", translate(comments.get(1), ordinary));
        var styles = (TextAttributes[]) stylesMethod.invoke(renderer, editor);
        require(!Color.ORANGE.equals(styles[ordinary.indexOf("ordinary")].getForegroundColor()),
                "ordinary Kotlin comment brackets are not links");
    }

    private static Object translate(PsiComment comment, String text) throws Exception {
        var type = Class.forName("dev.codei18n.idea.CommentHighlighting", true, TranslationRenderer.class.getClassLoader());
        var method = type.getDeclaredMethod("translate", PsiComment.class, String.class);
        method.setAccessible(true);
        return method.invoke(null, comment, text);
    }

    private static Object call(Object target, String name) {
        try {
            var method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
}
