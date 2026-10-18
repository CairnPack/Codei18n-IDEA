package dev.codei18n.idea;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups adjacent physical comments exactly like core does (docs/index-format.md §6.1): line comments with the same
 * marker, on consecutive lines, starting in the same column and each alone on its line form one record.
 */
public final class CommentGroups {
    /** A physical comment: [start, end) offsets in the document text. */
    public record Piece(int start, int end) {}

    private CommentGroups() {}

    /** The marker of a line comment: leading slashes plus one optional '!'; "" for block comments. */
    public static String marker(CharSequence text) {
        if (text.length() < 2 || text.charAt(0) != '/' || text.charAt(1) != '/') return "";
        int i = 0;
        while (i < text.length() && text.charAt(i) == '/') i++;
        if (i < text.length() && text.charAt(i) == '!') i++;
        return text.subSequence(0, i).toString();
    }

    /** Groups {@code pieces} (sorted by start) of {@code text}. */
    public static List<List<Piece>> group(String text, List<Piece> pieces) {
        int[] lineStarts = lineStarts(text);
        List<List<Piece>> out = new ArrayList<>();
        for (int i = 0; i < pieces.size(); ) {
            List<Piece> group = new ArrayList<>();
            Piece first = pieces.get(i);
            group.add(first);
            String marker = marker(text.substring(first.start(), first.end()));
            if (!marker.isEmpty() && ownLine(text, lineStarts, first)) {
                for (int j = i + 1; j < pieces.size(); j++) {
                    Piece prev = group.get(group.size() - 1), next = pieces.get(j);
                    if (!marker.equals(marker(text.substring(next.start(), next.end())))
                            || line(lineStarts, next.start()) != line(lineStarts, prev.end()) + 1
                            || column(lineStarts, next.start()) != column(lineStarts, first.start())
                            || !ownLine(text, lineStarts, next)) break;
                    group.add(next);
                }
            }
            out.add(group);
            i += group.size();
        }
        return out;
    }

    static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 0-based line of {@code offset}. An offset at the very end of a line comment (before '\n') stays on that line. */
    static int line(int[] lineStarts, int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    static int column(int[] lineStarts, int offset) { return offset - lineStarts[line(lineStarts, offset)]; }

    private static boolean ownLine(String text, int[] lineStarts, Piece p) {
        int start = lineStarts[line(lineStarts, p.start())];
        for (int i = start; i < p.start(); i++) if (!CommentText.isWhiteSpace(text.charAt(i))) return false;
        return true;
    }
}
