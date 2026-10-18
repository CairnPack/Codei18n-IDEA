package dev.codei18n.idea;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
            equal(root, CodeI18nFiles.findRoot(source, root));
            equal(null, CodeI18nFiles.findRoot(root.getParent().resolve("outside.java"), root));
            Path nested = Files.createDirectories(root.resolve("module/.codei18n")).getParent();
            equal(nested, CodeI18nFiles.findRoot(nested.resolve("Sub.java"), root));
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

            // Cache hits return the same parsed index; invalidating one root leaves other roots cached.
            Files.writeString(mappings, MAPPINGS);
            Path nestedMappings = nested.resolve(".codei18n/mappings.json");
            Files.writeString(nestedMappings, MAPPINGS);
            MappingIndex rootIndex = repository.load(root, "zh-CN");
            repository.load(nested, "zh-CN");
            equal(true, rootIndex == repository.load(root, "zh-CN"));
            replaceKeepingMetadata(mappings, MAPPINGS.replace("计算账户余额", "核算账户余额"));
            replaceKeepingMetadata(nestedMappings, MAPPINGS.replace("计算账户余额", "嵌套账户余额"));
            repository.invalidate(nested);
            equal(true, rootIndex == repository.load(root, "zh-CN"));
            equal("嵌套账户余额", repository.load(nested, "zh-CN").translation("// Calculate account balance"));
            repository.invalidate(root);
            equal("核算账户余额", repository.load(root, "zh-CN").translation("// Calculate account balance"));
            // Evicted roots reload, so same-metadata changes made while no source was open are not missed.
            repository.load(nested, "zh-CN");
            repository.retain(Set.of(root));
            replaceKeepingMetadata(nestedMappings, MAPPINGS);
            equal("计算账户余额", repository.load(nested, "zh-CN").translation("// Calculate account balance"));

            // Upstream rewrites mappings.json in place: a read during the write must retry, not fail.
            MappingRepository retrying = new MappingRepository(Duration.ofMillis(300), 4);
            Files.writeString(mappings, "{\"comments\":{\"a\":");
            Thread writer = new Thread(() -> {
                try { Thread.sleep(100); Files.writeString(mappings, MAPPINGS); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            writer.start();
            equal("计算账户余额", retrying.load(root, "zh-CN").translation("// Calculate account balance"));
            writer.join();
            byte[] complete = MAPPINGS.getBytes(StandardCharsets.UTF_8);
            int cut = MAPPINGS.substring(0, MAPPINGS.indexOf("计算")).getBytes(StandardCharsets.UTF_8).length + 1;
            Files.write(mappings, Arrays.copyOf(complete, cut)); // Ends inside a multi-byte character.
            writer = new Thread(() -> {
                try { Thread.sleep(100); Files.write(mappings, complete); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            writer.start();
            equal("计算账户余额", retrying.load(root, "zh-CN").translation("// Calculate account balance"));
            writer.join();
            // Content that stays broken after the file settles is still reported.
            Files.write(mappings, Arrays.copyOf(complete, cut));
            long started = System.nanoTime();
            try {
                retrying.load(root, "zh-CN");
                throw new AssertionError("Expected settled truncated UTF-8 to fail");
            } catch (CharacterCodingException expected) {
                assertions++;
            }
            equal(true, System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
            Files.writeString(mappings, "{broken");
            rejects(() -> retrying.load(root, "zh-CN"));

            // Opening or closing sources is not a mapping change; mapping and root changes are.
            Files.writeString(mappings, MAPPINGS);
            Path other = root.resolve("src/Other.java");
            Path nestedSource = nested.resolve("Sub.java");
            MappingSnapshot one = MappingSnapshot.capture(List.of(source), root);
            MappingSnapshot two = MappingSnapshot.capture(List.of(source, other, nestedSource), root);
            equal(Set.of(), two.changedRoots(one));
            equal(Set.of(), one.changedRoots(two));
            equal(Set.of(), MappingSnapshot.capture(List.of(source), root).changedRoots(null));
            Files.writeString(nestedMappings, MAPPINGS.replace("计算账户余额", "嵌套变化"));
            Files.setLastModifiedTime(nestedMappings, FileTime.fromMillis(System.currentTimeMillis() + 5000));
            MappingSnapshot three = MappingSnapshot.capture(List.of(source, other, nestedSource), root);
            equal(Set.of(nested), three.changedRoots(two));
            Files.writeString(data.resolve("config.json"), "{\"localLanguage\":\"zh-CN\"}");
            Files.setLastModifiedTime(data.resolve("config.json"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
            equal(Set.of(root), MappingSnapshot.capture(List.of(source), root).changedRoots(three));
            Path moved = root.resolve("moved-codei18n");
            Files.move(nested.resolve(".codei18n"), moved);
            MappingSnapshot four = MappingSnapshot.capture(List.of(source, other, nestedSource), root);
            equal(Set.of(root, nested), four.changedRoots(three)); // Sub.java now resolves to the outer root.
            Files.move(moved, nested.resolve(".codei18n"));

            // VFS events map to the owning root only for paths inside a .codei18n directory.
            equal(Path.of("/p/m"), CodeI18nFiles.rootOf("/p/m/.codei18n/mappings.json"));
            equal(Path.of("/p/m"), CodeI18nFiles.rootOf("/p/m/.codei18n"));
            equal(null, CodeI18nFiles.rootOf("/p/m/.codei18nx/mappings.json"));
            equal(null, CodeI18nFiles.rootOf("/p/m/src/Demo.java"));

            equal("a b", MappingIndex.normalize("// a　　b "));
            equal("x y", MappingIndex.normalize("/*  x\n\t y */"));
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
    private static void replaceKeepingMetadata(Path file, String content) throws Exception {
        FileTime modified = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        Files.writeString(file, content);
        if (Files.size(file) != size) throw new AssertionError("Replacement must keep the file size");
        Files.setLastModifiedTime(file, modified);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException | com.google.gson.JsonParseException expected) { return; }
        throw new AssertionError("Expected invalid JSON/schema rejection");
    }
}
