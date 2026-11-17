package pro.sketchware.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Carries a crash from the project editor to the AI tab. The editor saves and closes the project
 * first, so nothing it still holds can overwrite RKD AI's fix; the main screen then takes the crash
 * and opens the AI tab with the project attached.
 */
public final class AiCrashHandoff {

    private static Request pending;

    private AiCrashHandoff() {
    }

    public record Request(@NonNull String sc_id, @NonNull String crash) {
    }

    public static synchronized void offer(@NonNull String sc_id, @NonNull String crash) {
        pending = new Request(sc_id, crash);
    }

    /**
     * @return The waiting crash, once, or null.
     */
    @Nullable
    public static synchronized Request take() {
        Request request = pending;
        pending = null;
        return request;
    }
}
