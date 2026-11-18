package pro.sketchware.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * How freely RKD AI may change an existing project, chosen in the chat.
 */
public enum AiEditPermission {
    /**
     * Changes only what was asked, and shows every change for approval before anything is written.
     * The default.
     */
    ASK("""
            Change ONLY what the request asks for. Every other screen, view, text, color, size, icon, \
            resource, variable and line of code stays exactly as it is, character for character: don't \
            restyle, reword, reorder, rename or "improve" anything the request doesn't name. The user \
            reviews every change before it's applied, so an unasked change will be rejected. If you see \
            other improvements worth making, describe them in "suggestions" instead of making them."""),
    /**
     * Changes what was asked plus what that change needs to work well; applied without asking.
     */
    PARTIAL("""
            Change what the request asks for, plus only what that change needs to work well, e.g. a \
            variable, an icon, a text, or a fix in code the change touches. Leave unrelated screens and \
            features exactly as they are, without restyling or rewording them. Describe other \
            improvements worth making in "suggestions" instead of making them."""),
    /**
     * Makes the change and may improve the rest of the app too; applied without asking.
     */
    FULL("""
            Make the requested change. You may also improve other parts of the app where it clearly \
            helps, e.g. its design, a missing feature or a bug, as long as everything that works keeps \
            working. Say what you improved besides the request in "summary".""");

    private final String rule;

    AiEditPermission(String rule) {
        this.rule = rule;
    }

    /**
     * @return The instruction for the model about how much it may change.
     */
    @NonNull
    public String rule() {
        return rule;
    }

    /**
     * @return Whether changes wait for the user's approval before they're applied.
     */
    public boolean needsApproval() {
        return this == ASK;
    }

    @NonNull
    public static AiEditPermission fromName(@Nullable String name) {
        if (name != null) {
            for (AiEditPermission permission : values()) {
                if (permission.name().equals(name)) {
                    return permission;
                }
            }
        }
        return ASK;
    }
}
