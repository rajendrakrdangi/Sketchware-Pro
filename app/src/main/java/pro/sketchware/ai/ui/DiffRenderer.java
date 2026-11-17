package pro.sketchware.ai.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;

import java.util.List;

import pro.sketchware.R;
import pro.sketchware.ai.diff.LineDiff;
import pro.sketchware.ai.generator.GeneratedFile;
import pro.sketchware.utility.ThemeUtils;

/**
 * Renders a file's diff the way Git tools do: old and new line numbers, a +/− sign, added lines
 * on green and deleted ones on red, and long runs of unchanged lines folded away.
 */
public final class DiffRenderer {

    /**
     * Unchanged lines shown around each change, like <code>git diff</code>'s default.
     */
    private static final int CONTEXT_LINES = 3;
    /**
     * Lines are padded to this width at most, so backgrounds line up without huge padding.
     */
    private static final int MAX_PAD = 160;
    public static final int ADDED_COLOR = 0xFF2E9E4F;
    public static final int DELETED_COLOR = 0xFFD1453B;
    private static final int ADDED_BACKGROUND = 0x332E9E4F;
    private static final int DELETED_BACKGROUND = 0x33D1453B;

    private DiffRenderer() {
    }

    /**
     * @param full Whether to show every line instead of folding unchanged ones away.
     */
    @NonNull
    public static CharSequence render(@NonNull Context context, @NonNull GeneratedFile file, boolean full) {
        List<LineDiff.Line> lines = file.getDiff().getLines();
        boolean[] shown = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (full || lines.get(i).type != LineDiff.Type.UNCHANGED) {
                int from = full ? i : Math.max(0, i - CONTEXT_LINES);
                int to = full ? i : Math.min(lines.size() - 1, i + CONTEXT_LINES);
                for (int j = from; j <= to; j++) {
                    shown[j] = true;
                }
            }
        }

        int maxOld = 0;
        int maxNew = 0;
        int width = 0;
        for (LineDiff.Line line : lines) {
            maxOld = Math.max(maxOld, line.oldNumber);
            maxNew = Math.max(maxNew, line.newNumber);
            width = Math.max(width, Math.min(MAX_PAD, line.text.length()));
        }
        int oldDigits = Math.max(1, String.valueOf(maxOld).length());
        int newDigits = Math.max(1, String.valueOf(maxNew).length());
        int gutterColor = ThemeUtils.getColor(context, R.attr.colorOnSurfaceVariant);
        int foldColor = ThemeUtils.getColor(context, R.attr.colorPrimary);

        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < lines.size()) {
            if (!shown[i]) {
                int start = i;
                while (i < lines.size() && !shown[i]) {
                    i++;
                }
                appendLine(out, context.getString(R.string.ai_diff_unchanged_lines, i - start), foldColor, Color.TRANSPARENT);
                continue;
            }
            LineDiff.Line line = lines.get(i);
            String oldNumber = line.oldNumber == 0 ? "" : String.valueOf(line.oldNumber);
            String newNumber = line.newNumber == 0 ? "" : String.valueOf(line.newNumber);
            char sign = switch (line.type) {
                case ADDED -> '+';
                case DELETED -> '-';
                case UNCHANGED -> ' ';
            };
            int lineStart = out.length();
            out.append(pad(oldNumber, oldDigits)).append(' ').append(pad(newNumber, newDigits)).append(' ');
            out.setSpan(new ForegroundColorSpan(gutterColor), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int signStart = out.length();
            out.append(sign).append(' ');
            if (line.type != LineDiff.Type.UNCHANGED) {
                out.setSpan(new ForegroundColorSpan(line.type == LineDiff.Type.ADDED ? ADDED_COLOR : DELETED_COLOR),
                        signStart, signStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append(CodeSyntaxHighlighter.highlight(context, line.text, file.getName()));
            for (int p = line.text.length(); p < width; p++) {
                out.append(' ');
            }
            if (line.type != LineDiff.Type.UNCHANGED) {
                out.setSpan(new BackgroundColorSpan(line.type == LineDiff.Type.ADDED ? ADDED_BACKGROUND : DELETED_BACKGROUND),
                        lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append('\n');
            i++;
        }
        // No trailing line break
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    /**
     * @return "+12 −4" with the numbers in green and red, leaving out a side that is 0.
     */
    @NonNull
    public static CharSequence stats(int added, int deleted) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (added > 0 || deleted == 0) {
            int start = out.length();
            out.append('+').append(String.valueOf(added));
            out.setSpan(new ForegroundColorSpan(ADDED_COLOR), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (deleted > 0) {
            if (out.length() > 0) {
                out.append(' ');
            }
            int start = out.length();
            out.append('−').append(String.valueOf(deleted));
            out.setSpan(new ForegroundColorSpan(DELETED_COLOR), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static void appendLine(SpannableStringBuilder out, String text, int color, int background) {
        int start = out.length();
        out.append(text);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (background != Color.TRANSPARENT) {
            out.setSpan(new BackgroundColorSpan(background), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append('\n');
    }

    private static String pad(String text, int width) {
        StringBuilder padded = new StringBuilder();
        for (int i = text.length(); i < width; i++) {
            padded.append(' ');
        }
        return padded.append(text).toString();
    }
}
