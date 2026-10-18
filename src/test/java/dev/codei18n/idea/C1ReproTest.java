package dev.codei18n.idea;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * C1 "translation exists but is not shown" root causes. Written before the fix (all four failed against the
 * pre-index matching API, see the PR description); now each case runs through the entry point that fixes it.
 */
public final class C1ReproTest {
    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();

        // C1.1 同文异译：两个 ID 原文相同、译文不同。修复前被标成 ambiguous 整体丢弃；
        // 现在有 index.json 时按 文件 + 位置 分别命中。
        String duplicates = """
            {"comments":{
              "id-a":{"en":"// Save","zh-CN":"保存"},
              "id-b":{"en":"// Save","zh-CN":"储存"}}}
            """;
        String source = "// Save\nint a;\n// Save\nint b;\n";
        String hash = CommentText.sha1(source.getBytes(StandardCharsets.UTF_8));
        String th = CommentText.textHash("// Save");
        String index = """
            {"version":"1","files":{"A.java":{"contentHash":"%s","language":"java","comments":[
             {"id":"id-a","range":{"startLine":1,"startCol":1,"endLine":1,"endCol":8},"type":"line","lang":"en","sourceKey":"en","textHash":"%s","parts":1},
             {"id":"id-b","range":{"startLine":3,"startCol":1,"endLine":3,"endCol":8},"type":"line","lang":"en","sourceKey":"en","textHash":"%s","parts":1}]}}}
            """.formatted(hash, th, th);
        MappingIndex dup = MappingIndex.parse(duplicates, "{}", index, "");
        var file = new MappingIndex.FileContext("A.java", hash, source);
        expect(failures, "C1.1 same text, different translations is resolvable by file+range",
                "保存".equals(dup.match(file, 0, 7, 1).text()) && "储存".equals(dup.match(file, 15, 22, 1).text()));

        // C1.2 本地语言写的注释：中文注释存在 zh-CN 键下、译文存在 en 键下；显示语言为 en 时应显示英文译文。
        String local = """
            {"comments":{"id-zh":{"zh-CN":"// 计算余额","en":"// Calculate balance"}}}
            """;
        expect(failures, "C1.2 a comment written in the local language shows the other language",
                "// Calculate balance".equals(MappingIndex.parse(local, "{}", "en").translation("// 计算余额")));

        // C1.4 连续行注释：core 合并为一条记录（原文以 \n 连接），PSI 中是两个 PsiComment。
        // 修复前逐条匹配不上；现在插件按 §6.1 聚合成组再匹配，整组显示译文。
        String merged = """
            {"comments":{"id-m":{"en":"// First line\\n// Second line","zh-CN":"// 第一行\\n// 第二行"}}}
            """;
        String code = "    // First line\n    // Second line\n    void f() {}\n";
        var pieces = List.of(new CommentGroups.Piece(4, 17), new CommentGroups.Piece(22, 36));
        var groups = CommentGroups.group(code, pieces);
        MappingIndex m = MappingIndex.parse(merged, "{}", "");
        expect(failures, "C1.4 a merged line-comment group matches as one record",
                groups.size() == 1 && "// 第一行\n// 第二行".equals(
                        m.match(new MappingIndex.FileContext("B.java", null, code), 4, 36, 2).text()));

        // C1.5 语言码：设置里填 zh，mapping 中是 zh-CN。
        String codes = """
            {"comments":{"id-c":{"en":"// Calculate","zh-CN":"// 计算"}}}
            """;
        expect(failures, "C1.5 display language zh finds zh-CN translations",
                MappingIndex.parse(codes, "{}", "zh").translation("// Calculate") != null);

        if (failures.isEmpty()) {
            System.out.println("PASS: C1 reproduction cases");
        } else {
            failures.forEach(f -> System.out.println("FAIL: " + f));
            throw new AssertionError(failures.size() + " C1 cases fail");
        }
    }

    private static void expect(List<String> failures, String name, boolean ok) {
        if (!ok) failures.add(name);
    }
}
