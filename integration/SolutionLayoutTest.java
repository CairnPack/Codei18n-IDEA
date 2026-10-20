package dev.codei18n.integration;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.codei18n.idea.TranslationRenderer;
import dev.codei18n.idea.TranslationSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Rider's layout for a solution in a repository subdirectory: the IDE project lives in
 * {@code <solution>/.idea/.idea.<name>}, the project base path is the solution directory, and the CLI wrote
 * .codei18n at the repository root above it.
 */
final class SolutionLayoutTest {
    private SolutionLayoutTest() {}

    static void run(Path repository) throws Exception {
        Path solution = repository.resolve("src/backend");
        Path store = Files.createDirectories(solution.resolve(".idea/.idea.Backend"));
        Path source = solution.resolve("Demo.java");
        Path mappings = repository.resolve(".codei18n/mappings.json");
        byte[] before = Files.readAllBytes(source);
        Project project = ProjectManagerEx.getInstanceEx().openProject(store, OpenProjectTask.build().withForceOpenInNewFrame(true));
        require(project != null, "Rider-shaped project opened");
        Editor[] editor = new Editor[1];
        try {
            require(Path.of(project.getBasePath()).equals(solution), "base path is the solution directory: " + project.getBasePath());
            ui(() -> {
                var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
                editor[0] = EditorFactory.getInstance().createEditor(FileDocumentManager.getInstance().getDocument(file), project);
                TranslationSettings.getInstance(project).update(true, "");
            });
            waitTranslation(editor[0], "计算账户余额", 60);
            pass("solution source finds .codei18n at the repository root above the base path");
            String original = Files.readString(mappings);
            Thread.sleep(2500); // Let the metadata poll record the open source's root as its baseline.
            Files.writeString(mappings, original.replace("计算账户余额", "仓库根目录的新译文"));
            // The root is outside the project's content, so the metadata poll must see this CLI write.
            waitTranslation(editor[0], "仓库根目录的新译文", 10);
            pass("external mapping rewrite at the repository root reloads");
            Files.writeString(mappings, original);
            waitTranslation(editor[0], "计算账户余额", 10);
            require(java.util.Arrays.equals(before, Files.readAllBytes(source)), "solution source bytes unchanged");
        } finally {
            ui(() -> {
                if (editor[0] != null) EditorFactory.getInstance().releaseEditor(editor[0]);
                ProjectManagerEx.getInstanceEx().forceCloseProject(project);
            });
        }
    }

    private static void waitTranslation(Editor editor, String expected, int timeoutSeconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < end) {
            boolean[] found = {false};
            ui(() -> {
                for (var hint : editor.getInlayModel().getBlockElementsInRange(0, editor.getDocument().getTextLength(),
                        TranslationRenderer.class)) {
                    if (expected.equals(hint.getRenderer().text())) found[0] = true;
                }
            });
            if (found[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for translation " + expected);
    }

    private static void ui(Runnable action) { ApplicationManager.getApplication().invokeAndWait(action); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        pass(message);
    }

    private static void pass(String message) { System.out.println("PASS: " + message); }
}
