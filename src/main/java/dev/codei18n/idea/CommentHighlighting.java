package dev.codei18n.idea;

import com.intellij.lang.CodeDocumentationAwareCommenter;
import com.intellij.lang.Commenter;
import com.intellij.lang.LanguageCommenters;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Lex the translation in the source comment's language, without changing the source PSI. */
final class CommentHighlighting {
    record Span(int start, int end, TextAttributesKey[] keys) {}
    record Result(TextAttributesKey base, List<Span> spans) {}

    // Keys and node names of optional language plugins, looked up by name to avoid hard dependencies.
    private static final TextAttributesKey KDOC_LINK =
            TextAttributesKey.createTextAttributesKey("KDOC_LINK", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE);
    private static final TextAttributesKey[] JAVA_DOC_REFERENCE = {
            TextAttributesKey.createTextAttributesKey("JAVA_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT),
            TextAttributesKey.createTextAttributesKey("DOC_COMMENT_TAG_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE)};
    private static final Set<String> JAVA_DOC_REFERENCE_NODES =
            Set.of("DOC_REFERENCE_HOLDER", "DOC_METHOD_OR_FIELD_REF", "DOC_PARAMETER_REF");

    /** How the source comment is delimited; an empty prefix means the language has no usable commenter. */
    private record Syntax(TextAttributesKey base, String prefix, String suffix) {
        boolean isLineComment() { return suffix.isEmpty(); }

        static Syntax of(PsiComment comment) {
            Commenter commenter = LanguageCommenters.INSTANCE.forLanguage(comment.getLanguage());
            if (commenter instanceof CodeDocumentationAwareCommenter docs && docs.isDocumentationComment(comment)) {
                return docs.isDocumentationLineComment(comment)
                        ? syntax(DefaultLanguageHighlighterColors.DOC_COMMENT, docs.getDocumentationLineCommentPrefix(), "")
                        : syntax(DefaultLanguageHighlighterColors.DOC_COMMENT, docs.getDocumentationCommentPrefix(),
                                docs.getDocumentationCommentSuffix());
            }
            if (commenter == null) return syntax(DefaultLanguageHighlighterColors.LINE_COMMENT, "", "");
            String block = commenter.getBlockCommentPrefix();
            if (block != null && comment.getText().startsWith(block)) {
                return syntax(DefaultLanguageHighlighterColors.BLOCK_COMMENT, block, commenter.getBlockCommentSuffix());
            }
            return syntax(DefaultLanguageHighlighterColors.LINE_COMMENT, commenter.getLineCommentPrefix(), "");
        }

        private static Syntax syntax(TextAttributesKey base, String prefix, String suffix) {
            return new Syntax(base, prefix == null ? "" : prefix, suffix == null ? "" : suffix);
        }
    }

    private CommentHighlighting() {}

    static Result translate(PsiComment comment, String text) {
        Syntax syntax = Syntax.of(comment);
        if (syntax.prefix.isEmpty()) return new Result(syntax.base, List.of());
        SyntaxHighlighter highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(comment.getLanguage(),
                comment.getProject(), comment.getContainingFile().getVirtualFile());
        if (highlighter == null) return new Result(syntax.base, List.of());
        List<Span> spans = new ArrayList<>();
        if (syntax.isLineComment()) {
            lexLines(highlighter, syntax.prefix, text, spans);
        } else {
            // The translation may keep or drop the outer delimiters; add them only when missing.
            boolean wrapped = text.stripLeading().startsWith(syntax.prefix) && text.stripTrailing().endsWith(syntax.suffix);
            String opening = wrapped ? "" : syntax.prefix + "\n";
            String closing = wrapped ? "" : "\n" + syntax.suffix;
            lex(highlighter, opening, text, closing, 0, spans);
            if (syntax.base == DefaultLanguageHighlighterColors.DOC_COMMENT && "JAVA".equals(comment.getLanguage().getID())) {
                javaReferences(comment, opening, text, closing, spans);
            }
        }
        return new Result(syntax.base, List.copyOf(spans));
    }

    /** Line comments need a fresh delimiter on every line. */
    private static void lexLines(SyntaxHighlighter highlighter, String prefix, String text, List<Span> spans) {
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            boolean hasPrefix = line.stripLeading().startsWith(prefix);
            lex(highlighter, hasPrefix ? "" : prefix + " ", line, "", offset, spans);
            offset += line.length() + 1;
        }
    }

    /** Lexes {@code prefix + text + suffix} and records token keys for the {@code text} part only. */
    private static void lex(SyntaxHighlighter highlighter, String prefix, String text, String suffix, int offset,
                            List<Span> spans) {
        var lexer = highlighter.getHighlightingLexer();
        lexer.start(prefix + text + suffix);
        for (; lexer.getTokenType() != null; lexer.advance()) {
            int start = Math.max(0, lexer.getTokenStart() - prefix.length());
            int end = Math.min(text.length(), lexer.getTokenEnd() - prefix.length());
            if (start >= end) continue;
            var type = lexer.getTokenType();
            TextAttributesKey[] keys = highlighter.getTokenHighlights(type);
            // Kotlin applies KDOC_LINK in BeforeResolveHighlightingVisitor, not its lexer. Use the lexer's
            // link token so inline code and ordinary brackets stay untouched.
            if ("kotlin".equals(type.getLanguage().getID()) && "KDOC_MARKDOWN_LINK".equals(type.toString())) {
                keys = Arrays.copyOf(keys, keys.length + 1);
                keys[keys.length - 1] = KDOC_LINK;
            }
            spans.add(new Span(offset + start, offset + end, keys));
        }
    }

    /** Parses a detached file to tell real Javadoc references from link labels and {@code {@code}} contents. */
    private static void javaReferences(PsiComment source, String prefix, String text, String suffix, List<Span> spans) {
        var file = PsiFileFactory.getInstance(source.getProject()).createFileFromText("CodeI18nDocumentation.java",
                source.getLanguage(), prefix + text + suffix + "\nclass CodeI18nDocumentation {}");
        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                if (!JAVA_DOC_REFERENCE_NODES.contains(element.getNode().getElementType().toString())) {
                    super.visitElement(element);
                    return;
                }
                int start = Math.max(0, element.getTextRange().getStartOffset() - prefix.length());
                int end = Math.min(text.length(), element.getTextRange().getEndOffset() - prefix.length());
                if (start < end) spans.add(new Span(start, end, JAVA_DOC_REFERENCE));
            }
        });
    }
}
