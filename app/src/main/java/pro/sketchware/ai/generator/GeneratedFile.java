package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import pro.sketchware.ai.diff.LineDiff;

/**
 * A file the AI created, changed or removed in a project, for showing it to the user together
 * with the real line-by-line difference to what was there before.
 */
public final class GeneratedFile {

    private final String name;
    private final String description;
    /**
     * The file's content now; empty for {@link Operation#DELETED}.
     */
    private final String content;
    /**
     * The file's content before the AI touched it, or null if the file didn't exist.
     */
    @Nullable
    private final String previousContent;
    private final Operation operation;
    private LineDiff.Result diff;

    public GeneratedFile(@NonNull String name, @NonNull String description, @NonNull String content) {
        this(name, description, content, null, Operation.CREATED);
    }

    public GeneratedFile(@NonNull String name, @NonNull String description, @NonNull String content,
                         @Nullable String previousContent, @NonNull Operation operation) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.previousContent = previousContent;
        this.operation = operation;
    }

    @NonNull
    public String getName() {
        return name;
    }

    /**
     * @return What the file is, e.g. "Layout of MainActivity".
     */
    @NonNull
    public String getDescription() {
        return description;
    }

    @NonNull
    public String getContent() {
        return content;
    }

    @Nullable
    public String getPreviousContent() {
        return previousContent;
    }

    @NonNull
    public Operation getOperation() {
        return operation;
    }

    /**
     * @return The line diff between {@link #getPreviousContent()} and {@link #getContent()},
     * computed once on first use.
     */
    @NonNull
    public synchronized LineDiff.Result getDiff() {
        if (diff == null) {
            diff = LineDiff.diff(previousContent, operation == Operation.DELETED ? null : content);
        }
        return diff;
    }

    public int getLineCount() {
        if (content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public enum Operation {
        CREATED,
        MODIFIED,
        DELETED
    }
}
