package dev.codei18n.idea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural view of a translated documentation comment, independent of any language plugin: HTML/XML markup is
 * simplified into a concise display text, and references, code, tags and parameter names are marked. Core keeps
 * code and tags verbatim in translations, so the original's documentation syntax applies to the translated text.
 */
final class DocMarkup {
    enum Family { JAVADOC, KDOC, JSDOC, DARTDOC, CSHARP, NONE }

    /** PROSE marks text that must stay plain even where a lexer would style it (e.g. a link label). */
    enum Kind { REFERENCE, CODE, TAG, PARAM_NAME, PROSE }

    record Span(int start, int end, Kind kind) {}

    /** {@code source[i]} is the raw offset of display character {@code i}, or -1 for synthesized text. */
    record Result(String display, List<Span> spans, int[] source) {}

    /** Section labels for C# XML doc structure, in the display language. */
    private record Labels(String param, String typeParam, String returns, String exception, String remarks,
                          String example, String value, String seeAlso) {
        static Labels of(String language, String text) {
            String code = language == null ? "" : language.toLowerCase(Locale.ROOT);
            if (code.isEmpty() || code.equals(LanguageCodes.UNKNOWN)) code = guess(text);
            if (code.startsWith("zh")) return new Labels("参数", "类型参数", "返回", "异常", "备注", "示例", "值", "另请参阅");
            if (code.startsWith("ja")) return new Labels("引数", "型引数", "戻り値", "例外", "備考", "例", "値", "関連項目");
            if (code.startsWith("ko")) return new Labels("매개변수", "형식 매개변수", "반환", "예외", "설명", "예제", "값", "참고");
            return new Labels("Parameter", "Type parameter", "Returns", "Throws", "Remarks", "Example", "Value", "See also");
        }

        private static String guess(String text) {
            boolean han = false;
            for (int i = 0; i < text.length(); i++) {
                Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
                if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) return "ja";
                if (script == Character.UnicodeScript.HANGUL) return "ko";
                han |= script == Character.UnicodeScript.HAN;
            }
            return han ? "zh" : "en";
        }
    }

    private enum Mode { NORMAL, FENCE, EXAMPLE, RAW, BRACE_CODE, LINK_TARGET, LINK_LABEL }
    private enum Expect { NONE, PARAM, REFERENCE }

    private static final Pattern PREFIX = Pattern.compile("[ \\t]*(?:/\\*\\*+(?!/)|/\\*!?|//[/!]?|\\*(?=[ \\t]|$))?[ \\t]?");
    private static final Pattern BLOCK_TAG = Pattern.compile("@([A-Za-z][\\w-]*)(?=[\\s\\[{:]|$)");
    private static final Pattern PARAM_TOKEN = Pattern.compile("\\[[^\\]\\s]+]|<[A-Za-z_$][\\w$]*>|[A-Za-z_$][\\w$.]*");
    private static final Pattern REF_TOKEN = Pattern.compile("(?:module:)?[A-Za-z_$#][\\w$.#~]*(?:\\([^)\\n]*\\))?");
    private static final Pattern INLINE_TAG = Pattern.compile("\\{@([A-Za-z]+)");
    private static final Pattern REF_LINK = Pattern.compile("\\[(?:new )?[A-Za-z_$`][\\w$.#`]*(?:\\([^)\\]\\n]*\\))?]");
    private static final Pattern MARKUP_TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9]*)((?:\\s+[^<>]*?)?)\\s*(/?)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern ENTITY = Pattern.compile("&(?:(lt|gt|amp|quot|apos|nbsp)|#(\\d{1,6})|#[xX]([0-9a-fA-F]{1,6}));");
    private static final Pattern CREF_PREFIX = Pattern.compile("^[A-Z!]:");

    /** HTML tags that separate blocks; shown as a line break (or dropped at a line start). */
    private static final Set<String> HTML_BREAK = Set.of("p", "br", "hr", "div", "ul", "ol", "dl", "dt", "dd", "table",
            "tr", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6");
    /** Inline HTML formatting tags; dropped, their content is kept. */
    private static final Set<String> HTML_DROP = Set.of("b", "i", "em", "strong", "u", "s", "small", "big", "sup", "sub",
            "span", "font", "cite", "dfn", "abbr", "img", "center", "thead", "tbody", "tfoot", "th", "td", "caption");
    private static final Set<String> HTML_CODE = Set.of("code", "tt", "kbd", "samp");

    private DocMarkup() {}

    /** The documentation syntax family from a PSI language id (preferred) or a file name / index.json language id. */
    static Family family(String languageId, String fileName) {
        if (languageId != null) {
            String id = languageId.toLowerCase(Locale.ROOT);
            switch (id) {
                case "java", "groovy" -> { return Family.JAVADOC; }
                case "kotlin" -> { return Family.KDOC; }
                case "dart" -> { return Family.DARTDOC; }
                case "c#", "csharp", "cs" -> { return Family.CSHARP; }
                default -> {
                    if (id.contains("javascript") || id.contains("typescript") || id.contains("ecmascript")
                            || id.contains("jsx") || id.equals("flow js")) {
                        return Family.JSDOC;
                    }
                }
            }
        }
        if (fileName == null) return Family.NONE;
        String name = fileName.toLowerCase(Locale.ROOT);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "java", "groovy" -> Family.JAVADOC;
            case "kt", "kts" -> Family.KDOC;
            case "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts" -> Family.JSDOC;
            case "dart" -> Family.DARTDOC;
            case "cs" -> Family.CSHARP;
            default -> Family.NONE;
        };
    }

    /** Parses a translated documentation comment; {@code language} picks the C# section labels (null: guessed). */
    static Result parse(Family family, String text, String language) {
        if (family == null || family == Family.NONE || text.isEmpty()) {
            int[] identity = new int[text.length()];
            Arrays.setAll(identity, i -> i);
            return new Result(text, List.of(), identity);
        }
        return new Parser(family, text, Labels.of(language, text)).run();
    }

    private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\u3000'; }

    private static final class Parser {
        private final String raw;
        private final Family family;
        private final Labels labels;
        private final boolean markdown, html, xml, blockTags, inlineTags, refs;

        // Display buffer: one source offset, kind and blank-line flag per character.
        private final StringBuilder out = new StringBuilder();
        private int[] source = new int[64];
        private Kind[] kinds = new Kind[64];
        private boolean[] ignorable = new boolean[64];
        /** Per display line: markup was removed from it, so it is dropped if nothing visible is left. */
        private final List<Boolean> dirty = new ArrayList<>(List.of(false));
        private int lineStart;

        private Mode mode = Mode.NORMAL;
        private String closer, fence;
        private boolean preBrace, exampleNext;
        private int depth;
        private Kind textKind;
        private Expect expect = Expect.NONE;
        private boolean expectType;

        Parser(Family family, String raw, Labels labels) {
            this.raw = raw;
            this.family = family;
            this.labels = labels;
            boolean markdownJavadoc = family == Family.JAVADOC && raw.stripLeading().startsWith("///");
            markdown = family == Family.KDOC || family == Family.DARTDOC || family == Family.JSDOC || markdownJavadoc;
            html = family == Family.JAVADOC;
            xml = family == Family.CSHARP;
            blockTags = family == Family.JAVADOC || family == Family.KDOC || family == Family.JSDOC;
            inlineTags = family != Family.CSHARP;
            refs = family == Family.KDOC || family == Family.DARTDOC || markdownJavadoc;
        }

        Result run() {
            int pos = 0;
            while (true) {
                int newline = raw.indexOf('\n', pos);
                int lineEnd = newline < 0 ? raw.length() : newline;
                int contentEnd = lineEnd > pos && raw.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
                Matcher prefix = PREFIX.matcher(raw).region(pos, contentEnd);
                int bodyStart = prefix.lookingAt() ? prefix.end() : pos;
                int bodyEnd = suffixStart(bodyStart, contentEnd);
                // The opening delimiter is visible content; only " * " / "///" style prefixes may vanish with a line.
                boolean opener = raw.startsWith("/*", pos + countSpaces(pos, bodyStart));
                for (int k = pos; k < bodyStart; k++) emit(raw.charAt(k), k, null, !opener);
                line(bodyStart, bodyEnd);
                for (int k = bodyEnd; k < contentEnd; k++) emit(raw.charAt(k), k, null, false);
                for (int k = contentEnd; k < lineEnd; k++) emit(raw.charAt(k), k, null, true);
                if (newline < 0) break;
                emit('\n', newline, null, true);
                pos = newline + 1;
            }
            return finish();
        }

        private int countSpaces(int from, int to) {
            int n = 0;
            while (from + n < to && (raw.charAt(from + n) == ' ' || raw.charAt(from + n) == '\t')) n++;
            return n;
        }

        /** Start of a trailing {@code *}{@code /} (with surrounding blanks), or {@code end}. */
        private int suffixStart(int start, int end) {
            int j = end;
            while (j > start && isSpace(raw.charAt(j - 1))) j--;
            if (j - start < 2 || raw.charAt(j - 1) != '/' || raw.charAt(j - 2) != '*') return end;
            int k = j - 2;
            while (k > start && raw.charAt(k - 1) == '*') k--;
            while (k > start && isSpace(raw.charAt(k - 1))) k--;
            return k;
        }

        /** One line of comment body, without prefix and suffix. Multi-line constructs keep their state across lines. */
        private void line(int start, int end) {
            String trimmed = raw.substring(start, end).stripLeading();
            int lead = end - start - trimmed.length();
            expect = Expect.NONE;
            expectType = false;
            if (mode == Mode.FENCE) {
                copy(start, end, Kind.CODE);
                if (trimmed.startsWith(fence)) mode = Mode.NORMAL;
                return;
            }
            if (mode == Mode.EXAMPLE) {
                if (!BLOCK_TAG.matcher(trimmed).lookingAt()) {
                    copy(start, end, Kind.CODE);
                    return;
                }
                mode = Mode.NORMAL;
            }
            int i = start;
            if (mode == Mode.NORMAL && markdown && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                fence = trimmed.substring(0, 3);
                mode = Mode.FENCE;
                copy(start, end, Kind.CODE);
                return;
            }
            if (mode == Mode.NORMAL && blockTags) {
                Matcher tag = BLOCK_TAG.matcher(raw).region(start + lead, end);
                if (tag.lookingAt()) {
                    copy(start, start + lead, null);
                    copy(tag.start(), tag.end(), Kind.TAG);
                    i = tag.end();
                    blockTag(tag.group(1).toLowerCase(Locale.ROOT));
                }
            }
            if (exampleNext) {
                exampleNext = false;
                copy(i, end, Kind.CODE); // JSDoc @example: code may start right after the tag
                mode = Mode.EXAMPLE;
                return;
            }
            inline(i, end);
        }

        private void blockTag(String name) {
            boolean js = family == Family.JSDOC;
            switch (name) {
                case "param", "property", "prop", "arg", "argument", "typeparam", "tparam", "template" -> {
                    expectType = js;
                    expect = Expect.PARAM;
                }
                case "throws", "exception", "returns", "return", "type", "yields", "enum", "typedef" -> {
                    expectType = js;
                    expect = js ? Expect.NONE : name.startsWith("return") ? Expect.NONE : Expect.REFERENCE;
                }
                case "see", "sample", "extends", "augments", "implements" -> {
                    expectType = js;
                    expect = Expect.REFERENCE;
                }
                case "example" -> exampleNext = js;
                default -> { }
            }
        }

        private void inline(int i, int end) {
            while (i < end) {
                switch (mode) {
                    case RAW -> { i = rawCode(i, end); continue; }
                    case BRACE_CODE -> { i = braceCode(i, end); continue; }
                    case LINK_TARGET -> { i = linkTarget(i, end); continue; }
                    case LINK_LABEL -> { i = linkLabel(i, end); continue; }
                    default -> { }
                }
                char c = raw.charAt(i);
                if (isSpace(c)) {
                    copy(i, i + 1, textKind);
                    i++;
                    continue;
                }
                if (expectType && c == '{' && (i + 1 >= end || raw.charAt(i + 1) != '@')) {
                    int close = matchingBrace(i, end);
                    if (close > i) {
                        copy(i, close + 1, Kind.REFERENCE);
                        i = close + 1;
                        expectType = false;
                        continue;
                    }
                }
                expectType = false;
                if (expect != Expect.NONE) {
                    Expect expected = expect;
                    expect = Expect.NONE;
                    Matcher token = (expected == Expect.PARAM ? PARAM_TOKEN : REF_TOKEN).matcher(raw).region(i, end);
                    if (token.lookingAt() && token.end() > i) {
                        copy(i, token.end(), expected == Expect.PARAM ? Kind.PARAM_NAME : Kind.REFERENCE);
                        i = token.end();
                        continue;
                    }
                }
                int next = i;
                if (c == '`' && markdown) next = inlineCode(i, end);
                else if (c == '{' && inlineTags) next = inlineTag(i, end);
                else if (c == '[' && refs) next = reference(i, end);
                else if (c == '<' && (html || xml)) next = xml ? xmlTag(i, end) : htmlTag(i, end);
                else if (c == '&' && (html || xml)) next = entity(i, end, textKind);
                if (next > i) {
                    i = next;
                    continue;
                }
                copy(i, i + 1, textKind);
                i++;
            }
        }

        private int matchingBrace(int open, int end) {
            int level = 0;
            for (int k = open; k < end; k++) {
                char c = raw.charAt(k);
                if (c == '{') level++;
                else if (c == '}' && --level == 0) return k;
            }
            return -1;
        }

        /** Markdown inline code: a backtick run closed by a run of the same length on the same line. */
        private int inlineCode(int i, int end) {
            int run = 0;
            while (i + run < end && raw.charAt(i + run) == '`') run++;
            int close = raw.indexOf("`".repeat(run), i + run);
            if (close < 0 || close + run > end) return i;
            copy(i, close + run, Kind.CODE);
            return close + run;
        }

        private int inlineTag(int i, int end) {
            Matcher tag = INLINE_TAG.matcher(raw).region(i, end);
            if (!tag.lookingAt()) return i;
            copy(i, tag.end(), Kind.TAG);
            mode = switch (tag.group(1).toLowerCase(Locale.ROOT)) {
                case "code", "literal" -> {
                    depth = 1;
                    yield Mode.BRACE_CODE;
                }
                case "link", "linkplain", "linkcode", "value", "see" -> Mode.LINK_TARGET;
                default -> Mode.LINK_LABEL;
            };
            int k = tag.end();
            if (mode == Mode.BRACE_CODE && k < end && raw.charAt(k) == ' ') copy(k, ++k, null);
            return k;
        }

        /** Body of {@code {@code ...}}: verbatim up to the balancing brace, possibly over several lines. */
        private int braceCode(int i, int end) {
            for (int k = i; k < end; k++) {
                char c = raw.charAt(k);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) {
                    copy(i, k, Kind.CODE);
                    copy(k, k + 1, Kind.TAG);
                    mode = Mode.NORMAL;
                    return k + 1;
                }
            }
            copy(i, end, Kind.CODE);
            return end;
        }

        private int linkTarget(int i, int end) {
            int k = i;
            while (k < end && isSpace(raw.charAt(k))) k++;
            copy(i, k, null);
            if (k >= end) return end;
            if (raw.charAt(k) == '}') return closeTag(k);
            int parens = 0, start = k;
            for (; k < end; k++) {
                char c = raw.charAt(k);
                if (c == '(') parens++;
                else if (c == ')') parens--;
                else if (c == '}' || c == '|' || (isSpace(c) && parens <= 0)) break;
            }
            copy(start, k, Kind.REFERENCE);
            mode = Mode.LINK_LABEL;
            return k;
        }

        private int linkLabel(int i, int end) {
            for (int k = i; k < end; k++) {
                char c = raw.charAt(k);
                if (c == '|') {
                    copy(i, k, Kind.PROSE);
                    copy(k, k + 1, Kind.TAG);
                    return k + 1;
                }
                if (c == '}') {
                    copy(i, k, Kind.PROSE);
                    return closeTag(k);
                }
            }
            copy(i, end, Kind.PROSE);
            return end;
        }

        private int closeTag(int k) {
            copy(k, k + 1, Kind.TAG);
            mode = Mode.NORMAL;
            return k + 1;
        }

        /** KDoc / Dartdoc / Markdown-Javadoc {@code [ref]}; Markdown links {@code [text](url)} stay prose. */
        private int reference(int i, int end) {
            Matcher ref = REF_LINK.matcher(raw).region(i, end);
            if (!ref.lookingAt() || (ref.end() < end && raw.charAt(ref.end()) == '(')) return i;
            copy(i, ref.end(), Kind.REFERENCE);
            return ref.end();
        }

        private int htmlTag(int i, int end) {
            Matcher tag = MARKUP_TAG.matcher(raw).region(i, end);
            if (!tag.lookingAt()) return i;
            String original = tag.group(2);
            // Box<U>, Foo<I>: single upper-case letters are type parameters, not <u>/<i> (legacy <P> still is HTML).
            if (original.length() == 1 && Character.isUpperCase(original.charAt(0)) && original.charAt(0) != 'P') return i;
            String name = original.toLowerCase(Locale.ROOT);
            boolean closing = !tag.group(1).isEmpty(), selfClosing = !tag.group(4).isEmpty();
            int next = tag.end();
            if (HTML_BREAK.contains(name)) {
                return lineBreak(next, end);
            } else if (HTML_DROP.contains(name)) {
                drop();
            } else if (HTML_CODE.contains(name) || name.equals("pre")) {
                drop();
                if (closing || selfClosing) return next;
                startRaw("</" + name + ">");
                if (name.equals("pre")) {
                    Matcher brace = INLINE_TAG.matcher(raw).region(skipSpaces(next, end), end);
                    if (brace.lookingAt() && brace.group(1).equals("code")) {
                        preBrace = true;
                        return brace.end();
                    }
                }
            } else if (name.equals("a")) {
                drop();
                textKind = closing ? null : Kind.REFERENCE;
            } else if (name.equals("li")) {
                if (!closing) bullet();
                else drop();
            } else {
                return i;
            }
            return next;
        }

        private int xmlTag(int i, int end) {
            Matcher tag = MARKUP_TAG.matcher(raw).region(i, end);
            if (!tag.lookingAt()) return i;
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            boolean closing = !tag.group(1).isEmpty(), selfClosing = !tag.group(4).isEmpty();
            String attributes = tag.group(3);
            int next = tag.end();
            switch (name) {
                case "summary", "list", "listheader", "term", "description", "permission" -> drop();
                case "para", "br" -> { return lineBreak(next, end); }
                case "item" -> {
                    if (closing) drop();
                    else bullet();
                }
                case "c", "code" -> {
                    drop();
                    if (!closing && !selfClosing) startRaw("</" + tag.group(2) + ">");
                }
                case "param", "typeparam" -> {
                    if (closing) drop();
                    else return label(name.equals("param") ? labels.param : labels.typeParam,
                            attribute(attributes, "name"), Kind.PARAM_NAME, next, end);
                }
                case "returns", "value", "remarks", "example" -> {
                    if (closing) drop();
                    else return label(switch (name) {
                        case "returns" -> labels.returns;
                        case "value" -> labels.value;
                        case "remarks" -> labels.remarks;
                        default -> labels.example;
                    }, null, null, next, end);
                }
                case "exception" -> {
                    if (closing) drop();
                    else return label(labels.exception, cref(attribute(attributes, "cref")), Kind.REFERENCE, next, end);
                }
                case "seealso" -> {
                    if (closing) {
                        drop();
                        textKind = null;
                    } else {
                        String target = cref(attribute(attributes, "cref"));
                        if (target == null) target = attribute(attributes, "href");
                        int after = label(labels.seeAlso, null, null, next, end);
                        if (selfClosing) {
                            if (target != null) insert(target, Kind.REFERENCE);
                        } else {
                            textKind = Kind.REFERENCE;
                        }
                        return after;
                    }
                }
                case "see" -> {
                    if (closing) {
                        drop();
                        textKind = null;
                    } else if (selfClosing) {
                        String word = attribute(attributes, "langword");
                        if (word != null) {
                            insert(word, Kind.CODE);
                        } else {
                            String target = cref(attribute(attributes, "cref"));
                            if (target == null) target = attribute(attributes, "href");
                            if (target != null) insert(target, Kind.REFERENCE);
                            else drop();
                        }
                    } else {
                        drop();
                        textKind = Kind.REFERENCE;
                    }
                }
                case "paramref", "typeparamref" -> {
                    String target = attribute(attributes, "name");
                    if (target != null) insert(target, Kind.REFERENCE);
                    else drop();
                }
                case "inheritdoc", "include" -> copy(i, next, Kind.TAG);
                default -> { return i; }
            }
            return next;
        }

        /** Starts a C# section on its own line: "参数 a: …" / "返回: …". */
        private int label(String text, String value, Kind valueKind, int next, int end) {
            if (hasContent()) newline();
            else drop();
            insert(text, Kind.TAG);
            if (value != null) {
                insert(" ", null);
                insert(value, valueKind);
            }
            insert(":", Kind.TAG);
            insert(" ", null);
            return skipSpaces(next, end);
        }

        private int lineBreak(int next, int end) {
            if (hasContent()) {
                newline();
                return skipSpaces(next, end);
            }
            drop();
            return next;
        }

        private void bullet() {
            if (hasContent()) newline();
            else drop();
            insert("• ", null);
        }

        private int skipSpaces(int k, int end) {
            while (k < end && isSpace(raw.charAt(k))) k++;
            return k;
        }

        private void startRaw(String closingTag) {
            closer = closingTag;
            mode = Mode.RAW;
        }

        /** Content of {@code <code>}, {@code <pre>} or {@code <c>}: verbatim (entities decoded) up to the closer. */
        private int rawCode(int i, int end) {
            int close = indexOfIgnoreCase(raw, closer, i, end);
            int stop = close < 0 ? end : close;
            int codeEnd = stop;
            if (close >= 0 && preBrace) {
                int k = stop;
                while (k > i && isSpace(raw.charAt(k - 1))) k--;
                if (k > i && raw.charAt(k - 1) == '}') codeEnd = k - 1; // <pre>{@code ...}</pre>
            }
            for (int k = i; k < codeEnd; ) {
                int decoded = raw.charAt(k) == '&' ? entity(k, codeEnd, Kind.CODE) : k;
                if (decoded > k) {
                    k = decoded;
                } else {
                    copy(k, k + 1, Kind.CODE);
                    k++;
                }
            }
            if (codeEnd < stop) copy(codeEnd + 1, stop, null);
            if (close < 0) return end;
            drop();
            mode = Mode.NORMAL;
            preBrace = false;
            return close + closer.length();
        }

        private int entity(int i, int end, Kind kind) {
            Matcher entity = ENTITY.matcher(raw).region(i, end);
            if (!entity.lookingAt()) return i;
            int cp;
            if (entity.group(1) != null) {
                cp = switch (entity.group(1)) {
                    case "lt" -> '<';
                    case "gt" -> '>';
                    case "amp" -> '&';
                    case "quot" -> '"';
                    case "apos" -> '\'';
                    default -> ' ';
                };
            } else {
                cp = entity.group(2) != null ? Integer.parseInt(entity.group(2)) : Integer.parseInt(entity.group(3), 16);
                if (!Character.isValidCodePoint(cp) || cp == 0) return i;
            }
            insert(Character.toString(cp), kind);
            return entity.end();
        }

        private static String attribute(String attributes, String name) {
            Matcher attribute = ATTRIBUTE.matcher(attributes);
            while (attribute.find()) {
                if (attribute.group(1).equalsIgnoreCase(name)) {
                    return attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
                }
            }
            return null;
        }

        /** "T:System.String" → "System.String". */
        private static String cref(String value) {
            return value == null ? null : CREF_PREFIX.matcher(value).replaceFirst("");
        }

        private static int indexOfIgnoreCase(String text, String needle, int from, int end) {
            for (int k = from; k + needle.length() <= end; k++) {
                if (text.regionMatches(true, k, needle, 0, needle.length())) return k;
            }
            return -1;
        }

        // ---- display buffer ----

        private void emit(char c, int src, Kind kind, boolean blank) {
            int n = out.length();
            if (n == source.length) {
                source = Arrays.copyOf(source, n * 2);
                kinds = Arrays.copyOf(kinds, n * 2);
                ignorable = Arrays.copyOf(ignorable, n * 2);
            }
            source[n] = src;
            kinds[n] = kind;
            ignorable[n] = blank;
            out.append(c);
            if (c == '\n') {
                dirty.add(false);
                lineStart = out.length();
            }
        }

        private void copy(int from, int to, Kind kind) {
            for (int k = from; k < to; k++) emit(raw.charAt(k), k, kind, false);
        }

        private void insert(String text, Kind kind) {
            for (int k = 0; k < text.length(); k++) emit(text.charAt(k), -1, kind, false);
        }

        private void newline() {
            emit('\n', -1, null, true);
            drop();
        }

        private void drop() { dirty.set(dirty.size() - 1, true); }

        private boolean hasContent() {
            for (int k = lineStart; k < out.length(); k++) {
                if (!ignorable[k] && !isSpace(out.charAt(k))) return true;
            }
            return false;
        }

        /** Drops lines left blank by removed markup, then turns the per-character kinds into spans. */
        private Result finish() {
            StringBuilder display = new StringBuilder(out.length());
            int[] src = new int[out.length()];
            Kind[] kind = new Kind[out.length()];
            int n = 0, line = 0;
            for (int start = 0; start <= out.length(); line++) {
                int newline = out.indexOf("\n", start);
                int end = newline < 0 ? out.length() : newline;
                boolean blank = dirty.get(line);
                for (int k = start; blank && k < end; k++) blank = ignorable[k] || isSpace(out.charAt(k));
                if (!blank) {
                    int stop = newline < 0 ? end : end + 1;
                    for (int k = start; k < stop; k++) {
                        display.append(out.charAt(k));
                        src[n] = source[k];
                        kind[n++] = kinds[k];
                    }
                } else if (newline < 0 && n > 0 && display.charAt(n - 1) == '\n') {
                    // The last line vanished: drop the line break (and CR) that led to it.
                    int cut = n - 1;
                    if (cut > 0 && display.charAt(cut - 1) == '\r') cut--;
                    display.setLength(cut);
                    n = cut;
                }
                if (newline < 0) break;
                start = newline + 1;
            }
            List<Span> spans = new ArrayList<>();
            for (int k = 0; k < n; ) {
                int j = k;
                while (j < n && kind[j] == kind[k]) j++;
                if (kind[k] != null) spans.add(new Span(k, j, kind[k]));
                k = j;
            }
            return new Result(display.toString(), List.copyOf(spans), Arrays.copyOf(src, n));
        }
    }
}
