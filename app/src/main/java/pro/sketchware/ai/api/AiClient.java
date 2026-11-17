package pro.sketchware.ai.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * A blocking client for one AI provider. All methods do network I/O, so never call them on the
 * main thread.
 */
public interface AiClient {

    /**
     * @return The IDs of the models the configured key can use, sorted alphabetically.
     */
    @NonNull
    List<String> fetchModels() throws AiException;

    /**
     * Sends the conversation to the configured model.
     *
     * @param systemPrompt Instructions for the model, may be empty.
     * @param messages     The conversation so far, ending with a user message.
     * @return The text the model replied with.
     */
    @NonNull
    String complete(@NonNull String systemPrompt, @NonNull List<AiMessage> messages) throws AiException;

    /**
     * Like {@link #complete(String, List)}, but reports the answer while it's being written, for
     * providers that can stream. Ones that can't, or a server that refuses to, give the same
     * answer without progress.
     *
     * @param listener Called on the calling thread with the whole answer received so far.
     */
    @NonNull
    default String complete(@NonNull String systemPrompt, @NonNull List<AiMessage> messages,
                            @Nullable StreamListener listener) throws AiException {
        return complete(systemPrompt, messages);
    }

    interface StreamListener {
        void onText(@NonNull String textSoFar);

        /**
         * Reasoning models think before they answer and stream that separately; this reports
         * how much thinking has arrived, so a long silence before the answer isn't mistaken for
         * a hang.
         */
        default void onThinking(int charactersSoFar) {
        }
    }

    /**
     * Sends a minimal request to verify that key, base URL and model work together.
     *
     * @throws AiException with the provider's reason if they don't.
     */
    default void testConnection() throws AiException {
        complete("", List.of(AiMessage.user("Reply with the single word OK.")));
    }
}
