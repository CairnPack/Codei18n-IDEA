package dev.codei18n.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.Alarm;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the translation inlays, replace-mode folds and per-editor listeners. Every method runs on the EDT.
 * Below mode shows every hint as a block inlay. Replace mode folds each comment away behind its translation
 * ({@link ReplacePresenter}); a comment touched by a caret is revealed and its translation shown below it instead, and
 * folds again shortly after the caret leaves.
 */
final class InlayPresenter implements Disposable {
    /** Delay before a comment the caret left is folded again, so moving through code doesn't make lines jump. */
    private static final int RECOLLAPSE_DELAY_MS = 600;

    /** A displayed hint. The inlay sits at the comment end and follows edits, so the comment length stays exact. */
    private record Shown(Inlay<TranslationRenderer> inlay, TranslationHint hint) {
        TranslationHint.Key key() {
            return new TranslationHint.Key(inlay.getOffset(), hint.commentLength(), hint.text(), hint.language());
        }

        TranslationHint current() {
            return new TranslationHint(inlay.getOffset(), hint.commentLength(), hint.text(), hint.language(), hint.highlighting());
        }

        /** Whether the edit may change this comment's text, making the translation stale. */
        boolean isEditedBy(DocumentEvent event) {
            if (!inlay.isValid()) return true;
            int end = inlay.getOffset();
            return CommentGeometry.affects(end - hint.commentLength(), end, event.getOffset(), event.getOldLength(),
                    event.getNewFragment());
        }

        void dispose() { if (inlay.isValid()) Disposer.dispose(inlay); }
    }

    private final Project project;
    private final Runnable onEdit;
    private final Map<Editor, List<Shown>> shown = new HashMap<>();
    // Per-editor disposables: listeners must not keep released editors reachable from the service.
    private final Map<Editor, Disposable> editorListeners = new HashMap<>();
    private final List<Shown> stale = new ArrayList<>();
    private final ReplacePresenter replace = new ReplacePresenter();
    private final Alarm caretAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final Set<Editor> pendingCaret = new LinkedHashSet<>();
    private DisplayMode mode;

