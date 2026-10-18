package dev.codei18n.idea;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** index.json matching (C1/C2), comment grouping and the vectors shared with core. Run from MappingIndexTest. */
final class IndexMatchingTest {
    private static int assertions;

    static int run() throws Exception {
        sharedVectors();
        grouping();
        indexMatching();
        localLanguageAndCodes();
        noPsiFallback();
        System.out.println("PASS: " + assertions + " index matching assertions");
        return assertions;
    }

    private static void sharedVectors() throws Exception {
        var codes = JsonParser.parseString(resource("testdata/language-codes.json")).getAsJsonObject();
        for (JsonElement e : codes.getAsJsonArray("canonical")) {
            JsonArray pair = e.getAsJsonArray();
            equal(pair.get(1).getAsString(), LanguageCodes.canonical(pair.get(0).getAsString()));
        }
        for (JsonElement e : codes.getAsJsonArray("equivalent")) {
            JsonArray c = e.getAsJsonArray();
            equal(c.get(2).getAsBoolean(), LanguageCodes.equivalent(c.get(0).getAsString(), c.get(1).getAsString()));
        }
        var norm = JsonParser.parseString(resource("testdata/text-normalization.json")).getAsJsonObject();
        int cases = 0;
        for (JsonElement e : norm.getAsJsonArray("cases")) {
            var c = e.getAsJsonObject();
            equal(c.get("normalized").getAsString(), CommentText.normalize(c.get("input").getAsString()));
            cases++;
        }
        equal(true, cases >= 10);
        // Hash of the normalized text, lowercase hex SHA-1 (same as core/index.TextHash).
        equal(CommentText.sha1("Hello world".getBytes(StandardCharsets.UTF_8)), CommentText.textHash("// Hello   world"));
    }

    private static void grouping() {
        String text = "// a\n// b\n    // c\nx(); // d\n// e\n/// f\n/// g\n/* h */\n/* i */\n";
        List<CommentGroups.Piece> pieces = List.of(p(text, "// a"), p(text, "// b"), p(text, "// c"), p(text, "// d"),
                p(text, "// e"), p(text, "/// f"), p(text, "/// g"), p(text, "/* h */"), p(text, "/* i */"));
        var groups = CommentGroups.group(text, pieces);
        // a+b; c (different column); d (trailing); e (different marker from f); f+g; h; i
        equal(List.of(2, 1, 1, 1, 2, 1, 1), groups.stream().map(List::size).toList());
        equal("///", CommentGroups.marker("/// x"));
        equal("//!", CommentGroups.marker("//! x"));
        equal("", CommentGroups.marker("/* x */"));
    }

    private static final String MAPPINGS = """
        {"version":"1.0","sourceLanguage":"en","targetLanguage":"zh-CN","comments":{
          "id-save-1":{"en":"// Save","zh-CN":"// 保存"},
          "id-save-2":{"en":"// Save","zh-CN":"// 储存"},
          "id-group":{"en":"// First line\\n// Second line","zh-CN":"// 第一行\\n// 第二行"},
          "id-zh":{"zh-CN":"// 计算余额","en":"// Calculate balance"},
          "id-ja":{"ja":"// 設定を読み込む","zh-CN":"// 读取设置","en":"// Load settings"}
        }}
        """;

    /** Java source whose comments are indexed below. Lines: 1 class, 2 save-1, 4 save-2, 6-7 group, 9 zh, 11 ja. */
    private static final String SOURCE = """
        class A {
            // Save
            void a() {}
            // Save
            void b() {}
            // First line
            // Second line
            void c() {}
            // 计算余额
            void d() {}
            // 設定を読み込む
            void e() {}
        }
        """;

