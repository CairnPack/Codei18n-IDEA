package dev.codei18n.idea;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.util.ArrayList;
import java.util.List;

/** A translation to show below the comment ending at {@code offset}. */
record TranslationHint(int offset, int commentLength, String text, String language,
                       CommentHighlighting.Result highlighting) {
    /** Hints that would render identically; used to keep existing inlays across refreshes. */
    record Key(int offset, int commentLength, String text, String language) {}

    Key key() { return new Key(offset, commentLength, text, language); }

    /** Translations for every comment in {@code file}; call inside a cancellable read action. */
    static List<TranslationHint> scan(PsiFile file, MappingIndex index) {
        List<TranslationHint> hints = new ArrayList<>();
        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                ProgressManager.checkCanceled();
                if (element instanceof PsiComment comment) {
                    String translated = index.translation(comment.getText());
                    if (translated != null) hints.add(of(comment, translated, index.language()));
                } else {
                    super.visitElement(element);
                }
            }
        });
        return hints;
    }

    private static TranslationHint of(PsiComment comment, String translated, String language) {
        return new TranslationHint(comment.getTextRange().getEndOffset(), comment.getTextLength(), translated,
                language, CommentHighlighting.translate(comment, translated));
    }
}
