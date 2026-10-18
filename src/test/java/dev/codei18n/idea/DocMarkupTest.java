package dev.codei18n.idea;

import java.util.Objects;

/** DocMarkup (C3): structural rendering of translated doc comments for each syntax family. Run from MappingIndexTest. */
final class DocMarkupTest {
    private static int assertions;

    static int run() {
        families();
        javadoc();
        javadocHtml();
        kdoc();
        jsdoc();
        dartdoc();
        csharp();
        crlf();
        untouched();
        System.out.println("PASS: " + assertions + " doc markup assertions");
        return assertions;
    }

    private static void families() {
        equal(DocMarkup.Family.JAVADOC, DocMarkup.family("JAVA", "A.java"));
        equal(DocMarkup.Family.KDOC, DocMarkup.family("kotlin", null));
        equal(DocMarkup.Family.JSDOC, DocMarkup.family("TypeScript JSX", null));
        equal(DocMarkup.Family.JSDOC, DocMarkup.family("ECMAScript 6", null));
        equal(DocMarkup.Family.CSHARP, DocMarkup.family("C#", null));
        // Without the language plugin the file is plain text: fall back to the extension.
        equal(DocMarkup.Family.DARTDOC, DocMarkup.family("TEXT", "lib/src/a.dart"));
        equal(DocMarkup.Family.CSHARP, DocMarkup.family(null, "src/Calc.cs"));
        equal(DocMarkup.Family.JSDOC, DocMarkup.family(null, "web/app.mts"));
        equal(DocMarkup.Family.NONE, DocMarkup.family(null, "README"));
    }

    private static void javadoc() {
        String raw = "/**\n * 返回 {@code sum} 的值。\n * @param a 第一个 {@link Number}\n */";
        equal("/**\n * 返回 ⟦T|{@code⟧ ⟦C|sum⟧⟦T|}⟧ 的值。\n * ⟦T|@param⟧ ⟦P|a⟧ 第一个 ⟦T|{@link⟧ ⟦R|Number⟧⟦T|}⟧\n */",
                annotate(DocMarkup.Family.JAVADOC, raw));
        // Body without delimiters, label and nested braces in code.
        equal("见 ⟦T|{@linkplain⟧ ⟦R|java.util.List⟧⟦S| 列表⟧⟦T|}⟧ 与 ⟦T|{@code⟧ ⟦C|Map<K, {V}>⟧⟦T|}⟧\n"
                        + "⟦T|@throws⟧ ⟦R|IOException⟧ 读取失败\n⟦T|@return⟧ 总和",
                annotate(DocMarkup.Family.JAVADOC, "见 {@linkplain java.util.List 列表} 与 {@code Map<K, {V}>}\n"
                        + "@throws IOException 读取失败\n@return 总和"));
        // {@code} spanning lines keeps the prefix plain.
        equal("/**\n * ⟦T|{@code⟧ ⟦C|a(⟧\n * ⟦C|b)⟧⟦T|}⟧ 完成\n */",
                annotate(DocMarkup.Family.JAVADOC, "/**\n * {@code a(\n * b)} 完成\n */"));
        // Java 23 Markdown doc comments.
        equal("/// 参见 ⟦R|[String#length()]⟧ 与 ⟦C|`[x]`⟧", annotate(DocMarkup.Family.JAVADOC, "/// 参见 [String#length()] 与 `[x]`"));
    }

    private static void javadocHtml() {
        String raw = "/**\n * 计算总和。\n * <p>\n * 使用 <code>a[0]</code> 和 <b>粗体</b>，a &lt; b。<br>第二行\n"
                + " * <pre>{@code\n * int x = a[0]; // {@link X}\n * }</pre>\n"
                + " * <ul><li>甲</li><li>乙</li></ul>\n"
                + " * @throws IllegalStateException 状态错误\n * @see String#length()\n * @param <T> 元素类型\n */";
        equal("/**\n * 计算总和。\n * 使用 ⟦C|a[0]⟧ 和 粗体，a < b。\n第二行\n * ⟦C|int x = a[0]; // {@link X}⟧\n"
                        + " * • 甲\n• 乙\n"
                        + " * ⟦T|@throws⟧ ⟦R|IllegalStateException⟧ 状态错误\n * ⟦T|@see⟧ ⟦R|String#length()⟧\n"
                        + " * ⟦T|@param⟧ ⟦P|<T>⟧ 元素类型\n */",
                annotate(DocMarkup.Family.JAVADOC, raw));
        var result = DocMarkup.parse(DocMarkup.Family.JAVADOC, raw, "zh-CN");
        equal(raw.indexOf("a[0]"), result.source()[result.display().indexOf("a[0]")]);
        equal(-1, result.source()[result.display().indexOf('<')]); // decoded entity is synthesized
        // <a href> text is a reference; generic angle brackets that are not HTML stay verbatim.
        equal("见 ⟦R|文档⟧，List<String> 不变", annotate(DocMarkup.Family.JAVADOC,
                "见 <a href=\"https://x\">文档</a>，List<String> 不变"));
        equal("返回 Box<U> 或 Foo<I>；旧式\n段落", annotate(DocMarkup.Family.JAVADOC, "返回 Box<U> 或 Foo<I>；旧式<P>段落"));
        equal("<pre> 不在 Javadoc 外部生效",annotate(DocMarkup.Family.KDOC, "<pre> 不在 Javadoc 外部生效"));
    }

