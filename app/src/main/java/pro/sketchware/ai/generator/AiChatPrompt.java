package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import pro.sketchware.ai.AiIdentity;
import pro.sketchware.ai.AiIntentClassifier;

/**
 * Instructions for messages that are answered in words: questions, explanations, debugging and
 * project analysis. In this mode nothing is written to the project, and the prompt says so, so
 * the model never claims to have changed anything.
 */
public final class AiChatPrompt {

    /**
     * Projects are sent as JSON; this keeps them within the context window of small models.
     */
    private static final int MAX_PROJECT_CHARS = 60_000;
    private static final int MAX_ERRORS_CHARS = 8_000;

    private static final String RULES = """
            # How to answer
            - Answer the user's message directly, in the language they wrote in. Be accurate and concise; \
            use short paragraphs and bullet lists. Put code in fenced code blocks with a language tag \
            (```java, ```xml).
            - In this mode you do NOT create or change any project files. Never say that you created, \
            updated, fixed or saved anything. If the user wants a change made, show what to change and \
            tell them they can ask you to apply it (for example "Add a login screen to my project") \
            with their project attached.
            - When explaining a problem, say what causes it, why, and how to fix it.
            - Only rely on facts. If you are not sure, or the information given isn't enough, say so \
            and ask for what you need (the full error message, the code involved).

            # Sketchware Pro facts
            - A project has screens (Activities). Each screen has an XML layout and logic made of \
            visual blocks that Sketchware turns into Java. Logic lives in events (onCreate, view \
            events such as onClick, Activity events such as onBackPressed) and More Blocks, which become \
            methods named with an underscore prefix, e.g. _loadNotes(final String _filter).
            - Every view with an ID becomes a ready-to-use field of the same name; no findViewById.
            - Components (Intent, SharedPreferences, Calendar, Vibrator, Dialog, Timer and others) \
            become fields. Variables: boolean, number (double), String, Map. Lists: ListNumber, \
            ListString, ListMap.
            - The floating action button is the screen option "fab" and its field is _fab.
            - Custom Java, libraries, permissions and the manifest can be edited in the project's \
            managers (Java manager, Library manager, Permission manager, AndroidManifest manager).
            - Don't invent components, properties, events or blocks that aren't listed in the project \
            data or these facts. If you are unsure whether Sketchware supports something, say so.
            """;

    private AiChatPrompt() {
    }

    /**
     * @param projectJson   The attached project in the project JSON format, or null.
     * @param unsupported   Parts of the project the JSON can't express.
     * @param compileErrors The project's real compile errors, or null if it compiles or wasn't
     *                      compiled.
     */
    @NonNull
    public static String system(@NonNull AiIntentClassifier.Intent intent, @Nullable String projectName,
                                @Nullable String projectJson, @NonNull List<String> unsupported,
                                @Nullable String compileErrors, boolean compiled) {
        return system(intent, projectName, projectJson, unsupported, compileErrors, compiled, null);
    }

    /**
     * @param catalog What this install offers, see {@link AiSketchwareCatalog#promptSection()},
     *                or null when the question doesn't need it.
     */
    @NonNull
    public static String system(@NonNull AiIntentClassifier.Intent intent, @Nullable String projectName,
                                @Nullable String projectJson, @NonNull List<String> unsupported,
                                @Nullable String compileErrors, boolean compiled, @Nullable String catalog) {
        StringBuilder prompt = new StringBuilder(AiIdentity.SYSTEM_PREAMBLE)
                .append("You help developers build, debug and understand Android apps, especially Sketchware Pro projects.\n\n")
                .append(RULES);
        if (catalog != null) {
            prompt.append('\n').append(catalog).append('\n')
                    .append("When you explain how to do something in Sketchware Pro, use the components, events and ")
                    .append("settings above, as the user would in the editor.\n");
        }
        switch (intent) {
            case DEBUG -> prompt.append("\n# Task\nThe user needs help with a problem. Find the most likely cause, ")
                    .append("explain why it happens, and give the exact fix. Don't change files.\n");
            case ANALYZE -> prompt.append("\n# Task\nThe user asks about their project. Base your answer on the ")
                    .append("project data below only. Point out real problems you find, with where they are.\n");
            default -> {
            }
        }
        if (projectJson != null) {
            prompt.append("\n# Attached project: ").append(projectName == null ? "" : projectName).append('\n')
                    .append("Its screens, layouts and logic in JSON, as Sketchware stores them")
                    .append(projectJson.length() > MAX_PROJECT_CHARS ? " (cut off, it is large)" : "")
                    .append(":\n")
                    .append(truncate(projectJson, MAX_PROJECT_CHARS)).append('\n');
            if (!unsupported.isEmpty()) {
                prompt.append("Parts not included above: ").append(String.join(", ", unsupported)).append('\n');
            }
            if (compiled) {
                prompt.append(compileErrors == null
                        ? "\nThe project was just compiled with Sketchware's compiler: no compile errors.\n"
                        : "\nThe project was just compiled with Sketchware's compiler. Real compile errors:\n"
                        + truncate(compileErrors, MAX_ERRORS_CHARS) + '\n');
            }
        }
        return prompt.toString();
    }

    /**
     * The catalog is large, so general questions ("what is a RecyclerView?") are sent without it;
     * anything about a project or about Sketchware's own features gets it.
     */
    public static boolean needsCatalog(@NonNull AiIntentClassifier.Intent intent, @NonNull String message, boolean hasProject) {
        if (hasProject || intent == AiIntentClassifier.Intent.DEBUG || intent == AiIntentClassifier.Intent.ANALYZE) {
            return true;
        }
        return SKETCHWARE_TOPIC.matcher(message).find();
    }

    private static final java.util.regex.Pattern SKETCHWARE_TOPIC = java.util.regex.Pattern.compile(
            "(?i)\\b(sketchware|component|widget|block|more\\s*block|event|admob|ads?|banner|interstitial|rewarded"
                    + "|menu|toolbar|options|firebase|sharedpreferences|intent|logic\\s+editor|view\\s+editor)\\b");

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…";
    }
}
