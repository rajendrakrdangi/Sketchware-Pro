package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;

import pro.sketchware.ai.AiIdentity;

/**
 * Instructions for planning a new app before building it. The model thinks the app through like a
 * product designer, then asks the user only what really changes the result.
 */
public final class AiPlanPrompt {

    private static final String RULES = """
            Right now you are planning an Android app that will then be built in Sketchware Pro. Nothing is \
            built yet. Think the user's idea through like an experienced product designer and Android \
            developer, so the finished app does what the user expects, and more:
            1. What is this app, who uses it, and what problem does it solve?
            2. Which features make it complete and pleasant to use? Go beyond the obvious minimum where it \
            clearly helps, and say for each feature why the user needs it.
            3. Which screens does it need? For each: what it shows, how it looks (layout, cards, colors, \
            spacing, icons) and how the user gets there.
            4. How does it behave while being used? For interactive parts, like answering quiz questions, \
            describe the feedback, progress, results, and what happens with empty input or no data.
            5. What does it store, and what must still be there after the app is closed?
            6. What can't Sketchware Pro build as asked, with what it offers (listed below)? For example \
            video effects and exporting edited videos, real-time multiplayer, or anything needing a server \
            of its own. Be honest: list each in "limits" with what the app does instead (a simpler version \
            that does work), so the user knows before anything is built. Leave "limits" empty when all of \
            it can be built. Never plan something you listed there.
            Plan only what Sketchware Pro can build with what it offers; no servers or accounts unless the \
            user asks for them.

            Then decide whether something important is unclear. Ask only questions whose answers really \
            change what you build: at most 4, each with 2-5 short options to tap. Set "multiple" when \
            several options can apply together, and "allowCustom" to let the user type their own answer. \
            If the request is clear enough, ask nothing ("questions": []) and decide sensibly yourself.

            Write everything for someone who can't code: everyday words, no code or file names. Use the \
            language the user wrote in.

            Answer with one JSON object and nothing else:
            {"understanding": "...", "features": [{"name": "...", "why": "..."}], \
            "screens": [{"name": "...", "purpose": "...", "ui": "..."}], "flow": "...", "design": "...", \
            "data": "...", "limits": [{"what": "...", "instead": "..."}], "questions": [{"question": "...", "options": ["...", "..."], \
            "multiple": false, "allowCustom": true}]}
            """;

    private AiPlanPrompt() {
    }

    @NonNull
    public static String system(@NonNull AiSketchwareCatalog catalog) {
        return AiIdentity.SYSTEM_PREAMBLE + RULES + "\nWhat Sketchware Pro offers:\n" + catalog.capabilitySummary();
    }

    /**
     * @param answers The user's answers to the plan's questions, or null if there were none.
     * @return The request that builds the app the way it was planned.
     */
    @NonNull
    public static String buildRequest(@NonNull String request, @NonNull AiAppPlan plan, @androidx.annotation.Nullable String answers) {
        StringBuilder out = new StringBuilder("Build this app: ").append(request).append("\n\n")
                .append(plan.toBuildInstructions());
        if (answers != null && !answers.trim().isEmpty()) {
            out.append("\n\nThe user's answers to your questions:\n").append(answers.trim());
        }
        out.append("\n\nBuild everything in the plan, following the answers. Make it look good and work fully.");
        return out.toString();
    }
}