    private static void kdoc() {
        String raw = "/**\n * 从缓存解析 [versions]，参见 [Foo.bar]。\n * `code [notALink]` 与 [链接](https://x.y)\n"
                + " * ```kotlin\n * val x = list[0] // [x]\n * ```\n * @param value 输入值\n * @property name 名称\n"
                + " * @return 结果\n * @throws IllegalStateException 失败时\n * @sample com.example.sampleFoo\n */";
        equal("/**\n * 从缓存解析 ⟦R|[versions]⟧，参见 ⟦R|[Foo.bar]⟧。\n * ⟦C|`code [notALink]`⟧ 与 [链接](https://x.y)\n"
                        + " * ⟦C|```kotlin⟧\n * ⟦C|val x = list[0] // [x]⟧\n * ⟦C|```⟧\n * ⟦T|@param⟧ ⟦P|value⟧ 输入值\n"
                        + " * ⟦T|@property⟧ ⟦P|name⟧ 名称\n * ⟦T|@return⟧ 结果\n * ⟦T|@throws⟧ ⟦R|IllegalStateException⟧ 失败时\n"
                        + " * ⟦T|@sample⟧ ⟦R|com.example.sampleFoo⟧\n */",
                annotate(DocMarkup.Family.KDOC, raw));
        equal("⟦C|``a ` [b]``⟧ 与 ⟦R|[c]⟧", annotate(DocMarkup.Family.KDOC, "``a ` [b]`` 与 [c]"));
    }

    private static void jsdoc() {
        String raw = "/**\n * 把两个数相加，见 {@link Calculator#add | 加法}。\n * @param {number} a 第一个数\n"
                + " * @param {number} [b=0] 第二个数\n * @returns {number} 两数之和\n * @remarks 使用 `a + b` 计算。\n"
                + " * @example\n * add(1, 2); // {@link x} [y]\n * @throws {RangeError} 溢出时\n */";
        equal("/**\n * 把两个数相加，见 ⟦T|{@link⟧ ⟦R|Calculator#add⟧⟦S| ⟧⟦T||⟧⟦S| 加法⟧⟦T|}⟧。\n"
                        + " * ⟦T|@param⟧ ⟦R|{number}⟧ ⟦P|a⟧ 第一个数\n * ⟦T|@param⟧ ⟦R|{number}⟧ ⟦P|[b=0]⟧ 第二个数\n"
                        + " * ⟦T|@returns⟧ ⟦R|{number}⟧ 两数之和\n * ⟦T|@remarks⟧ 使用 ⟦C|`a + b`⟧ 计算。\n"
                        + " * ⟦T|@example⟧\n * ⟦C|add(1, 2); // {@link x} [y]⟧\n * ⟦T|@throws⟧ ⟦R|{RangeError}⟧ 溢出时\n */",
                annotate(DocMarkup.Family.JSDOC, raw));
        equal("⟦T|@param⟧ ⟦P|options⟧ 选项，类型为 ⟦T|{@link⟧ ⟦R|Options⟧⟦T||⟧⟦S|配置⟧⟦T|}⟧",
                annotate(DocMarkup.Family.JSDOC, "@param options 选项，类型为 {@link Options|配置}"));
    }