    private static String index(String contentHash) {
        return """
            {"version":"1","files":{"src/A.java":{"contentHash":"%s","language":"java","comments":[
              {"id":"id-save-1","range":{"startLine":2,"startCol":5,"endLine":2,"endCol":12},"type":"line","lang":"en","sourceKey":"en","textHash":"%s","parts":1},
              {"id":"id-save-2","range":{"startLine":4,"startCol":5,"endLine":4,"endCol":12},"type":"line","lang":"en","sourceKey":"en","textHash":"%s","parts":1},
              {"id":"id-group","range":{"startLine":6,"startCol":5,"endLine":7,"endCol":19},"type":"line","lang":"en","sourceKey":"en","textHash":"%s","parts":2},
              {"id":"id-zh","range":{"startLine":9,"startCol":5,"endLine":9,"endCol":12},"type":"line","lang":"zh-Hans","sourceKey":"zh-CN","textHash":"%s","parts":1},
              {"id":"id-ja","range":{"startLine":11,"startCol":5,"endLine":11,"endCol":15},"type":"line","lang":"ja","sourceKey":"ja","textHash":"%s","parts":1}
            ]}}}
            """.formatted(contentHash, CommentText.textHash("// Save"), CommentText.textHash("// Save"),
                CommentText.textHash("// First line\n// Second line"), CommentText.textHash("// 计算余额"),
                CommentText.textHash("// 設定を読み込む"));
    }

    private static String hash(String s) { return CommentText.sha1(s.getBytes(StandardCharsets.UTF_8)); }

    private static void indexMatching() throws Exception {
        MappingIndex index = MappingIndex.parse(MAPPINGS, "{}", index(hash(SOURCE)), "");
        equal(true, index.hasLocations());
        var file = new MappingIndex.FileContext("src/A.java", hash(SOURCE), SOURCE);

        // C1.1 同文异译：按文件 + 位置各自命中，不再因 ambiguous 被整体丢弃
        equal("// 保存", match(index, file, "// Save", 0, 1));
        equal("// 储存", match(index, file, "// Save", 1, 1));
        // 全局文本匹配对同文异译仍然保守：不显示
        equal(null, index.translation("// Save"));

        // C1.4 连续行注释：按 parts 作为一组匹配
        int g = SOURCE.indexOf("// First line"), ge = SOURCE.indexOf("// Second line") + "// Second line".length();
        equal("// 第一行\n// 第二行", index.match(file, g, ge, 2).text());
        // 单独的第一行不是索引中的条目
        equal(false, index.match(file, g, g + "// First line".length(), 1).found());

        // 文件已修改（hash 不同）：在该文件条目内按 textHash 匹配；同文异译按出现顺序配对（行号整体平移也不会错位）
        String edited = "// inserted\n" + SOURCE;
        var changed = new MappingIndex.FileContext("src/A.java", hash(edited), edited);
        int s0 = edited.indexOf("// Save"), s1 = edited.indexOf("// Save", s0 + 1);
        equal("// 保存", index.match(changed, s0, s0 + 7, 1, 0, 2).text());
        equal("// 储存", index.match(changed, s1, s1 + 7, 1, 1, 2).text());
        // 出现次数与索引不一致时退回最近行；与两个条目等距时宁可不显示
        equal(null, index.match(changed, s0, s0 + 7, 1, 0, 3).text());
        equal(false, index.match(changed, s0, s0 + 7, 1, 0, 3).found() && index.match(changed, s0, s0 + 7, 1, 0, 3).text() != null);
        int eg = edited.indexOf("// First line"), ege = edited.indexOf("// Second line") + "// Second line".length();
        equal("// 第一行\n// 第二行", index.match(changed, eg, ege, 2).text());

        // 不在索引中的文件：退回全局文本匹配（同旧行为）
        var other = new MappingIndex.FileContext("src/B.java", null, "// First line\n// Second line\n");
        equal("// 第一行\n// 第二行", index.match(other, 0, other.text().length() - 1, 2).text());

        // 不认识的 index.json 主版本：忽略索引
        equal(false, MappingIndex.parse(MAPPINGS, "{}", "{\"version\":\"2\",\"files\":{}}", "").hasLocations());
        rejects(() -> MappingIndex.parse(MAPPINGS, "{}", "{broken", ""));
        // 没有 index.json：与旧版本完全一样
        equal(false, MappingIndex.parse(MAPPINGS, "{}", "").hasLocations());
    }

