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

/**
 * Lex the translation in the source comment's language, without changing the source PSI, and overlay the
 * plugin-independent documentation structure from {@link DocMarkup}.
 */
final class CommentHighlighting {
    record Span(int start, int end, TextAttributesKey[] keys) {}

    /**
     * {@code display} is the text to paint when markup was simplified (e.g. C# XML tags removed), or null when it is
     * the raw translation; {@code spans} index the painted text.
     */
    record Result(TextAttributesKey base, String display, List<Span> spans) {
        Result(TextAttributesKey base, List<Span> spans) { this(base, null, spans); }

        String displayOf(String raw) { return display == null ? raw : display; }
    }

    // Keys and node names of optional language plugins, looked up by name to avoid hard dependencies.
    private static final TextAttributesKey KDOC_LINK =
            TextAttributesKey.createTextAttributesKey("KDOC_LINK", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE);
    private static final TextAttributesKey[] JAVA_DOC_REFERENCE = {
            TextAttributesKey.createTextAttributesKey("JAVA_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT),
            TextAttributesKey.createTextAttributesKey("DOC_COMMENT_TAG_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE)};
    private static final Set<String> JAVA_DOC_REFERENCE_NODES =
            Set.of("DOC_REFERENCE_HOLDER", "DOC_METHOD_OR_FIELD_REF", "DOC_PARAMETER_REF");
    /** Keys that only say "this is a comment"; any other lexer key beats DocMarkup's generic styling. */
    private static final Set<TextAttributesKey> COMMENT_BASES = Set.of(DefaultLanguageHighlighterColors.DOC_COMMENT,
            DefaultLanguageHighlighterColors.LINE_COMMENT, DefaultLanguageHighlighterColors.BLOCK_COMMENT);

    /** How the source comment is delimited; an empty prefix means the language has no usable commenter. */
    private record Syntax(TextAttributesKey base, String prefix, String suffix) {
        boolean isLineComment() { return suffix.isEmpty(); }

        static Syntax of(PsiComment comment) {
            Commenter commenter = LanguageCommenters.INSTANCE.forLanguage(comment.getLanguage());
            if (commenter instanceof CodeDocumentationAwareCommenter docs && docs.isDocumentationComment(comment)) {
                return PlatformCompat.isDocumentationLineComment(docs, comment)
                        ? syntax(DefaultLanguageHighlighterColors.DOC_COMMENT, PlatformCompat.documentationLineCommentPrefix(docs), "")
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

    /** Plain comment styling by index.json type, for translations located without comment PSI. */
    static Result plain(String type) {
        TextAttributesKey base = switch (type == null ? "" : type) {
            case "doc" -> DefaultLanguageHighlighterColors.DOC_COMMENT;
            case "block" -> DefaultLanguageHighlighterColors.BLOCK_COMMENT;
            default -> DefaultLanguageHighlighterColors.LINE_COMMENT;
        };
        return new Result(base, List.of());
    }

    /**
     * Comment styling for translations located without comment PSI (C2): no lexer, but the documentation structure
     * is derived from the file name, e.g. Dart without the Dart plugin.
     */
    static Result plain(String type, String text, String fileName, String language) {
        DocMarkup.Family family = DocMarkup.family(null, fileName);
        if (family == DocMarkup.Family.NONE || (!"doc".equals(type) && !looksLikeDoc(text, family))) return plain(type);
        return combine(DefaultLanguageHighlighterColors.DOC_COMMENT, text, List.of(), family,
                DocMarkup.parse(family, text, language));
    }

    static Result translate(PsiComment comment, String text) { return translate(comment, text, null); }

    /** {@code language} is the display language (C# section labels); null guesses it from the text. */
    static Result translate(PsiComment comment, String text, String language) {
        Syntax syntax = Syntax.of(comment);
        var file = comment.getContainingFile();
        String fileName = file.getVirtualFile() != null ? file.getVirtualFile().getName() : file.getName();
        DocMarkup.Family family = DocMarkup.family(comment.getLanguage().getID(), fileName);
        boolean doc = syntax.base == DefaultLanguageHighlighterColors.DOC_COMMENT;
        // C# in Rider has no documentation-aware commenter (262) or none at all (251); Dart may lack its plugin.
        boolean forcedDoc = !doc && looksLikeDoc(comment.getText(), family);
        List<Span> lexed = forcedDoc ? List.of() : lex(comment, syntax, text);
        if (!doc && !forcedDoc) return new Result(syntax.base, lexed);
        return combine(DefaultLanguageHighlighterColors.DOC_COMMENT, text, lexed, family,
                DocMarkup.parse(family, text, language));
    }

    private static boolean looksLikeDoc(String text, DocMarkup.Family family) {
        String start = text.stripLeading();
        if (start.startsWith("/**") && !start.startsWith("/**/")) return family != DocMarkup.Family.NONE;
        return start.startsWith("///") && (family == DocMarkup.Family.CSHARP || family == DocMarkup.Family.DARTDOC);
    }

    private static List<Span> lex(PsiComment comment, Syntax syntax, String text) {
        if (syntax.prefix.isEmpty()) return List.of();
        SyntaxHighlighter highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(comment.getLanguage(),
                comment.getProject(), comment.getContainingFile().getVirtualFile());
        if (highlighter == null) return List.of();
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
        return List.copyOf(spans);
    }

    /**
     * Maps lexer spans (raw offsets, later spans win) onto the markup's display text. Per character a specific lexer
     * key (tag, reference, ...) beats the markup's generic key, except in code and link labels, which stay verbatim.
     */
    static Result combine(TextAttributesKey base, String text, List<Span> lexed, DocMarkup.Family family,
                          DocMarkup.Result markup) {
        String display = markup.display();
        if (markup.spans().isEmpty() && display.equals(text)) return new Result(base, lexed);
        TextAttributesKey[][] raw = new TextAttributesKey[text.length()][];
        for (Span span : lexed) Arrays.fill(raw, span.start(), span.end(), span.keys());
        DocMarkup.Kind[] kinds = new DocMarkup.Kind[display.length()];
        for (DocMarkup.Span span : markup.spans()) Arrays.fill(kinds, span.start(), span.end(), span.kind());
        List<Span> spans = new ArrayList<>();
        TextAttributesKey[] run = null;
        int runStart = 0;
        for (int i = 0; i <= display.length(); i++) {
            TextAttributesKey[] keys = null;
            if (i < display.length()) {
                int source = markup.source()[i];
                keys = keys(source >= 0 ? raw[source] : null, kinds[i], family);
            }
            if (i == display.length() || !Arrays.equals(keys, run)) {
                if (run != null) spans.add(new Span(runStart, i, run));
                run = keys;
                runStart = i;
            }
        }
        return new Result(base, display.equals(text) ? null : display, List.copyOf(spans));
    }

    private static TextAttributesKey[] keys(TextAttributesKey[] lexed, DocMarkup.Kind kind, DocMarkup.Family family) {
        if (kind == null) return lexed == null || lexed.length == 0 ? null : lexed;
        TextAttributesKey[] general = lexed == null ? new TextAttributesKey[0]
                : Arrays.stream(lexed).filter(CommentHighlighting::isCommentBase).toArray(TextAttributesKey[]::new);
        boolean verbatim = kind == DocMarkup.Kind.CODE || kind == DocMarkup.Kind.PROSE;
        if (!verbatim && lexed != null && general.length < lexed.length) return lexed;
        TextAttributesKey key = switch (kind) {
            case REFERENCE -> family == DocMarkup.Family.KDOC ? KDOC_LINK : DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE;
            case PARAM_NAME -> DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE;
            case CODE -> DefaultLanguageHighlighterColors.DOC_COMMENT_MARKUP;
            case TAG -> DefaultLanguageHighlighterColors.DOC_COMMENT_TAG;
            case PROSE -> null;
        };
        if (key == null) return general.length == 0 ? null : general;
        TextAttributesKey[] keys = Arrays.copyOf(general, general.length + 1);
        keys[general.length] = key;
        return keys;
    }

    /** Whether the key falls back to a plain comment color without passing a doc-specific key. */
    private static boolean isCommentBase(TextAttributesKey key) {
        for (int i = 0; key != null && i < 16; i++, key = key.getFallbackAttributeKey()) {
            if (COMMENT_BASES.contains(key)) return true;
        }
        return false;
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
        boolean[] inCode = inlineCode(text);
        lexer.start(prefix + text + suffix);
        for (; lexer.getTokenType() != null; lexer.advance()) {
            int start = Math.max(0, lexer.getTokenStart() - prefix.length());
            int end = Math.min(text.length(), lexer.getTokenEnd() - prefix.length());
            if (start >= end) continue;
            var type = lexer.getTokenType();
            TextAttributesKey[] keys = highlighter.getTokenHighlights(type);
            // Kotlin applies KDOC_LINK in BeforeResolveHighlightingVisitor, not its lexer. Use the lexer's
            // link token so inline code and ordinary brackets stay untouched.
            // The 251 KDoc lexer has no inline-code token, so [x] inside `code` is also lexed as a link:
            // never style links that fall inside a backtick code span.
            if ("kotlin".equals(type.getLanguage().getID()) && "KDOC_MARKDOWN_LINK".equals(type.toString())
                    && !inCode[start]) {
                keys = Arrays.copyOf(keys, keys.length + 1);
                keys[keys.length - 1] = KDOC_LINK;
            }
            spans.add(new Span(offset + start, offset + end, keys));
        }
    }

    /** Marks characters inside a backtick code span on one line (Markdown inline code). */
    static boolean[] inlineCode(String text) {
        boolean[] inside = new boolean[text.length() + 1];
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                open = -1; // inline code never spans lines
            } else if (c == '`') {
                if (open < 0) {
                    open = i;
                } else {
                    Arrays.fill(inside, open, i + 1, true);
                    open = -1;
                }
            }
        }
        return inside;
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
