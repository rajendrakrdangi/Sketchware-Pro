package pro.sketchware.ai;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;

import pro.sketchware.R;
import pro.sketchware.ai.api.AiException;
import pro.sketchware.ai.generator.AiSpecException;

/**
 * Turns a failed AI request into a message for the user. Decides by HTTP status and cause where
 * they are known, and only falls back to the provider's wording where they aren't.
 */
public final class AiErrors {

    private AiErrors() {
    }

    @NonNull
    public static Kind classify(@NonNull Exception e) {
        if (e instanceof AiSpecException) {
            return Kind.UNUSABLE_ANSWER;
        }
        Throwable cause = e.getCause();
        if (cause instanceof UnknownHostException || cause instanceof ConnectException) {
            return Kind.CONNECTION;
        }
        if (cause instanceof InterruptedIOException) {
            return Kind.TIMEOUT;
        }
        String raw = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        int status = e instanceof AiException ai ? ai.getStatusCode() : AiException.NO_STATUS;
        if (status == 401 || status == 403) {
            return Kind.AUTH;
        }
        if (status == 429 || raw.contains("quota") || raw.contains("rate limit")) {
            return Kind.RATE_LIMIT;
        }
        if (status == 404 || raw.contains("model not found") || raw.contains("does not exist")) {
            return Kind.MODEL_NOT_FOUND;
        }
        if ((status == 400 || status == 413) && (raw.contains("context") || raw.contains("token")
                || raw.contains("too long") || raw.contains("too large"))) {
            return Kind.CONTEXT_TOO_LONG;
        }
        if (status >= 500) {
            return Kind.SERVER;
        }
        if (raw.contains("api key") || raw.contains("unauthorized")) {
            return Kind.AUTH;
        }
        if (raw.contains("timed out") || raw.contains("timeout")) {
            return Kind.TIMEOUT;
        }
        return Kind.OTHER;
    }

    @NonNull
    public static String describe(@NonNull Context context, @NonNull Exception e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        return switch (classify(e)) {
            case UNUSABLE_ANSWER -> context.getString(R.string.ai_error_unusable_answer, raw);
            case CONNECTION -> context.getString(R.string.ai_error_connection_lost);
            case TIMEOUT -> context.getString(R.string.ai_error_timeout);
            case AUTH -> context.getString(R.string.ai_error_auth);
            case RATE_LIMIT -> context.getString(R.string.ai_error_rate_limit);
            case MODEL_NOT_FOUND -> context.getString(R.string.ai_error_model_not_found, raw);
            case CONTEXT_TOO_LONG -> context.getString(R.string.ai_error_context_too_long);
            case SERVER -> context.getString(R.string.ai_error_server, raw);
            case OTHER -> context.getString(R.string.ai_error_general, raw);
        };
    }

    public enum Kind {
        UNUSABLE_ANSWER,
        CONNECTION,
        TIMEOUT,
        AUTH,
        RATE_LIMIT,
        MODEL_NOT_FOUND,
        CONTEXT_TOO_LONG,
        SERVER,
        OTHER;

        /**
         * @return Whether opening the model settings is how the user fixes it.
         */
        public boolean isConfigurationIssue() {
            return this == AUTH || this == MODEL_NOT_FOUND;
        }
    }
}