    private static void localLanguageAndCodes() {
        String idx = index(hash(SOURCE));
        var file = new MappingIndex.FileContext("src/A.java", hash(SOURCE), SOURCE);
        // C1.2 本地语言写的注释：显示语言为 zh-CN 时，原文已是显示语言，不显示（找到条目但无译文）
        MappingIndex zh = MappingIndex.parse(MAPPINGS, "{}", idx, "");
        MappingIndex.Match self = matchRaw(zh, file, "// 计算余额", 0, 1);
        equal(true, self.found());
        equal(null, self.text());
        // 显示语言为 en 时显示英文
        equal("// Calculate balance", match(MappingIndex.parse(MAPPINGS, "{}", idx, "en"), file, "// 计算余额", 0, 1));
        // 第三种语言（ja）的注释：显示本地语言译文
        equal("// 读取设置", match(zh, file, "// 設定を読み込む", 0, 1));
        // C1.5 语言码等价：设置里填 zh，mapping 中是 zh-CN
        MappingIndex zhShort = MappingIndex.parse(MAPPINGS, "{}", idx, "zh");
        equal("zh", zhShort.language());
        equal("// 保存", match(zhShort, file, "// Save", 0, 1));
        equal(null, matchRaw(zhShort, file, "// 计算余额", 0, 1).text());
        // 全局回退同样识别本地语言原文与等价语言码（无 index.json）
        MappingIndex legacy = MappingIndex.parse(MAPPINGS, "{}", "en");
        equal("// Calculate balance", legacy.translation("// 计算余额"));
        equal("// 读取设置", MappingIndex.parse(MAPPINGS, "{}", "zh").translation("// 設定を読み込む"));
    }

    private static void noPsiFallback() {
        MappingIndex index = MappingIndex.parse(MAPPINGS, "{}", index(hash(SOURCE)), "");
        var located = index.fallback(new MappingIndex.FileContext("src/A.java", null, SOURCE));
        // save-1, save-2, group, ja（zh 原文已是显示语言）
        equal(4, located.size());
        var first = located.get(0);
        equal("// Save", SOURCE.substring(first.start(), first.end()));
        equal("// 保存", first.text());
        var group = located.get(2);
        equal("// First line\n    // Second line", SOURCE.substring(group.start(), group.end()));
        // 文本已改变的范围不显示（宁缺毋滥）
        String edited = SOURCE.replace("// Save\n    void a", "// Sav!\n    void a");
        equal(3, index.fallback(new MappingIndex.FileContext("src/A.java", null, edited)).size());
        // 行列越界不报错
        equal(0, index.fallback(new MappingIndex.FileContext("src/A.java", null, "class A {}\n")).size());
    }

    private static String match(MappingIndex index, MappingIndex.FileContext file, String comment, int nth, int parts) {
        return matchRaw(index, file, comment, nth, parts).text();
    }

    private static MappingIndex.Match matchRaw(MappingIndex index, MappingIndex.FileContext file, String comment, int nth, int parts) {
        int start = -1;
        for (int i = 0; i <= nth; i++) start = file.text().indexOf(comment, start + 1);
        if (start < 0) throw new AssertionError("comment not found: " + comment);
        return index.match(file, start, start + comment.length(), parts);
    }

    private static CommentGroups.Piece p(String text, String comment) {
        int start = text.indexOf(comment);
        return new CommentGroups.Piece(start, start + comment.length());
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = IndexMatchingTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new AssertionError("missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private interface Checked { void run() throws Exception; }

    private static void rejects(Checked action) throws Exception {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException | com.google.gson.JsonParseException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
}
