package dev.codei18n.idea;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/** Dependency-free regression suite; run through coreTest or build.ps1. */
public final class MappingIndexTest {
    private static int assertions;
    private static final String MAPPINGS = """
        {"version":"1.0","sourceLanguage":"en","targetLanguage":"zh-CN","comments":{
          "id-a":{"en":"// Calculate account balance","zh-CN":"计算账户余额","ja":"口座残高を計算"},
          "id-b":{"en":"/* Multi\\n line */","zh-CN":"多行\\n译文"},
          "id-c":{"en":"/** Documentation */","zh-CN":"文档"}
        }}
        """;

    public static void main(String[] args) throws Exception {
        MappingIndex index = MappingIndex.parse(MAPPINGS, "{}", "");
        equal("zh-CN", index.language());
        equal("计算账户余额", index.translation("// Calculate account balance"));
        equal("计算账户余额", index.translation("//  Calculate  account\t balance\r\n"));
        equal("多行\n译文", index.translation("/* Multi\r\n line */"));
        equal("文档", index.translation("/** Documentation */"));
        equal(null, index.translation("// Calculate NEW account balance"));
        equal(null, index.translation("// missing"));
        equal("口座残高を計算", MappingIndex.parse(MAPPINGS, "{\"localLanguage\":\"ja\"}", "")
                .translation("// Calculate account balance"));
        equal("计算账户余额", MappingIndex.parse(MAPPINGS, "{\"localLanguage\":\"ja\"}", "zh-CN")
                .translation("// Calculate account balance"));
        equal(null, MappingIndex.parse(MAPPINGS, "{}", "fr").translation("// Calculate account balance"));
        equal("计算账户余额", MappingIndex.parse("\uFEFF" + MAPPINGS, "\uFEFF{}", "")
                .translation("// Calculate account balance"));
        equal(null, MappingIndex.parse("{\"comments\":null}", "{}", "").translation("x"));
        equal(null, MappingIndex.parse("{\"comments\":{\"x\":{\"en\":\"same\",\"zh-CN\":\"same\"}}}", "{}", "").translation("same"));
        String duplicates = """
            {"comments":{"a":{"en":"// Save","zh-CN":"保存"},"b":{"en":"Save","zh-CN":"节省"}}}
            """;
        equal(null, MappingIndex.parse(duplicates, "{}", "").translation("// Save"));
        equal("保存", MappingIndex.parse(duplicates.replace("节省", "保存"), "{}", "").translation("// Save"));
        equal(null, MappingIndex.parse(duplicates.replace("节省", ""), "{}", "").translation("// Save"));
        equal("保存", MappingIndex.parse("{\"comments\":{\"a\":{\"ja\":\"保存する\",\"zh-CN\":\"保存\"}}}",
                "{\"sourceLanguage\":\"ja\"}", "").translation("// 保存する"));
        rejects(() -> MappingIndex.parse("{", "{}", ""));
        rejects(() -> MappingIndex.parse("[]", "{}", ""));
        rejects(() -> MappingIndex.parse("{\"comments\":[]}", "{}", ""));
        rejects(() -> MappingIndex.parse("{\"comments\":{\"a\":{\"en\":42}}}", "{}", ""));
        rejects(() -> MappingIndex.parse(MAPPINGS, "{\"localLanguage\":false}", ""));

        Path root = Files.createTempDirectory("codei18n-test-");
        try {
            Path data = Files.createDirectories(root.resolve(".codei18n"));
            Path source = Files.createDirectories(root.resolve("src")).resolve("Demo.java");
            Files.writeString(source, "// Calculate account balance\nclass Demo {}\n");
            String original = Files.readString(source);
            equal(root, MappingRepository.findRoot(source, root));
            equal(null, MappingRepository.findRoot(root.getParent().resolve("outside.java"), root));
            Path nested = Files.createDirectories(root.resolve("module/.codei18n")).getParent();
            equal(nested, MappingRepository.findRoot(nested.resolve("Sub.java"), root));
            Path mappings = data.resolve("mappings.json");
            MappingRepository repository = new MappingRepository();
            equal(null, repository.load(root, ""));
            Files.writeString(mappings, MAPPINGS);
            equal("计算账户余额", repository.load(root, "").translation("// Calculate account balance"));
            FileTime unchangedTime = Files.getLastModifiedTime(mappings);
            Files.writeString(mappings, MAPPINGS.replace("计算账户余额", "核算账户余额"));
            Files.setLastModifiedTime(mappings, unchangedTime);
            repository.invalidate();
            equal("核算账户余额", repository.load(root, "").translation("// Calculate account balance"));
            Files.writeString(mappings, MAPPINGS);
            repository.invalidate();
            equal("口座残高を計算", repository.load(root, "ja").translation("// Calculate account balance"));
            Files.writeString(data.resolve("config.json"), "{\"localLanguage\":\"ja\"}");
            equal("ja", repository.load(root, "").language());
            Files.writeString(mappings, MAPPINGS.replace("口座残高を計算", "更新済み"));
            Files.setLastModifiedTime(mappings, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            equal("更新済み", repository.load(root, "").translation("// Calculate account balance"));
            Files.writeString(mappings, "{");
            rejects(() -> repository.load(root, ""));
            Files.writeString(mappings, MAPPINGS);
            repository.invalidate();
            equal("ja", repository.load(root, "").language());
            Files.delete(mappings);
            equal(null, repository.load(root, ""));
            equal(original, Files.readString(source));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS: " + assertions + " mapping/repository assertions");
    }
    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException | com.google.gson.JsonParseException expected) { return; }
        throw new AssertionError("Expected invalid JSON/schema rejection");
    }
}
