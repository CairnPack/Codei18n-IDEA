package dev.codei18n.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CustomFoldRegion;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.FoldingModelEx;
import com.intellij.openapi.util.Key;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replace mode: hides translated comments behind fold regions that show the translation instead. Comments occupying
 * whole lines use a custom lines fold painted by {@link TranslationFoldRenderer}; comments sharing a line with code use
 * a never-expanding fold whose placeholder is the one-line translation. Comments touched by a caret are not folded:
 * the caller shows those as block inlays instead. Only fold regions change, never the document. Every method runs on
 * the EDT, and fold changes always happen in a folding batch outside any inlay batch.
 */
final class ReplacePresenter {
    /** Marks fold regions created here, so they are never confused with the IDE's own folds. */
    static final Key<Boolean> FOLD_MARK = Key.create("codei18n.translation.fold");
    private static final int MAX_PLACEHOLDER = 120;

    /** A hidden comment. The marker tracks the comment text across edits; for whole lines it differs from the fold. */
    private static final class Folded {
        final Editor editor;
        final TranslationHint hint;
        final RangeMarker comment;
        final FoldRegion region;
        final TranslationRenderer renderer; // Null for one-line placeholder folds.

        Folded(Editor editor, TranslationHint hint, RangeMarker comment, FoldRegion region, TranslationRenderer renderer) {
            this.editor = editor;
            this.hint = hint;
            this.comment = comment;
            this.region = region;
            this.renderer = renderer;
        }

        boolean isValid() { return comment.isValid() && region.isValid(); }

        TranslationHint.Key key() {
            return new TranslationHint.Key(comment.getEndOffset(), hint.commentLength(), hint.text(), hint.language());
        }

        TranslationHint current() {
            return new TranslationHint(comment.getEndOffset(), hint.commentLength(), hint.text(), hint.language(), hint.highlighting());
        }

        boolean isEditedBy(DocumentEvent event) {
            return !comment.isValid() || CommentGeometry.affects(comment.getStartOffset(), comment.getEndOffset(),
                    event.getOffset(), event.getOldLength(), event.getNewFragment());
        }

        /** Call inside a folding batch unless the editor is gone. */
        void remove() {
            if (!editor.isDisposed() && region.isValid()) editor.getFoldingModel().removeFoldRegion(region);
            comment.dispose();
        }
    }

    private final Map<Editor, List<Folded>> folded = new HashMap<>();
    private final List<Folded> stale = new ArrayList<>();

