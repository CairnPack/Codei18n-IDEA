package dev.codei18n.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.CustomFoldRegion;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * C4 display modes against the demo project: replace mode folds both comments behind their translations, a caret in a
 * comment reveals it (translation shown below instead), leaving folds it again, switching back restores the block
 * inlays at once, and the on/off toggle clears and restores translations. The document never changes.
 * Call from a non-EDT thread (it waits for debounced work) while the editor shows both demo translations in below mode;
 * it leaves translations enabled in below mode. Uses only public plugin API: the harness has its own class loader.
 */
public final class DisplayModeTest {
    public static void run(Project project, Editor editor, String translation) throws Exception {
        var service = TranslationService.getInstance(project);
        var settings = TranslationSettings.getInstance(project);
        var document = editor.getDocument();
        String[] original = new String[1];
        ui(() -> original[0] = document.getText());
        int comment = original[0].indexOf("// Calculate account balance");
        int code = original[0].indexOf("return income");
        require(comment > 0 && code > comment, "display mode fixture located");
        try {
            ui(() -> {
                require(settings.displayMode() == DisplayMode.BELOW && blocks(editor).size() == 2, "starts in below mode with two block inlays");
                editor.getCaretModel().removeSecondaryCarets();
                editor.getCaretModel().moveToOffset(code);
                service.setDisplayMode(DisplayMode.REPLACE);
                require(settings.displayMode() == DisplayMode.REPLACE, "replace mode persisted in settings");
                require(folds(editor).size() == 2 && blocks(editor).isEmpty(), "switching to replace mode folds both comments at once");
            });
            ui(() -> {
                var folds = folds(editor);
                require(folds.stream().anyMatch(f -> translation.equals(renderer(f).text())), "fold renderer shows translation " + translation);
                require(folds.stream().allMatch(f -> f.getHeightInPixels() >= editor.getLineHeight() && f.getWidthInPixels() > 0),
                        "translation folds have display dimensions");
                require(editor.getFoldingModel().isOffsetCollapsed(comment + 3), "original comment hidden by its fold");
                require(!editor.getFoldingModel().isOffsetCollapsed(code), "code stays visible");
                for (CustomFoldRegion fold : folds) paint(fold);
                require(true, "translation fold paints");
                require(document.getText().equals(original[0]), "replace mode leaves the document unchanged");
                editor.getCaretModel().moveToOffset(comment + 5);
            });
            waitFor("caret inside the comment reveals the original", () ->
                    !editor.getFoldingModel().isOffsetCollapsed(comment + 3) && folds(editor).size() == 1
                    && blocks(editor).size() == 1 && translation.equals(blocks(editor).get(0)));
            ui(() -> {
                require(editor.getFoldingModel().isOffsetCollapsed(3), "the other comment stays folded");
                require(document.getText().equals(original[0]), "revealing a comment leaves the document unchanged");
                editor.getCaretModel().moveToOffset(code);
            });
            waitFor("caret leaving the comment folds it again", () ->
                    folds(editor).size() == 2 && blocks(editor).isEmpty() && editor.getFoldingModel().isOffsetCollapsed(comment + 3));
            ui(() -> {
                service.toggleDisplayMode();
                require(settings.displayMode() == DisplayMode.BELOW, "toggle returns to below mode");
                require(folds(editor).isEmpty() && blocks(editor).size() == 2, "switching back restores block inlays at once");
                require(!editor.getFoldingModel().isOffsetCollapsed(comment + 3), "original comment visible again");
                require(document.getText().equals(original[0]), "below mode leaves the document unchanged");
                service.toggleTranslations();
                require(!settings.isEnabled() && blocks(editor).isEmpty() && folds(editor).isEmpty(), "turning translations off clears them at once");
                service.toggleTranslations();
                require(settings.isEnabled(), "translations turned back on");
            });
            waitFor("turning translations on restores them", () -> blocks(editor).size() == 2);
            ui(() -> {
                service.setDisplayMode(DisplayMode.REPLACE);
                service.toggleTranslations();
                require(blocks(editor).isEmpty() && folds(editor).isEmpty(), "turning off in replace mode removes the folds");
                service.toggleTranslations();
            });
            waitFor("turning on in replace mode folds again", () -> folds(editor).size() == 2 && blocks(editor).isEmpty());
            ui(() -> {
                service.setDisplayMode(DisplayMode.BELOW);
                require(folds(editor).isEmpty() && blocks(editor).size() == 2, "below mode restored");
                require(document.getText().equals(original[0]), "document unchanged after every mode switch");
            });
        } finally {
            ui(() -> {
                if (!settings.isEnabled()) service.setTranslationsEnabled(true);
                if (settings.displayMode() != DisplayMode.BELOW) service.setDisplayMode(DisplayMode.BELOW);
                editor.getCaretModel().moveToOffset(0);
            });
        }
    }

    private static List<CustomFoldRegion> folds(Editor editor) {
        return Arrays.stream(editor.getFoldingModel().getAllFoldRegions())
                .filter(r -> r.isValid() && r instanceof CustomFoldRegion c && c.getRenderer() instanceof TranslationFoldRenderer)
                .map(r -> (CustomFoldRegion) r).toList();
    }

    /** Texts of the translation block inlays. */
    private static List<String> blocks(Editor editor) {
        return editor.getInlayModel().getBlockElementsInRange(0, editor.getDocument().getTextLength(), TranslationRenderer.class)
                .stream().map(i -> i.getRenderer().text()).toList();
    }

    private static TranslationFoldRenderer renderer(CustomFoldRegion fold) {
        return (TranslationFoldRenderer) fold.getRenderer();
    }

    private static void paint(CustomFoldRegion fold) {
        var image = new BufferedImage(Math.max(1, fold.getWidthInPixels()), Math.max(1, fold.getHeightInPixels()), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            fold.getRenderer().paint(fold, graphics, new Rectangle(0, 0, image.getWidth(), image.getHeight()), new TextAttributes());
        } finally {
            graphics.dispose();
        }
    }

    private static void waitFor(String message, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            boolean[] done = {false};
            ui(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                require(true, message);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out: " + message);
    }

    private static void ui(Runnable action) { ApplicationManager.getApplication().invokeAndWait(action); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
}
