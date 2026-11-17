package pro.sketchware.ai.diff;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes a line-by-line diff between two texts with Myers' O(ND) algorithm, the one Git uses by
 * default. Counts and lines come from the actual texts, never from estimates.
 */
public final class LineDiff {

    /**
     * Upper bound for the ints kept while backtracking, about 16 MB. Texts whose diff would need
     * more are reported as fully replaced, which is still correct, just not minimal.
     */
    private static final long MAX_TRACE_INTS = 4_000_000L;

    private LineDiff() {
    }

    @NonNull
    public static Result diff(@Nullable String before, @Nullable String after) {
        String[] a = split(before);
        String[] b = split(after);
        List<Line> lines = myers(a, b);
        int added = 0;
        int deleted = 0;
        for (Line line : lines) {
            if (line.type == Type.ADDED) {
                added++;
            } else if (line.type == Type.DELETED) {
                deleted++;
            }
        }
        return new Result(lines, added, deleted);
    }

    @NonNull
    static String[] split(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String normalized = text.replace("\r\n", "\n");
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\n", -1);
    }

    private static List<Line> myers(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        int max = n + m;
        if (max == 0) {
            return new ArrayList<>();
        }
        int offset = max + 1;
        int width = 2 * max + 2;
        long maxD = Math.max(1, MAX_TRACE_INTS / width);
        int[] v = new int[width];
        List<int[]> trace = new ArrayList<>();

        for (int d = 0; d <= max; d++) {
            if (d > maxD) {
                return replaceAll(a, b);
            }
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    return backtrack(a, b, trace, offset);
                }
            }
        }
        return replaceAll(a, b);
    }

    private static List<Line> backtrack(String[] a, String[] b, List<int[]> trace, int offset) {
        List<Line> reversed = new ArrayList<>();
        int x = a.length;
        int y = b.length;
        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int prevK = (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) ? k + 1 : k - 1;
            int prevX = v[offset + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                reversed.add(new Line(Type.UNCHANGED, a[x - 1], x, y));
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) {
                    reversed.add(new Line(Type.ADDED, b[y - 1], 0, y));
                } else {
                    reversed.add(new Line(Type.DELETED, a[x - 1], x, 0));
                }
            }
            x = prevX;
            y = prevY;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<Line> replaceAll(String[] a, String[] b) {
        List<Line> lines = new ArrayList<>(a.length + b.length);
        for (int i = 0; i < a.length; i++) {
            lines.add(new Line(Type.DELETED, a[i], i + 1, 0));
        }
        for (int i = 0; i < b.length; i++) {
            lines.add(new Line(Type.ADDED, b[i], 0, i + 1));
        }
        return lines;
    }

    public enum Type {
        UNCHANGED,
        ADDED,
        DELETED
    }

    /**
     * One line of a diff. Line numbers start at 1; 0 means the line doesn't exist on that side.
     */
    public static final class Line {
        public final Type type;
        public final String text;
        public final int oldNumber;
        public final int newNumber;

        Line(Type type, String text, int oldNumber, int newNumber) {
            this.type = type;
            this.text = text;
            this.oldNumber = oldNumber;
            this.newNumber = newNumber;
        }
    }

    public static final class Result {
        private final List<Line> lines;
        private final int added;
        private final int deleted;

        Result(List<Line> lines, int added, int deleted) {
            this.lines = lines;
            this.added = added;
            this.deleted = deleted;
        }

        @NonNull
        public List<Line> getLines() {
            return lines;
        }

        public int getAdded() {
            return added;
        }

        public int getDeleted() {
            return deleted;
        }

        public boolean hasChanges() {
            return added > 0 || deleted > 0;
        }
    }
}
