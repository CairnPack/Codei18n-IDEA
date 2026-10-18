package dev.codei18n.idea;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.CustomFoldRegion;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * C5: the multi-language sample project (examples/languages, copied to an isolated directory below the smoke project)
 * in the real IDE. For every language: block comment, merged line-comment group (one inlay after its last line),
 * doc comment and a "//" inside a string literal (no inlay), plus which matching path the IDE takes (comment PSI or the
 * index.json fallback without PSI). Then C1.6 automatic refresh: opening latency, a translation rewritten externally,
 * and a branch switch (source overwritten on disk, then a new index/mappings written by the CLI) in both orders.
 * Screenshots of the real editor components go to the screenshot directory. Call from a non-EDT thread with
 * translations enabled in below mode and the display language following config.json (zh-CN). Leaves files as found.
 */
public final class LanguageSamplesTest {
    private static final String MOCK = "[MOCK en->zh-CN] ";
    private static final long REFRESH_BOUND_MS = 5000;

    /** {@code docLines}: the doc comment is a "///" group (Dart, C#) instead of a block. */
    private record Sample(String file, String label, boolean psiOn251, boolean psiOn262, boolean docLines) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample("Inventory.kt", "kotlin", true, true, false),
            new Sample("OrderService.java", "java", true, true, false),
            new Sample("cart.ts", "typescript", false, true, false),
            new Sample("format.js", "javascript", false, true, false),
            new Sample("invoice.dart", "dart", false, false, true),
            new Sample("Ledger.cs", "csharp", false, false, true));

    /** A comment located from the source text alone (independent of index.json): [start, end) and core's original text. */
    private record Comment(String kind, int start, int end, String original) {}

    public static void run(Project project, Path samples, Path branch, Path screenshots) throws Exception {
        var build = ApplicationInfo.getInstance().getBuild();
        int baseline = build.getBaselineVersion();
        String ide = build.getProductCode() + "-" + baseline;
        Path shots = screenshots.resolve(ide);
        Files.createDirectories(shots);
        Path data = samples.resolve(".codei18n");
        Path mappings = data.resolve("mappings.json"), index = data.resolve("index.json");
        String originalMappings = Files.readString(mappings), originalIndex = Files.readString(index);
        Map<String, byte[]> originalSources = new LinkedHashMap<>();
        for (Sample sample : SAMPLES) originalSources.put(sample.file, Files.readAllBytes(samples.resolve(sample.file)));
        Map<Sample, Editor> editors = new LinkedHashMap<>();
        List<String> latencies = new ArrayList<>();
        // test-ide.ps1 recopied the samples before startup; the persistent VFS may still hold the previous run's
        // timestamps. A real IDE refreshes on startup and through its file watcher; do the same once.
        ui(() -> com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true,
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(samples)));
        try {
            // 1. Every language, opened one by one: translations appear on their own (C1.6a) at the right places.
            for (Sample sample : SAMPLES) {
                Path source = samples.resolve(sample.file);
                long opened = System.nanoTime();
                Editor[] editor = new Editor[1];
                ui(() -> {
                    VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
                    Document document = FileDocumentManager.getInstance().getDocument(file);
                    editor[0] = EditorFactory.getInstance().createEditor(document, project, file, false);
                });
                editors.put(sample, editor[0]);
                waitFor(sample.label + ": three translations shown", () -> blocks(editor[0]).size() == 3, 60);
                long ms = millis(opened);
                latencies.add(sample.label + " open=" + ms + "ms");
                System.out.println("LATENCY open " + sample.label + " " + ms + "ms");
                require(ms <= REFRESH_BOUND_MS, sample.label + ": newly opened editor translated within 5 s (" + ms + "ms)");
                verifySample(project, sample, editor[0], originalMappings, baseline);
            }

            // 2. Screenshots of the real editors, below mode for every language and replace mode for Java.
            for (var entry : editors.entrySet()) screenshot(entry.getValue(), shots.resolve(entry.getKey().label + ".png"));
            Editor java = editors.get(SAMPLES.get(1));
            var service = TranslationService.getInstance(project);
            ui(() -> {
                java.getCaretModel().moveToOffset(0);
                service.setDisplayMode(DisplayMode.REPLACE);
            });
            waitFor("replace mode folds the three Java comments", () -> folds(java).size() == 3 && blocks(java).isEmpty(), 20);
            screenshot(java, shots.resolve("java-replace.png"));
            ui(() -> service.setDisplayMode(DisplayMode.BELOW));
            waitFor("below mode restored for every sample", () ->
                    editors.values().stream().allMatch(e -> blocks(e).size() == 3 && folds(e).isEmpty()), 20);

            // 3. C1.6b: translations rewritten externally (CLI style: temp file + rename, no VFS refresh).
            Editor kotlin = editors.get(SAMPLES.get(0)), csharp = editors.get(SAMPLES.get(5));
            Comment kotlinBlock = locate(kotlin.getDocument().getText(), false).get(0);
            Comment csharpBlock = locate(csharp.getDocument().getText(), true).get(0);
            JsonObject edited = JsonParser.parseString(originalMappings).getAsJsonObject();
            JsonObject comments = edited.getAsJsonObject("comments");
            String indexOnly = "只能经 index.json 匹配的新译文", fallbackText = "无 PSI 回退也会刷新的新译文";
            for (var entry : comments.entrySet()) {
                JsonObject texts = entry.getValue().getAsJsonObject();
                if (texts.get("en").getAsString().equals(kotlinBlock.original)) {
                    // The stored original no longer equals the source: only index.json (position → ID) can match.
                    texts.addProperty("en", "/* An outdated original text */");
                    texts.addProperty("zh-CN", indexOnly);
                } else if (texts.get("en").getAsString().equals(csharpBlock.original)) {
                    texts.addProperty("zh-CN", fallbackText);
                }
            }
            long written = System.nanoTime();
            atomicWrite(mappings, edited.toString());
            waitFor("Kotlin block shows the rewritten translation", () -> textAt(kotlin, kotlinBlock.end).equals(List.of(indexOnly)), 20);
            long kotlinMs = millis(written);
            waitFor("C# block (no PSI) shows the rewritten translation", () -> textAt(csharp, csharpBlock.end).equals(List.of(fallbackText)), 20);
            long csharpMs = millis(written);
            latencies.add("mappings rewrite kotlin=" + kotlinMs + "ms csharp=" + csharpMs + "ms");
            System.out.println("LATENCY mappings-rewrite kotlin=" + kotlinMs + "ms csharp=" + csharpMs + "ms");
            require(kotlinMs <= REFRESH_BOUND_MS && csharpMs <= REFRESH_BOUND_MS, "external translation rewrite shown within 5 s");
            require(true, "Kotlin matches through index.json: the global text match cannot find a changed original (C1.3)");
            atomicWrite(mappings, originalMappings);
            waitFor("original translations restored", () -> consistent(kotlin, 3, false) && consistent(csharp, 3, true), 20);

            // 4. Branch switch: the source changes on disk first (IDE file watcher reloads it), then `map update` +
            //    `translate` write the new index.json and mappings.json.
            Path inventory = samples.resolve("Inventory.kt");
            byte[] branchSource = Files.readAllBytes(branch.resolve("Inventory.kt"));
            String branchMappings = Files.readString(branch.resolve(".codei18n/mappings.json"));
            String branchIndex = Files.readString(branch.resolve(".codei18n/index.json"));
            Files.write(inventory, branchSource);
            reloadFromDisk(kotlin, inventory, branchSource);
            waitFor("changed file: old comments matched by textHash, new ones untranslated", () -> consistent(kotlin, 3, false)
                    && locate(kotlin.getDocument().getText(), false).size() == 5, 20);
            written = System.nanoTime();
            atomicWrite(index, branchIndex);
            atomicWrite(mappings, branchMappings);
            waitFor("branch switch: new comments translated", () -> consistent(kotlin, 5, false), 20);
            long branchMs = millis(written);
            latencies.add("branch switch (source first)=" + branchMs + "ms");
            System.out.println("LATENCY branch-switch-source-first " + branchMs + "ms");
            require(branchMs <= REFRESH_BOUND_MS, "branch switch translations shown within 5 s");
            verifyKotlinBranch(kotlin);

            // 5. Switching back in the other order: .codei18n is rewritten before the IDE notices the source change.
            //    The document is stale meanwhile; translations must never be attached to the wrong comment.
            written = System.nanoTime();
            atomicWrite(index, originalIndex);
            atomicWrite(mappings, originalMappings);
            Files.write(inventory, originalSources.get("Inventory.kt"));
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3000);
            while (System.nanoTime() < until) {
                boolean[] ok = {true};
                String[] detail = {""};
                ui(() -> {
                    for (var inlay : inlays(kotlin)) {
                        if (!belongs(kotlin.getDocument().getText(), inlay.getOffset(), inlay.getRenderer().text(), false)) {
                            ok[0] = false;
                            detail[0] = "offset " + inlay.getOffset() + ": " + inlay.getRenderer().text();
                        }
                    }
                });
                if (!ok[0]) throw new AssertionError("stale document shows a translation of another comment, " + detail[0]);
                Thread.sleep(50);
            }
            boolean[] stale = {false};
            ui(() -> stale[0] = kotlin.getDocument().getText().equals(new String(branchSource, StandardCharsets.UTF_8)));
            System.out.println("stale window: document still shows the old text after 3 s = " + stale[0]);
            require(stale[0], "the document stayed stale during the window (the IDE did not reload the file by itself)");
            require(true, "stale document never shows a translation under the wrong comment");
            waitFor("stale document: unchanged comments keep their translations through textHash", () -> consistent(kotlin, 3, false), 5);
            long reloaded = System.nanoTime();
            reloadFromDisk(kotlin, inventory, originalSources.get("Inventory.kt"));
            waitFor("switched back: original three translations", () -> consistent(kotlin, 3, false), 20);
            long backMs = millis(reloaded);
            latencies.add("branch switch back (index first) after the IDE reloads the source=" + backMs + "ms");
            System.out.println("LATENCY branch-switch-index-first " + backMs + "ms after the source reload (" + millis(written)
                    + "ms since .codei18n was written, including the 3 s stale window)");
            require(backMs <= REFRESH_BOUND_MS, "switched-back translations shown within 5 s of the source reload");
            verifySample(project, SAMPLES.get(0), kotlin, originalMappings, baseline);
        } finally {
            atomicWrite(mappings, originalMappings);
            atomicWrite(index, originalIndex);
            for (var entry : originalSources.entrySet()) {
                Path source = samples.resolve(entry.getKey());
                if (!Arrays.equals(Files.readAllBytes(source), entry.getValue())) Files.write(source, entry.getValue());
            }
            ui(() -> {
                TranslationService.getInstance(project).setDisplayMode(DisplayMode.BELOW);
                editors.values().forEach(EditorFactory.getInstance()::releaseEditor);
            });
        }
        for (var entry : originalSources.entrySet()) {
            require(Arrays.equals(Files.readAllBytes(samples.resolve(entry.getKey())), entry.getValue()),
                    "sample source restored byte for byte: " + entry.getKey());
        }
        System.out.println("LATENCIES " + String.join("; ", latencies));
    }

    /**
     * One sample on its own, for IDEs without the Java and Kotlin plugins (test-rider.ps1 runs Ledger.cs in Rider):
     * opened by itself, the same assertions as {@link #run} (block, merged line group, doc comment, string literal,
     * matching path), a screenshot of the real editor to {@code png}, an external rewrite of the block translation,
     * then the document and the file unchanged byte for byte. Call from a non-EDT thread with translations enabled
     * in below mode and the display language following config.json. Returns the latency lines.
     */
    public static List<String> runSingle(Project project, Path samples, String file, Path png) throws Exception {
        Sample sample = SAMPLES.stream().filter(s -> s.file.equals(file)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no sample " + file));
        int baseline = ApplicationInfo.getInstance().getBuild().getBaselineVersion();
        Path mappings = samples.resolve(".codei18n/mappings.json");
        String originalMappings = Files.readString(mappings);
        Path source = samples.resolve(file);
        byte[] before = Files.readAllBytes(source);
        List<String> latencies = new ArrayList<>();
        Files.createDirectories(png.getParent());
        ui(() -> com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true,
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(samples)));
        Editor[] editor = new Editor[1];
        long opened = System.nanoTime();
        ui(() -> {
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
            Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            editor[0] = EditorFactory.getInstance().createEditor(document, project, virtualFile, false);
        });
        try {
            waitFor(sample.label + ": three translations shown", () -> blocks(editor[0]).size() == 3, 60);
            long ms = millis(opened);
            latencies.add(sample.label + " open=" + ms + "ms");
            System.out.println("LATENCY open " + sample.label + " " + ms + "ms");
            require(ms <= REFRESH_BOUND_MS, sample.label + ": newly opened editor translated within 5 s (" + ms + "ms)");
            verifySample(project, sample, editor[0], originalMappings, baseline);
            screenshot(editor[0], png);

            Comment block = locate(editor[0].getDocument().getText(), sample.docLines).get(0);
            JsonObject edited = JsonParser.parseString(originalMappings).getAsJsonObject();
            String rewritten = "外部改写后的新译文（" + sample.label + "）";
            for (var entry : edited.getAsJsonObject("comments").entrySet()) {
                JsonObject texts = entry.getValue().getAsJsonObject();
                if (texts.get("en").getAsString().equals(block.original)) texts.addProperty("zh-CN", rewritten);
            }
            long written = System.nanoTime();
            atomicWrite(mappings, edited.toString());
            waitFor(sample.label + " block shows the rewritten translation", () -> textAt(editor[0], block.end).equals(List.of(rewritten)), 20);
            long rewriteMs = millis(written);
            latencies.add("mappings rewrite " + sample.label + "=" + rewriteMs + "ms");
            System.out.println("LATENCY mappings-rewrite " + sample.label + "=" + rewriteMs + "ms");
            require(rewriteMs <= REFRESH_BOUND_MS, sample.label + ": external translation rewrite shown within 5 s");
            atomicWrite(mappings, originalMappings);
            waitFor(sample.label + ": original translations restored", () -> consistent(editor[0], 3, sample.docLines), 20);
            ui(() -> require(editor[0].getDocument().getText().equals(new String(before, StandardCharsets.UTF_8)),
                    sample.label + ": document text unchanged by the translations"));
        } finally {
            atomicWrite(mappings, originalMappings);
            ui(() -> EditorFactory.getInstance().releaseEditor(editor[0]));
        }
        require(Arrays.equals(before, Files.readAllBytes(source)), sample.label + ": source file bytes unchanged");
        return latencies;
    }

    /** Exact translations for block, line group and doc comment; nothing for the string literal; the matching path. */
    private static void verifySample(Project project, Sample sample, Editor editor, String mappingsJson, int baseline) {
        JsonObject comments = JsonParser.parseString(mappingsJson).getAsJsonObject().getAsJsonObject("comments");
        ui(() -> {
            Document document = editor.getDocument();
            String text = document.getText();
            List<Comment> located = locate(text, sample.docLines);
            require(located.size() == 3, sample.label + ": fixture has block, line group and doc comment");
            var all = inlays(editor);
            require(all.size() == 3, sample.label + ": exactly three translation inlays");
            for (Comment comment : located) {
                String expected = translationOf(comments, comment.original);
                require(expected.startsWith(MOCK) || expected.contains("* " + MOCK) || expected.startsWith("/// " + MOCK),
                        sample.label + " " + comment.kind + ": mock translation exists in mappings.json");
                var inside = all.stream().filter(i -> i.getOffset() >= comment.start && i.getOffset() <= comment.end).toList();
                require(inside.size() == 1 && inside.get(0).getOffset() == comment.end,
                        sample.label + " " + comment.kind + ": one inlay, anchored at the comment end");
                require(expected.equals(inside.get(0).getRenderer().text()),
                        sample.label + " " + comment.kind + ": exact translation " + firstLine(expected));
                if (comment.kind.equals("line group")) {
                    int last = document.getLineNumber(comment.end), first = document.getLineNumber(comment.start);
                    require(last > first && document.getLineNumber(inside.get(0).getOffset()) == last,
                            sample.label + ": merged group (" + (last - first + 1) + " lines) has its single inlay after its last line");
                }
                if (comment.kind.equals("doc") && sample.label.equals("csharp")) {
                    String display = display(inside.get(0).getRenderer());
                    System.out.println("C# doc display: " + display.replace("\n", "\\n"));
                    // The mock puts its prefix before <summary>, so the prefix keeps a line of its own.
                    require(display.indexOf('<') < 0 && display.equals("/// [MOCK en->zh-CN] \n/// Adds a booking to the ledger.\n/// 参数 amount: The amount to book.\n/// 返回: The new balance."),
                            "csharp doc: XML tags simplified for display (C3)");
                }
            }
            // Preconditions of the exact (file + range) match: unchanged content and a VFS snapshot in sync with the disk.
            try {
                VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                Path path = file.toNioPath();
                String contentHash = JsonParser.parseString(Files.readString(path.resolveSibling(".codei18n/index.json")))
                        .getAsJsonObject().getAsJsonObject("files").getAsJsonObject(sample.file).get("contentHash").getAsString();
                require(contentHash.equals(CommentText.sha1(Files.readAllBytes(path)))
                        && file.getTimeStamp() == Files.getLastModifiedTime(path).toMillis() && file.getLength() == Files.size(path),
                        sample.label + ": unchanged file, VFS in sync, so index.json positions are used");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            int literal = text.indexOf("// not a comment");
            int line = document.getLineNumber(literal);
            require(literal > 0 && all.stream().noneMatch(i -> document.getLineNumber(i.getOffset()) == line),
                    sample.label + ": no translation for // inside a string literal");
            var psi = PsiDocumentManager.getInstance(project).getPsiFile(document);
            int[] psiComments = {0};
            psi.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override public void visitElement(PsiElement element) {
                    if (element instanceof PsiComment) psiComments[0]++;
                    else super.visitElement(element);
                }
            });
            // TranslationHint.scan takes the PSI path exactly when the file has comment PSI.
            boolean viaPsi = psiComments[0] > 0;
            boolean expectPsi = baseline >= 262 ? sample.psiOn262 : sample.psiOn251;
            System.out.println("PATH " + sample.label + "=" + (viaPsi ? "PSI" : "fallback") + " (PSI file " + psi.getClass().getSimpleName()
                    + ", language " + psi.getLanguage().getID() + ", " + psiComments[0] + " PsiComment)");
            require(viaPsi == expectPsi, sample.label + " uses the " + (expectPsi ? "PSI" : "no-PSI index.json fallback") + " path on " + baseline);
        });
    }

    /** After the branch switch the old KDoc's translation moved with it and the new KDoc/line comment are translated. */
    private static void verifyKotlinBranch(Editor editor) {
        ui(() -> {
            String text = editor.getDocument().getText();
            List<Comment> located = locate(text, false);
            for (Comment comment : located) {
                List<String> shown = textAt(editor, comment.end);
                require(shown.size() == 1 && belongs(text, comment.end, shown.get(0), false),
                        "branch: " + firstLine(comment.original) + " → " + (shown.isEmpty() ? "none" : firstLine(shown.get(0))));
            }
        });
    }

    /**
     * The comments of a sample, from its text alone: the first block comment, the first group of own-line "//" comments,
     * every doc comment ("/**" blocks, or "///" groups when {@code docLines}) and further own-line "//" comments.
     * {@code original} is the text core stores: the raw range for blocks, the trimmed lines joined by '\n' for groups.
     */
    static List<Comment> locate(String text, boolean docLines) {
        List<Comment> out = new ArrayList<>();
        int block = text.indexOf("/*\n");
        if (block >= 0) out.add(new Comment("block", block, text.indexOf("*/", block) + 2, text.substring(block, text.indexOf("*/", block) + 2)));
        for (int from = 0; (from = text.indexOf("/**", from)) >= 0; ) {
            int end = text.indexOf("*/", from) + 2;
            out.add(new Comment("doc", from, end, text.substring(from, end)));
            from = end;
        }
        String[] lines = text.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; ) {
            String trimmed = lines[i].stripLeading();
            boolean doc = trimmed.startsWith("///");
            if (trimmed.startsWith("//") && (!doc || docLines)) {
                String marker = doc ? "///" : "//";
                int start = offset + lines[i].length() - trimmed.length();
                StringBuilder original = new StringBuilder();
                int end = start;
                while (i < lines.length && lines[i].stripLeading().startsWith(marker)
                        && (marker.equals("///") || !lines[i].stripLeading().startsWith("///"))) {
                    if (!original.isEmpty()) original.append('\n');
                    original.append(lines[i].strip());
                    end = offset + lines[i].length();
                    offset += lines[i].length() + 1;
                    i++;
                }
                out.add(new Comment(doc ? "doc" : "line group", start, end, original.toString()));
                continue;
            }
            offset += lines[i].length() + 1;
            i++;
        }
        out.sort(java.util.Comparator.comparingInt(Comment::start));
        return out;
    }

    /** The display-language text of the mapping whose original equals {@code original}. */
    private static String translationOf(JsonObject comments, String original) {
        for (var entry : comments.entrySet()) {
            JsonObject texts = entry.getValue().getAsJsonObject();
            if (texts.has("en") && texts.get("en").getAsString().equals(original)) return texts.get("zh-CN").getAsString();
        }
        throw new AssertionError("no mapping for " + original);
    }

    /**
     * Whether a mock translation shown at {@code offset} belongs to the comment ending there: mock translations keep
     * the original words, so both normalize to the same text once the mock prefix and markers are removed.
     */
    private static boolean belongs(String text, int offset, String translation, boolean docLines) {
        for (Comment comment : locate(text, docLines)) {
            if (comment.end != offset) continue;
            return strip(CommentText.normalize(translation).replace(MOCK, "")).equals(strip(CommentText.normalize(comment.original)));
        }
        return false;
    }

    private static String strip(String normalized) {
        for (String marker : List.of("/// ", "// ", "/* ")) if (normalized.startsWith(marker)) return normalized.substring(marker.length());
        return normalized;
    }

    /** The expected number of inlays, each a translation of the comment it follows. */
    private static boolean consistent(Editor editor, int count, boolean docLines) {
        var all = inlays(editor);
        String text = editor.getDocument().getText();
        return all.size() == count && all.stream().allMatch(i -> belongs(text, i.getOffset(), i.getRenderer().text(), docLines));
    }

    /** Simulates the IDE file watcher noticing an external source change, then waits for the document to follow. */
    private static void reloadFromDisk(Editor editor, Path source, byte[] content) throws Exception {
        ui(() -> {
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(source);
            file.refresh(false, false);
        });
        String expected = new String(content, StandardCharsets.UTF_8);
        waitFor("document reloaded from disk", () -> editor.getDocument().getText().equals(expected), 20);
    }

    /**
     * Paints the real editor component (gutter, text, inlays, folds) into a PNG. Headless components have no peer, so
     * Swing never validates them: lay the tree out by hand, once to set the wrap width and once for the full height.
     */
    private static void screenshot(Editor editor, Path png) {
        ui(() -> {
            var component = editor.getComponent();
            int width = 1100;
            component.setSize(width, 600);
            layout(component);
            int height = Math.min(3000, editor.getContentComponent().getPreferredSize().height + 24);
            component.setSize(width, height);
            layout(component);
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                graphics.setClip(0, 0, width, height);
                component.paint(graphics);
            } finally {
                graphics.dispose();
            }
            java.util.Set<Integer> colors = new java.util.HashSet<>();
            for (int y = 0; y < height; y += 2) for (int x = 0; x < width; x += 2) colors.add(image.getRGB(x, y));
            require(colors.size() > 20, "editor painted with text and translations (" + colors.size() + " colors): " + png.getFileName());
            try {
                javax.imageio.ImageIO.write(image, "png", png.toFile());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("SCREENSHOT " + png);
        });
    }

    private static void layout(java.awt.Component component) {
        if (!(component instanceof java.awt.Container container)) return;
        container.doLayout();
        for (var child : container.getComponents()) layout(child);
    }

    private static void atomicWrite(Path target, String content) throws Exception {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static List<Inlay<? extends TranslationRenderer>> inlays(Editor editor) {
        return editor.getInlayModel().getBlockElementsInRange(0, editor.getDocument().getTextLength(), TranslationRenderer.class);
    }

    private static List<String> blocks(Editor editor) { return inlays(editor).stream().map(i -> i.getRenderer().text()).toList(); }

    private static List<String> textAt(Editor editor, int offset) {
        return inlays(editor).stream().filter(i -> i.getOffset() == offset).map(i -> i.getRenderer().text()).toList();
    }

    private static List<CustomFoldRegion> folds(Editor editor) {
        return Arrays.stream(editor.getFoldingModel().getAllFoldRegions())
                .filter(r -> r.isValid() && r instanceof CustomFoldRegion c && c.getRenderer() instanceof TranslationFoldRenderer)
                .map(r -> (CustomFoldRegion) r).toList();
    }

    private static String display(TranslationRenderer renderer) {
        try {
            var field = TranslationRenderer.class.getDeclaredField("display");
            field.setAccessible(true);
            return (String) field.get(renderer);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static String firstLine(String text) {
        String line = text.replace(MOCK, "").lines().filter(l -> l.chars().anyMatch(Character::isLetter)).findFirst().orElse(text).strip();
        return line.length() > 70 ? line.substring(0, 70) + "…" : line;
    }

    private static long millis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private static void waitFor(String message, BooleanSupplier condition, int timeoutSeconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < end) {
            boolean[] done = {false};
            ui(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                require(true, message);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out: " + message);
    }

    private static void ui(Runnable action) { ApplicationManager.getApplication().invokeAndWait(action); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
}
