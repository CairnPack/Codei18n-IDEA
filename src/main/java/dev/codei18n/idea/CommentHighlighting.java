package dev.codei18n.idea;

import com.intellij.lang.CodeDocumentationAwareCommenter;
import com.intellij.lang.LanguageCommenters;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Lex the translation in the source comment's language, without changing the source PSI. */
final class CommentHighlighting {
    record Span(int start, int end, TextAttributesKey[] keys) {}
    record Result(TextAttributesKey base, List<Span> spans) {}

    static Result translate(PsiComment comment, String text) {
        var commenter = LanguageCommenters.INSTANCE.forLanguage(comment.getLanguage());
        String prefix = "", suffix = "";
        TextAttributesKey base = DefaultLanguageHighlighterColors.LINE_COMMENT;
        if (commenter instanceof CodeDocumentationAwareCommenter docs && docs.isDocumentationComment(comment)) {
            base = DefaultLanguageHighlighterColors.DOC_COMMENT;
            if (docs.isDocumentationLineComment(comment)) {
                prefix = docs.getDocumentationLineCommentPrefix();
            } else {
                prefix = docs.getDocumentationCommentPrefix();
                suffix = docs.getDocumentationCommentSuffix();
            }
        } else if (commenter != null) {
            String block = commenter.getBlockCommentPrefix();
            if (block != null && comment.getText().startsWith(block)) {
                base = DefaultLanguageHighlighterColors.BLOCK_COMMENT;
                prefix = block;
                suffix = commenter.getBlockCommentSuffix();
            } else prefix = commenter.getLineCommentPrefix();
        }
        if (prefix == null || prefix.isEmpty()) return new Result(base, List.of());
        if (suffix == null) suffix = "";
        var highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(comment.getLanguage(),
                comment.getProject(), comment.getContainingFile().getVirtualFile());
        if (highlighter == null) return new Result(base, List.of());
        List<Span> spans = new ArrayList<>();
        // Line comments need a fresh delimiter on every line; block/doc comments need one wrapper.
        if (suffix.isEmpty()) {
            int offset = 0;
            for (String line : text.split("\n", -1)) {
                boolean hasPrefix = line.stripLeading().startsWith(prefix);
                lex(highlighter, hasPrefix ? "" : prefix + " ", line, "", offset, spans);
                offset += line.length() + 1;
            }
        } else {
            String trimmed = text.stripLeading();
            boolean hasWrapper = trimmed.startsWith(prefix) && text.stripTrailing().endsWith(suffix);
            String opening = hasWrapper ? "" : prefix + "\n";
            String closing = hasWrapper ? "" : "\n" + suffix;
            lex(highlighter, opening, text, closing, 0, spans);
            if (base == DefaultLanguageHighlighterColors.DOC_COMMENT && "JAVA".equals(comment.getLanguage().getID())) {
                javaReferences(comment, opening, text, closing, spans);
            }
        }
        return new Result(base, List.copyOf(spans));
    }

    private static void javaReferences(PsiComment source, String prefix, String text, String suffix, List<Span> spans) {
        // Parse a detached file to distinguish actual references from labels and {@code} contents.
        // The platform PSI API keeps Java optional; node names belong to the Java plugin's doc parser.
        var file = PsiFileFactory.getInstance(source.getProject()).createFileFromText("CodeI18nDocumentation.java",
                source.getLanguage(), prefix + text + suffix + "\nclass CodeI18nDocumentation {}");
        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                String type = element.getNode().getElementType().toString();
                if (type.equals("DOC_REFERENCE_HOLDER") || type.equals("DOC_METHOD_OR_FIELD_REF")
                        || type.equals("DOC_PARAMETER_REF")) {
                    int start = Math.max(0, element.getTextRange().getStartOffset() - prefix.length());
                    int end = Math.min(text.length(), element.getTextRange().getEndOffset() - prefix.length());
                    if (start < end) spans.add(new Span(start, end, new TextAttributesKey[]{
                            TextAttributesKey.createTextAttributesKey("JAVA_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT),
                            TextAttributesKey.createTextAttributesKey("DOC_COMMENT_TAG_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE)}));
                } else super.visitElement(element);
            }
        });
    }

    private static void lex(com.intellij.openapi.fileTypes.SyntaxHighlighter highlighter,
                            String prefix, String text, String suffix, int offset, List<Span> spans) {
        var lexer = highlighter.getHighlightingLexer();
        lexer.start(prefix + text + suffix);
        while (lexer.getTokenType() != null) {
            int start = Math.max(0, lexer.getTokenStart() - prefix.length());
            int end = Math.min(text.length(), lexer.getTokenEnd() - prefix.length());
            if (start < end) {
                TextAttributesKey[] keys = highlighter.getTokenHighlights(lexer.getTokenType());
                // Kotlin applies this key in BeforeResolveHighlightingVisitor, not its lexer.
                // Use the lexer's link token so inline code and ordinary brackets stay untouched.
                // Key lookup avoids a hard dependency on the optional Kotlin plugin.
                if ("kotlin".equals(lexer.getTokenType().getLanguage().getID())
                        && "KDOC_MARKDOWN_LINK".equals(lexer.getTokenType().toString())) {
                    keys = Arrays.copyOf(keys, keys.length + 1);
                    keys[keys.length - 1] = TextAttributesKey.createTextAttributesKey("KDOC_LINK",
                            DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE);
                }
                spans.add(new Span(offset + start, offset + end, keys));
            }
            lexer.advance();
        }
    }
}