    /** {@code onEdit} runs after any edit to a document shown in this project's editors. */
    InlayPresenter(Project project, Runnable onEdit) {
        this.project = project;
        this.onEdit = onEdit;
        this.mode = TranslationSettings.getInstance(project).displayMode();
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void beforeDocumentChange(DocumentEvent event) { collectStale(event); }
            @Override public void documentChanged(DocumentEvent event) { removeStale(event); }
        }, this);
    }

    void attach(Editor editor) {
        if (editorListeners.containsKey(editor)) return;
        Disposable listeners = Disposer.newDisposable("CodeI18n editor listeners");
        Disposer.register(this, listeners);
        editorListeners.put(editor, listeners);
        editor.getScrollingModel().addVisibleAreaListener(new VisibleAreaListener() {
            @Override public void visibleAreaChanged(VisibleAreaEvent event) {
                // Translations wrap to the visible width.
                if (event.getOldRectangle() == null || event.getOldRectangle().width != event.getNewRectangle().width) {
                    update(editor);
                    // Custom folds must not be re-measured during a document change.
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (editorListeners.containsKey(editor)) replace.update(editor);
                    }, ModalityState.any());
                }
            }
        }, listeners);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent event) { caretMoved(editor); }
            @Override public void caretAdded(CaretEvent event) { caretMoved(editor); }
            @Override public void caretRemoved(CaretEvent event) { caretMoved(editor); }
        }, listeners);
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override public void mouseClicked(EditorMouseEvent event) { clicked(editor, event); }
        }, listeners);
    }

    void release(Editor editor) {
        clearBlocks(editor);
        replace.forget(editor);
        pendingCaret.remove(editor);
        Disposable listeners = editorListeners.remove(editor);
        if (listeners != null) Disposer.dispose(listeners);
    }

    DisplayMode mode() { return mode; }

    /** Switches display mode, re-presenting the translations already shown in every editor at once. */
    void setMode(DisplayMode next) {
        if (next == mode) return;
        Set<Editor> editors = new LinkedHashSet<>(shown.keySet());
        editors.addAll(replace.editors());
        Map<Editor, List<TranslationHint>> hints = new HashMap<>();
        for (Editor editor : editors) hints.put(editor, current(editor));
        mode = next;
        pendingCaret.clear();
        for (Editor editor : editors) if (!editor.isDisposed()) show(editor, hints.get(editor));
    }

    /** Shows exactly {@code hints} in the current mode, keeping inlays and folds that already display one of them. */
    void show(Editor editor, List<TranslationHint> hints) {
        if (mode == DisplayMode.REPLACE) {
            // Folding first, in its own batch: fold operations must not run inside an inlay batch.
            showBlocks(editor, replace.show(editor, hints));
        } else {
            replace.clear(editor);
            showBlocks(editor, hints);
        }
    }

    private void showBlocks(Editor editor, List<TranslationHint> hints) {
        Map<TranslationHint.Key, Shown> reusable = new HashMap<>();
        for (Shown item : shown.getOrDefault(editor, List.of())) {
            if (!item.inlay.isValid() || reusable.putIfAbsent(item.key(), item) != null) item.dispose();
        }
        List<Shown> next = new ArrayList<>();
        editor.getInlayModel().execute(true, () -> {
            for (TranslationHint hint : hints) {
                Shown kept = reusable.remove(hint.key());
                if (kept != null) {
                    next.add(kept);
                    continue;
                }
                var renderer = new TranslationRenderer(hint.text(), hint.language(), hint.highlighting());
                var inlay = editor.getInlayModel().addBlockElement(hint.offset(), true, false, 0, renderer);
                if (inlay != null) next.add(new Shown(inlay, hint));
            }
            reusable.values().forEach(Shown::dispose);
        });
        shown.put(editor, next);
    }

    /** Every hint currently presented in {@code editor}, folded or as a block, at its current offset. */
    private List<TranslationHint> current(Editor editor) {
        List<TranslationHint> hints = new ArrayList<>(replace.current(editor));
        for (Shown item : shown.getOrDefault(editor, List.of())) if (item.inlay.isValid()) hints.add(item.current());
        hints.sort(Comparator.comparingInt(TranslationHint::offset));
        return hints;
    }

    /** Re-resolves colors, fonts and TODO patterns after an appearance change. */
    void restyle() {
        for (Editor editor : shown.keySet()) {
            for (Shown item : shown.get(editor)) item.inlay.getRenderer().invalidateLayout();
            update(editor);
        }
        replace.restyle();
    }

    void clear(Editor editor) {
        clearBlocks(editor);
        replace.clear(editor);
    }

    void clearAll() {
        List.copyOf(shown.keySet()).forEach(this::clearBlocks);
        replace.clearAll();
        pendingCaret.clear();
    }

    private void clearBlocks(Editor editor) {
        List<Shown> previous = shown.remove(editor);
        if (previous != null) previous.forEach(Shown::dispose);
    }

    private void update(Editor editor) {
        for (Shown item : shown.getOrDefault(editor, List.of())) if (item.inlay.isValid()) item.inlay.update();
    }

    /**
     * Replace mode: a caret entering a folded comment reveals it at once; a caret leaving a revealed comment folds it
     * again after a short delay. Folding runs later, never inside the caret event.
     */
    private void caretMoved(Editor editor) {
        if (mode != DisplayMode.REPLACE || !editorListeners.containsKey(editor)) return;
        boolean reveal = replace.touchesCaret(editor);
        if (!reveal && shown.getOrDefault(editor, List.of()).isEmpty()) return;
        pendingCaret.add(editor);
        caretAlarm.cancelAllRequests();
        caretAlarm.addRequest(this::reshowPending, reveal ? 0 : RECOLLAPSE_DELAY_MS);
    }

    private void reshowPending() {
        List<Editor> editors = List.copyOf(pendingCaret);
        pendingCaret.clear();
        if (mode != DisplayMode.REPLACE) return;
        for (Editor editor : editors) {
            if (!editor.isDisposed() && editorListeners.containsKey(editor)) show(editor, current(editor));
        }
    }

    /** Clicking a translation that replaces a comment moves the caret into the comment, revealing the original. */
    private void clicked(Editor editor, EditorMouseEvent event) {
        if (mode != DisplayMode.REPLACE || event.getArea() != EditorMouseEventArea.EDITING_AREA) return;
        MouseEvent mouse = event.getMouseEvent();
        if (mouse.getButton() != MouseEvent.BUTTON1 || mouse.isShiftDown() || mouse.isAltDown()) return;
        var region = event.getCollapsedFoldRegion();
        if (region == null) region = editor.getFoldingModel().getCollapsedRegionAtOffset(event.getOffset());
        int start = replace.commentStart(editor, region);
        if (start < 0) return;
        editor.getCaretModel().removeSecondaryCarets();
        editor.getCaretModel().moveToOffset(start);
    }

    private Editor[] editors(DocumentEvent event) {
        return EditorFactory.getInstance().getEditors(event.getDocument(), project);
    }

    // Old offsets still match the inlays before the change; dispose after it completes.
    private void collectStale(DocumentEvent event) {
        stale.clear();
        Editor[] editors = editors(event);
        for (Editor editor : editors) {
            for (Shown item : shown.getOrDefault(editor, List.of())) if (item.isEditedBy(event)) stale.add(item);
        }
        replace.collectStale(event, editors);
    }

    // Only the edited comment's translation is stale; keep the others so lines don't jump.
    private void removeStale(DocumentEvent event) {
        Editor[] editors = editors(event);
        if (editors.length == 0) return;
        for (Editor editor : editors) {
            List<Shown> items = shown.get(editor);
            if (items != null) items.removeIf(item -> stale.contains(item) && dispose(item));
        }
        stale.clear();
        replace.removeStale(editors);
        onEdit.run();
    }

    private static boolean dispose(Shown item) {
        item.dispose();
        return true;
    }

    @Override public void dispose() {
        Runnable cleanup = () -> { clearAll(); editorListeners.clear(); };
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) cleanup.run();
        else application.invokeLater(cleanup, ModalityState.any());
    }
}