    private static void dartdoc() {
        String raw = "/// 返回 [Foo] 的副本，参见 [new Bar]。\n/// 使用 `list[0]` 访问。\n///\n/// ```dart\n/// var x = [1, 2];\n/// ```";
        equal("/// 返回 ⟦R|[Foo]⟧ 的副本，参见 ⟦R|[new Bar]⟧。\n/// 使用 ⟦C|`list[0]`⟧ 访问。\n///\n"
                        + "/// ⟦C|```dart⟧\n/// ⟦C|var x = [1, 2];⟧\n/// ⟦C|```⟧",
                annotate(DocMarkup.Family.DARTDOC, raw));
        // Dartdoc has no @tags: an at-sign stays prose.
        equal("/// 发送到 @param 不是标签", annotate(DocMarkup.Family.DARTDOC, "/// 发送到 @param 不是标签"));
    }

    private static final String CSHARP = "/// <summary>\n/// 把 <paramref name=\"a\"/> 加到 <c>b</c>，参见 <see cref=\"T:System.Math\"/>。\n"
            + "/// </summary>\n/// <param name=\"a\">第一个数</param>\n/// <returns>和，或 <see langword=\"null\"/>。</returns>\n"
            + "/// <exception cref=\"T:System.OverflowException\">溢出时。</exception>\n/// <remarks>\n"
            + "/// <para>第一段。</para><para>第二段 &amp; <see cref=\"M:A.B(System.Int32)\">B 方法</see>。</para>\n/// </remarks>";

    private static void csharp() {
        equal("/// 把 ⟦R|a⟧ 加到 ⟦C|b⟧，参见 ⟦R|System.Math⟧。\n/// ⟦T|参数⟧ ⟦P|a⟧⟦T|:⟧ 第一个数\n"
                        + "/// ⟦T|返回:⟧ 和，或 ⟦C|null⟧。\n/// ⟦T|异常⟧ ⟦R|System.OverflowException⟧⟦T|:⟧ 溢出时。\n"
                        + "/// ⟦T|备注:⟧ \n/// 第一段。\n第二段 & ⟦R|B 方法⟧。",
                annotate(DocMarkup.Family.CSHARP, CSHARP));
        String english = DocMarkup.parse(DocMarkup.Family.CSHARP, "/// <param name=\"a\">The first.</param>\n/// <returns>Sum.</returns>", "en").display();
        equal("/// Parameter a: The first.\n/// Returns: Sum.", english);
        // Without a language, labels follow the translation's script.
        equal("/// 戻り値: 合計を返す。", DocMarkup.parse(DocMarkup.Family.CSHARP, "/// <returns>合計を返す。</returns>", null).display());
        // <code> blocks keep their content verbatim, including XML-looking text.
        equal("/// ⟦T|示例:⟧ \n/// ⟦C|var x = a<b>(c);⟧",
                annotate(DocMarkup.Family.CSHARP, "/// <example>\n/// <code>\n/// var x = a&lt;b&gt;(c);\n/// </code>\n/// </example>"));
    }

    private static void crlf() {
        for (var family : DocMarkup.Family.values()) {
            String raw = family == DocMarkup.Family.CSHARP ? CSHARP
                    : "/**\n * 说明 <p> {@code x} [y] `z`\n * <pre>\n * a[0]\n * </pre>\n * @param v 值\n */";
            String crlf = raw.replace("\n", "\r\n");
            equal(annotate(family, raw), annotate(family, crlf).replace("\r", ""));
            var result = DocMarkup.parse(family, crlf, "zh-CN");
            equal(result.display().length(), result.source().length);
            for (int i = 0; i < result.display().length(); i++) {
                int source = result.source()[i];
                if (source >= 0 && crlf.charAt(source) != result.display().charAt(i)) throw new AssertionError("source map " + i);
            }
        }
    }

    private static void untouched() {
        String plain = "普通注释 [x] <p> {@code y}";
        var result = DocMarkup.parse(DocMarkup.Family.NONE, plain, "zh-CN");
        equal(plain, result.display());
        equal(0, result.spans().size());
        equal("", DocMarkup.parse(DocMarkup.Family.JAVADOC, "", null).display());
    }

    /** The display text with spans as ⟦K|text⟧: R reference, C code, T tag, P parameter, S explicit prose. */
    private static String annotate(DocMarkup.Family family, String raw) {
        var result = DocMarkup.parse(family, raw, "zh-CN");
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (var span : result.spans()) {
            out.append(result.display(), at, span.start()).append('⟦').append(switch (span.kind()) {
                case REFERENCE -> 'R';
                case CODE -> 'C';
                case TAG -> 'T';
                case PARAM_NAME -> 'P';
                case PROSE -> 'S';
            }).append('|').append(result.display(), span.start(), span.end()).append('⟧');
            at = span.end();
        }
        return out.append(result.display().substring(at)).toString();
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected\n" + expected + "\ngot\n" + actual);
        }
    }
}
