package pro.sketchware.ai.history;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Makes a short conversation title from its first message, like "Notes app with dark mode"
 * for "Hey, can you please create a notes app with dark mode for me?".
 */
public final class AiChatTitles {

    public static final String FALLBACK = "New chat";
    private static final int MAX_WORDS = 6;
    private static final int MAX_CHARS = 40;
    private static final int MIN_CHARS = 3;

    private static final Pattern FILLER_START = Pattern.compile(
            "^(?:(?:hey|hi|hello|ok|okay|so|rkd(?:\\s+ai)?|please|pls|kindly|now)[,!.\\s]+)*"
                    + "(?:(?:can|could|would|will)\\s+(?:you|u)\\s+(?:please\\s+)?|i\\s+(?:want|need|would\\s+like)\\s+(?:you\\s+)?(?:to\\s+)?"
                    + "|help\\s+me\\s+(?:to\\s+)?|let'?s\\s+)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER_VERB = Pattern.compile(
            "^(?:create|make|build|generate|develop|design|write)\\s+(?:me\\s+)?(?:a|an|the)?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER_END = Pattern.compile(
            "\\s+(?:for\\s+me|please|pls|thanks|thank\\s+you)$", Pattern.CASE_INSENSITIVE);

    private AiChatTitles() {
    }

    @NonNull
    public static String fromMessage(@NonNull String message) {
        String text = message.replaceAll("```[\\s\\S]*?(```|$)", " ").replaceAll("\\s+", " ").trim();
        // First sentence or line only
        int end = indexOfSentenceEnd(text);
        if (end > 0) {
            text = text.substring(0, end);
        }
        text = FILLER_START.matcher(text).replaceFirst("");
        String withoutVerb = FILLER_VERB.matcher(text).replaceFirst("");
        if (withoutVerb.length() >= MIN_CHARS) {
            text = withoutVerb;
        }
        text = text.replaceAll("[?!.,;:]+$", "").trim();
        text = FILLER_END.matcher(text).replaceFirst("").trim();

        String[] words = text.split(" ");
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < words.length && i < MAX_WORDS; i++) {
            if (title.length() + words[i].length() + 1 > MAX_CHARS) {
                break;
            }
            if (title.length() > 0) {
                title.append(' ');
            }
            title.append(words[i]);
        }
        String result = title.toString().replaceAll("[,;:\\-]+$", "").trim();
        if (result.length() < MIN_CHARS) {
            return FALLBACK;
        }
        return result.substring(0, 1).toUpperCase(Locale.ROOT) + result.substring(1);
    }

    private static int indexOfSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '?' || c == '!') && i >= MIN_CHARS
                    && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }
}
