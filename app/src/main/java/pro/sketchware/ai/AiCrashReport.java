package pro.sketchware.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the crash in what the logcat reader received, so RKD AI can be asked to fix it.
 * <p>
 * A Sketchware test app's crash handler sends the whole stack trace as one log entry; a device's
 * own log has it line by line under AndroidRuntime (or SketchwareCrash). Both are understood, the
 * latest crash wins, and plain error lines are used when there's no crash.
 */
public final class AiCrashReport {

    /**
     * The tag the test app's crash handler writes its stack trace to the device log with.
     */
    public static final String CRASH_TAG = "SketchwareCrash";
    static final int MAX_CHARS = 6000;
    private static final int MAX_FOLLOWING_LINES = 60;

    private static final Pattern LOG_LINE = Pattern.compile("^(.*\\d) ([VAFDEIW]) (.*?): (.*)");
    private static final Pattern EXCEPTION = Pattern.compile("(^|\\s|:)([a-zA-Z_][\\w$]*\\.)+[A-Z][\\w$]*(Exception|Error)\\b");
    private static final String[] FRAMEWORK_PACKAGES = {"android.", "androidx.", "java.", "javax.", "dalvik.",
            "com.android.", "com.google.android.", "kotlin.", "kotlinx.", "sun.", "libcore.", "jdk."};
    private static final Pattern STACK_LINE = Pattern.compile("^\\s*(at |Caused by|\\.\\.\\. \\d+ more|Suppressed:)");

    private AiCrashReport() {
    }

    /**
     * @param logs Log entries as received, oldest first.
     * @return The latest crash or error with its stack trace, or null if there's none.
     */
    @Nullable
    public static String extract(@NonNull List<String> logs) {
        List<Entry> entries = new ArrayList<>();
        for (String log : logs) {
            if (log != null) {
                entries.add(new Entry(log));
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            // A whole stack trace in one entry, from the test app's crash handler
            if (entry.body.contains("\n") && isException(firstLine(entry.body)) && entry.body.contains("at ")) {
                return limit(entry.body);
            }
            // A crash written line by line
            if ("AndroidRuntime".equals(entry.tag) && entry.body.contains("FATAL EXCEPTION")
                    || CRASH_TAG.equals(entry.tag) && (i == 0 || !CRASH_TAG.equals(entries.get(i - 1).tag))) {
                return limit(join(entries, i, entry.tag));
            }
        }
        // No crash: the latest error with the stack lines after it
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (("E".equals(entry.type) || "F".equals(entry.type) || entry.type == null) && isException(entry.body)) {
                StringBuilder out = new StringBuilder(entry.body.trim());
                for (int j = i + 1; j < entries.size() && j <= i + MAX_FOLLOWING_LINES; j++) {
                    String body = entries.get(j).body;
                    if (!STACK_LINE.matcher(body).find()) {
                        break;
                    }
                    out.append('\n').append(body.trim());
                }
                return limit(out.toString());
            }
        }
        return null;
    }

    /**
     * @return What's asked of RKD AI, with the crash.
     */
    @NonNull
    public static String prompt(@NonNull String crash) {
        return "My app crashed while I was running it. This is the error from the logcat reader:\n\n```\n"
                + crash + "\n```\n\nFind what causes it in the project and fix it so the app no longer crashes. "
                + "Tell me in simple words what was wrong.";
    }

    /**
     * @return A short version for the chat: what crashed, its cause, and where in the app's own
     * code, without Android's own lines.
     */
    @NonNull
    public static String summary(@NonNull String crash) {
        StringBuilder out = new StringBuilder("Fix this crash from the logcat reader:\n").append(headline(crash));
        String cause = null;
        List<String> appFrames = new ArrayList<>();
        for (String line : crash.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by")) {
                cause = trimmed;
            } else if (trimmed.startsWith("at ") && !isFrameworkFrame(trimmed) && appFrames.size() < 3) {
                appFrames.add(trimmed);
            }
        }
        if (cause != null && !crash.trim().startsWith(cause)) {
            out.append('\n').append(cause);
        }
        for (String frame : appFrames) {
            out.append('\n').append(frame);
        }
        return out.toString();
    }

    /**
     * Drops the long runs of Android's and Java's own stack lines, which only cost tokens: two of
     * each run stay, so the model still sees how the app's code was reached.
     */
    @NonNull
    static String compact(@NonNull String crash) {
        StringBuilder out = new StringBuilder();
        int run = 0;
        int skipped = 0;
        for (String line : crash.split("\n")) {
            String trimmed = line.trim();
            boolean framework = trimmed.startsWith("at ") && isFrameworkFrame(trimmed);
            if (framework && ++run > 2) {
                skipped++;
                continue;
            }
            if (!framework) {
                run = 0;
            }
            if (skipped > 0) {
                out.append("\t... ").append(skipped).append(" Android lines\n");
                skipped = 0;
            }
            out.append(line).append('\n');
        }
        if (skipped > 0) {
            out.append("\t... ").append(skipped).append(" Android lines\n");
        }
        return out.toString().trim();
    }

    private static boolean isFrameworkFrame(String frame) {
        String location = frame.substring("at ".length());
        for (String prefix : FRAMEWORK_PACKAGES) {
            if (location.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The first line of a crash, e.g. "java.lang.NullPointerException: ...", for showing.
     */
    @NonNull
    public static String headline(@NonNull String crash) {
        for (String line : crash.split("\n")) {
            if (isException(line)) {
                return line.trim();
            }
        }
        return firstLine(crash);
    }

    private static String join(List<Entry> entries, int start, String tag) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < entries.size() && i <= start + MAX_FOLLOWING_LINES * 2; i++) {
            Entry entry = entries.get(i);
            if (!tag.equals(entry.tag)) {
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(entry.body);
        }
        return out.toString();
    }

    private static boolean isException(String text) {
        Matcher matcher = EXCEPTION.matcher(text);
        return matcher.find();
    }

    private static String firstLine(String text) {
        String trimmed = text.trim();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).trim();
    }

    /**
     * Keeps the start of a long trace, where the app's own code usually is, and its last "Caused
     * by", which is the real cause.
     */
    private static String limit(String crash) {
        String trimmed = compact(crash.trim());
        if (trimmed.length() <= MAX_CHARS) {
            return trimmed;
        }
        int cause = trimmed.lastIndexOf("Caused by");
        if (cause > MAX_CHARS / 2) {
            String tail = trimmed.substring(cause);
            if (tail.length() > MAX_CHARS / 3) {
                tail = tail.substring(0, MAX_CHARS / 3);
            }
            return trimmed.substring(0, MAX_CHARS - tail.length() - 6) + "\n…\n" + tail;
        }
        return trimmed.substring(0, MAX_CHARS) + "\n…";
    }

    private static final class Entry {
        @Nullable
        final String type;
        @Nullable
        final String tag;
        final String body;

        Entry(String raw) {
            Matcher matcher = raw.contains("\n") ? null : LOG_LINE.matcher(raw);
            if (matcher != null && matcher.matches()) {
                type = matcher.group(2);
                tag = matcher.group(3).trim();
                body = matcher.group(4);
            } else {
                type = null;
                tag = null;
                body = raw;
            }
        }
    }
}
