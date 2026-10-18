package dev.codei18n.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns the translation inlays and per-editor listeners. Every method runs on the EDT. */
final class InlayPresenter implements Disposable {
    /** A displayed hint. The inlay sits at the comment end and follows edits, so the comment length stays exact. */
    private record Shown(Inlay<TranslationRenderer> inlay, TranslationHint hint) {
        TranslationHint.Key key() {
            return new TranslationHint.Key(inlay.getOffset(), hint.commentLength(), hint.text(), hint.language());
        }

        /** Whether the edit may change this comment's text, making the translation stale. */
        boolean isEditedBy(DocumentEvent event) {
            if (!inlay.isValid()) return true;
            int end = inlay.getOffset(), begin = end - hint.commentLength();
            int start = event.getOffset();
            // Removing text right after the comment may join the next line into it.
            if (event.getOldLength() > 0) return start <= end && start + event.getOldLength() > begin;
            // Text typed at the very end may extend a line comment, unless it starts a new line.
            if (start == end) return !startsWithLineBreak(event.getNewFragment());
            // Insertions before the comment (indentation) or after it leave the comment intact.
            return begin < start && start < end;
        }

        void dispose() { if (inlay.isValid()) Disposer.dispose(inlay); }
    }

    private final Project project;
    private final Runnable onEdit;
    private final Map<Editor, List<Shown>> shown = new HashMap<>();
    // Per-editor disposables: listeners must not keep released editors reachable from the service.
    private final Map<Editor, Disposable> editorListeners = new HashMap<>();
    private final List<Shown> stale = new ArrayList<>();

    /** {@code onEdit} runs after any edit to a document shown in this project's editors. */
    InlayPresenter(Project project, Runnable onEdit) {
        this.project = project;
        this.onEdit = onEdit;
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
                }
            }
        }, listeners);
    }

    void release(Editor editor) {
        clear(editor);
        Disposable listeners = editorListeners.remove(editor);
        if (listeners != null) Disposer.dispose(listeners);
    }

    /** Shows exactly {@code hints}, keeping inlays that already display one of them so lines don't move. */
    void show(Editor editor, List<TranslationHint> hints) {
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

    /** Re-resolves colors, fonts and TODO patterns after an appearance change. */
    void restyle() {
        for (Editor editor : shown.keySet()) {
            for (Shown item : shown.get(editor)) item.inlay.getRenderer().invalidateLayout();
            update(editor);
        }
    }

    void clear(Editor editor) {
        List<Shown> previous = shown.remove(editor);
        if (previous != null) previous.forEach(Shown::dispose);
    }

    void clearAll() { List.copyOf(shown.keySet()).forEach(this::clear); }

    private void update(Editor editor) {
        for (Shown item : shown.getOrDefault(editor, List.of())) if (item.inlay.isValid()) item.inlay.update();
    }

    private Editor[] editors(DocumentEvent event) {
        return EditorFactory.getInstance().getEditors(event.getDocument(), project);
    }

    // Old offsets still match the inlays before the change; dispose after it completes.
    private void collectStale(DocumentEvent event) {
        stale.clear();
        for (Editor editor : editors(event)) {
            for (Shown item : shown.getOrDefault(editor, List.of())) if (item.isEditedBy(event)) stale.add(item);
        }
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
        onEdit.run();
    }

    private static boolean dispose(Shown item) {
        item.dispose();
        return true;
    }

    private static boolean startsWithLineBreak(CharSequence text) {
        return !text.isEmpty() && (text.charAt(0) == '\n' || text.charAt(0) == '\r');
    }

    @Override public void dispose() {
        Runnable cleanup = () -> { clearAll(); editorListeners.clear(); };
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) cleanup.run();
        else application.invokeLater(cleanup, ModalityState.any());
    }
}