    /**
     * Folds every hint whose comment no caret touches, reusing folds that already show it; removes the other folds of
     * this editor. Returns the hints left unfolded (caret inside, folding disabled or a conflicting fold), which the
     * caller shows as block inlays.
     */
    List<TranslationHint> show(Editor editor, List<TranslationHint> hints) {
        Document document = editor.getDocument();
        CharSequence text = document.getImmutableCharSequence();
        Map<TranslationHint.Key, Folded> reusable = new HashMap<>();
        List<Folded> discard = new ArrayList<>();
        for (Folded item : folded.getOrDefault(editor, List.of())) {
            if (!item.isValid() || reusable.putIfAbsent(item.key(), item) != null) discard.add(item);
        }
        List<TranslationHint> unfolded = new ArrayList<>();
        List<TranslationHint> create = new ArrayList<>();
        List<Folded> next = new ArrayList<>();
        for (TranslationHint hint : hints) {
            int start = hint.offset() - hint.commentLength(), end = hint.offset();
            if (start < 0 || end > text.length()) continue;
            if (touchesCaret(editor, CommentGeometry.caretSpan(text, start, end))) {
                unfolded.add(hint);
                continue;
            }
            Folded kept = reusable.remove(hint.key());
            if (kept != null) next.add(kept);
            else create.add(hint);
        }
        discard.addAll(reusable.values());
        if (!create.isEmpty() || !discard.isEmpty()) {
            // Keep the caret's on-screen position when lines above it fold or unfold.
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                discard.forEach(Folded::remove);
                for (TranslationHint hint : create) {
                    Folded created = fold(editor, text, hint);
                    if (created != null) next.add(created);
                    else unfolded.add(hint);
                }
            }, false, true);
        }
        if (next.isEmpty()) folded.remove(editor);
        else folded.put(editor, next);
        return unfolded;
    }

    /** The folded hints of {@code editor} at their current offsets. */
    List<TranslationHint> current(Editor editor) {
        List<TranslationHint> hints = new ArrayList<>();
        for (Folded item : folded.getOrDefault(editor, List.of())) if (item.isValid()) hints.add(item.current());
        return hints;
    }

    Set<Editor> editors() { return Set.copyOf(folded.keySet()); }

    /** Whether a caret now touches a folded comment, which must then be revealed. */
    boolean touchesCaret(Editor editor) {
        List<Folded> items = folded.get(editor);
        if (items == null) return false;
        CharSequence text = editor.getDocument().getImmutableCharSequence();
        for (Folded item : items) {
            if (item.isValid() && touchesCaret(editor, CommentGeometry.caretSpan(text, item.comment.getStartOffset(), item.comment.getEndOffset()))) {
                return true;
            }
        }
        return false;
    }

    /** Start offset of the comment hidden by {@code region}, or -1 when it is not one of ours. */
    int commentStart(Editor editor, FoldRegion region) {
        if (region == null || region.getUserData(FOLD_MARK) == null) return -1;
        for (Folded item : folded.getOrDefault(editor, List.of())) {
            if (item.region == region && item.comment.isValid()) return item.comment.getStartOffset();
        }
        return -1;
    }

    /** Removes the folds of {@code editor}, restoring the original comments. */
    void clear(Editor editor) {
        List<Folded> previous = folded.remove(editor);
        if (previous == null || previous.isEmpty()) return;
        if (editor.isDisposed()) previous.forEach(Folded::remove);
        else editor.getFoldingModel().runBatchFoldingOperation(() -> previous.forEach(Folded::remove), false, true);
    }

    void clearAll() { List.copyOf(folded.keySet()).forEach(this::clear); }

    /** Re-resolves colors, fonts and TODO patterns after an appearance change. */
    void restyle() {
        for (Editor editor : List.copyOf(folded.keySet())) {
            for (Folded item : folded.get(editor)) if (item.renderer != null) item.renderer.invalidateLayout();
            update(editor);
        }
    }

    /** Re-measures custom folds, e.g. after the visible width changed. Not during a document change. */
    void update(Editor editor) {
        List<Folded> items = folded.get(editor);
        if (items == null || editor.isDisposed()) return;
        editor.getFoldingModel().runBatchFoldingOperation(() -> {
            for (Folded item : items) if (item.isValid() && item.region instanceof CustomFoldRegion custom) custom.update();
        }, false, true);
    }

    // Offsets before the change still match the markers.
    void collectStale(DocumentEvent event, Editor[] editors) {
        stale.clear();
        for (Editor editor : editors) {
            for (Folded item : folded.getOrDefault(editor, List.of())) if (item.isEditedBy(event)) stale.add(item);
        }
    }

    /** Forgets folds of edited comments now; removes their regions right after the document change completes. */
    void removeStale(Editor[] editors) {
        if (stale.isEmpty()) return;
        List<Folded> removed = List.copyOf(stale);
        stale.clear();
        for (Editor editor : editors) {
            List<Folded> items = folded.get(editor);
            if (items != null) items.removeIf(removed::contains);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            Map<Editor, List<Folded>> byEditor = new LinkedHashMap<>();
            for (Folded item : removed) byEditor.computeIfAbsent(item.editor, e -> new ArrayList<>()).add(item);
            byEditor.forEach((editor, items) -> {
                if (editor.isDisposed()) items.forEach(Folded::remove);
                else editor.getFoldingModel().runBatchFoldingOperation(() -> items.forEach(Folded::remove), false, true);
            });
        }, ModalityState.any());
    }

    /** Drops a released editor's state without touching its folding model. */
    void forget(Editor editor) {
        List<Folded> previous = folded.remove(editor);
        if (previous != null) previous.forEach(item -> item.comment.dispose());
    }

    private static Folded fold(Editor editor, CharSequence text, TranslationHint hint) {
        Document document = editor.getDocument();
        int start = hint.offset() - hint.commentLength(), end = hint.offset();
        var renderer = new TranslationRenderer(hint.text(), hint.language(), hint.highlighting());
        FoldRegion region;
        if (CommentGeometry.isWholeLine(text, start, end)) {
            int tabSize = editor.getSettings().getTabSize(editor.getProject());
            region = editor.getFoldingModel().addCustomLinesFolding(document.getLineNumber(start), document.getLineNumber(end),
                    new TranslationFoldRenderer(renderer, CommentGeometry.indentColumns(text, start, tabSize)));
        } else {
            renderer = null;
            String placeholder = CommentGeometry.placeholder(hint.language(), hint.text(), MAX_PLACEHOLDER);
            if (editor.getFoldingModel() instanceof FoldingModelEx model) {
                // Never expands by itself (caret, "Expand All"): only this presenter reveals the comment.
                region = model.createFoldRegion(start, end, placeholder, null, true);
            } else {
                region = editor.getFoldingModel().addFoldRegion(start, end, placeholder);
                if (region != null) region.setExpanded(false);
            }
        }
        if (region == null) return null;
        region.putUserData(FOLD_MARK, Boolean.TRUE);
        return new Folded(editor, hint, document.createRangeMarker(start, end), region, renderer);
    }

    private static boolean touchesCaret(Editor editor, int[] span) {
        for (Caret caret : editor.getCaretModel().getAllCarets()) {
            if (CommentGeometry.contains(span, caret.getOffset())) return true;
        }
        return false;
    }
}
