package dev.codei18n.idea;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A translation to show below the comment (group) ending at {@code offset}. */
record TranslationHint(int offset, int commentLength, String text, String language,
                       CommentHighlighting.Result highlighting) {
    /** Hints that would render identically; used to keep existing inlays across refreshes. */
    record Key(int offset, int commentLength, String text, String language) {}

    Key key() { return new Key(offset, commentLength, text, language); }

    /**
     * Translations for every comment in {@code file}; call inside a cancellable read action. Adjacent line comments
     * are grouped like core groups them (docs/index-format.md §6.1) and matched as one record; a group that matches
     * nothing falls back to its individual comments (mappings written by older core versions). Files without any
     * comment PSI use the index.json ranges instead (C2).
     */
    static List<TranslationHint> scan(PsiFile file, MappingIndex index, MappingIndex.FileContext context) {
        List<PsiComment> comments = new ArrayList<>();
        file.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override public void visitElement(PsiElement element) {
                ProgressManager.checkCanceled();
                if (element instanceof PsiComment comment) comments.add(comment);
                else super.visitElement(element);
            }
        });
        if (comments.isEmpty()) return fallback(index, context);
        comments.sort(Comparator.comparingInt(c -> c.getTextRange().getStartOffset()));
        List<CommentGroups.Piece> pieces = comments.stream()
                .map(c -> new CommentGroups.Piece(c.getTextRange().getStartOffset(), c.getTextRange().getEndOffset()))
                .toList();
        List<TranslationHint> hints = new ArrayList<>();
        List<List<CommentGroups.Piece>> groups = CommentGroups.group(context.text(), pieces);
        // Occurrence rank of each group among groups with the same textHash and parts (pairs identical comments by order).
        java.util.Map<String, Integer> totals = new java.util.HashMap<>(), seen = new java.util.HashMap<>();
        List<String> keys = new ArrayList<>();
        for (List<CommentGroups.Piece> group : groups) {
            String key = group.size() + ":" + CommentText.textHash(
                    context.text().substring(group.get(0).start(), group.get(group.size() - 1).end()));
            keys.add(key);
            totals.merge(key, 1, Integer::sum);
        }
        int next = 0, g = 0;
        for (List<CommentGroups.Piece> group : groups) {
            String key = keys.get(g++);
            int rank = seen.merge(key, 1, Integer::sum) - 1;
            ProgressManager.checkCanceled();
            List<PsiComment> members = comments.subList(next, next + group.size());
            next += group.size();
            int start = group.get(0).start(), end = group.get(group.size() - 1).end();
            MappingIndex.Match match = index.match(context, start, end, group.size(), rank, totals.get(key));
            if (match.found()) {
                if (match.text() != null) hints.add(of(members.get(members.size() - 1), start, end, match.text(), index.language()));
                continue;
            }
            if (group.size() > 1) {
                for (int i = 0; i < group.size(); i++) {
                    CommentGroups.Piece piece = group.get(i);
                    MappingIndex.Match single = index.match(context, piece.start(), piece.end(), 1);
                    if (single.text() != null) hints.add(of(members.get(i), piece.start(), piece.end(), single.text(), index.language()));
                }
            }
        }
        return hints;
    }

    private static List<TranslationHint> fallback(MappingIndex index, MappingIndex.FileContext context) {
        List<TranslationHint> hints = new ArrayList<>();
        for (MappingIndex.Located located : index.fallback(context)) {
            hints.add(new TranslationHint(located.end(), located.end() - located.start(), located.text(),
                    index.language(), CommentHighlighting.plain(located.type(), located.text(), context.relativePath(),
                    index.language())));
        }
        return hints;
    }

    private static TranslationHint of(PsiComment last, int start, int end, String translated, String language) {
        return new TranslationHint(end, end - start, translated, language,
                CommentHighlighting.translate(last, translated, language));
    }
}
