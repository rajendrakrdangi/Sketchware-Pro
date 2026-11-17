package pro.sketchware.ai.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import pro.sketchware.ai.config.AiConfiguration;

/**
 * Client for Anthropic's Messages API, which Claude models are served through.
 */
class AnthropicClient implements AiClient {

    private static final String API_VERSION = "2023-06-01";
    private static final int MODELS_PAGE_SIZE = 1000;

    /**
     * The Messages API requires an output limit. These are tried in order, because a limit above
     * what the chosen model supports is rejected instead of being capped.
     */
    private static final int[] MAX_TOKENS_CANDIDATES = {32000, 16000, 8192, 4096};

    private final AiConfiguration configuration;

    AnthropicClient(@NonNull AiConfiguration configuration) {
        this.configuration = configuration;
    }

    @NonNull
    @Override
    public List<String> fetchModels() throws AiException {
        JsonObject response = AiHttp.get(url("/models?limit=" + MODELS_PAGE_SIZE), headers());
        JsonElement data = response.get("data");
        if (data == null || !data.isJsonArray()) {
            throw new AiException("The server's model list has an unexpected format.");
        }

        List<String> models = new ArrayList<>();
        for (JsonElement model : data.getAsJsonArray()) {
            String id = model.isJsonObject() ? AiHttp.optString(model.getAsJsonObject(), "id") : null;
            if (id != null && !id.isEmpty()) {
                models.add(id);
            }
        }
        Collections.sort(models);
        return models;
    }

    @NonNull
    @Override
    public String complete(@NonNull String systemPrompt, @NonNull List<AiMessage> messages) throws AiException {
        return complete(systemPrompt, messages, null);
    }

    @NonNull
    @Override
    public String complete(@NonNull String systemPrompt, @NonNull List<AiMessage> messages,
                           @Nullable StreamListener listener) throws AiException {
        boolean stream = listener != null;
        JsonArray jsonMessages = new JsonArray();
        for (AiMessage message : messages) {
            JsonObject jsonMessage = new JsonObject();
            jsonMessage.addProperty("role", message.getRole() == AiMessage.Role.USER ? "user" : "assistant");
            jsonMessage.addProperty("content", message.getContent());
            jsonMessages.add(jsonMessage);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", configuration.getModel().trim());
        if (!systemPrompt.isEmpty()) {
            body.addProperty("system", systemPrompt);
        }
        body.add("messages", jsonMessages);

        AiException lastError = null;
        for (int i = 0; i < MAX_TOKENS_CANDIDATES.length; i++) {
            body.addProperty("max_tokens", MAX_TOKENS_CANDIDATES[i]);
            try {
                if (stream) {
                    body.addProperty("stream", true);
                    return streamReply(body, listener);
                }
                body.remove("stream");
                return readReply(AiHttp.post(url("/messages"), headers(), body));
            } catch (AiException e) {
                if (stream && AiHttp.isStreamingUnsupported(e) && !isMaxTokensTooHigh(e)) {
                    stream = false;
                    i--;
                    continue;
                }
                if (!isMaxTokensTooHigh(e)) {
                    throw e;
                }
                lastError = e;
            }
        }
        throw lastError;
    }

    /**
     * Reads a streamed answer, whose text arrives in <code>content_block_delta</code> events.
     */
    private String streamReply(JsonObject body, StreamListener listener) throws AiException {
        StringBuilder text = new StringBuilder();
        String[] stopReason = {null};
        int[] thinking = {0};
        JsonObject whole = AiHttp.postStream(url("/messages"), headers(), body, event -> {
            String type = AiHttp.optString(event, "type");
            if ("error".equals(type)) {
                JsonElement error = event.get("error");
                String message = error != null && error.isJsonObject() ? AiHttp.optString(error.getAsJsonObject(), "message") : null;
                throw new AiException(message == null ? "The model reported an error." : message);
            }
            JsonElement delta = event.get("delta");
            if (delta == null || !delta.isJsonObject()) {
                return;
            }
            JsonObject deltaObject = delta.getAsJsonObject();
            if ("content_block_delta".equals(type) && "thinking_delta".equals(AiHttp.optString(deltaObject, "type"))) {
                String piece = AiHttp.optString(deltaObject, "thinking");
                if (piece != null) {
                    thinking[0] += piece.length();
                    listener.onThinking(thinking[0]);
                }
            } else if ("content_block_delta".equals(type) && "text_delta".equals(AiHttp.optString(deltaObject, "type"))) {
                String piece = AiHttp.optString(deltaObject, "text");
                if (piece != null && !piece.isEmpty()) {
                    text.append(piece);
                    listener.onText(text.toString());
                }
            } else if ("message_delta".equals(type)) {
                String reason = AiHttp.optString(deltaObject, "stop_reason");
                if (reason != null) {
                    stopReason[0] = reason;
                }
            }
        });
        if (whole != null) {
            return readReply(whole);
        }
        if ("max_tokens".equals(stopReason[0])) {
            throw new AiException("The answer was cut off because it hit the model's output limit. Ask for a smaller app, or pick a model with a larger output limit.");
        }
        if (text.toString().trim().isEmpty()) {
            throw new AiException("The model returned an empty answer.");
        }
        return text.toString();
    }

    private boolean isMaxTokensTooHigh(AiException e) {
        return e.getStatusCode() == 400 && e.getMessage() != null && e.getMessage().contains("max_tokens");
    }

    private String readReply(JsonObject response) throws AiException {
        if ("max_tokens".equals(AiHttp.optString(response, "stop_reason"))) {
            throw new AiException("The answer was cut off because it hit the model's output limit. Ask for a smaller app, or pick a model with a larger output limit.");
        }

        StringBuilder text = new StringBuilder();
        JsonElement content = response.get("content");
        if (content != null && content.isJsonArray()) {
            for (JsonElement block : content.getAsJsonArray()) {
                if (!block.isJsonObject()) {
                    continue;
                }
                JsonObject blockObject = block.getAsJsonObject();
                if ("text".equals(AiHttp.optString(blockObject, "type"))) {
                    String blockText = AiHttp.optString(blockObject, "text");
                    if (blockText != null) {
                        text.append(blockText);
                    }
                }
            }
        }
        if (text.toString().trim().isEmpty()) {
            throw new AiException("The model returned an empty answer.");
        }
        return text.toString();
    }

    private String url(String path) {
        return AiHttp.joinUrl(configuration.getBaseUrl(), path);
    }

    private Map<String, String> headers() {
        return Map.of(
                "x-api-key", configuration.getApiKey().trim(),
                "anthropic-version", API_VERSION);
    }
}
