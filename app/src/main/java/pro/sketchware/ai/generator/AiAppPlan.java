package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * RKD AI's plan for a new app, made before anything is built: what the app is, which features it
 * gets and why, its screens and how they look and respond, and the questions whose answers really
 * change what gets built.
 * <p>
 * The plan is shown to the user, who answers the questions with taps, and then becomes part of
 * the request that builds the app, so the build follows what was agreed.
 */
public class AiAppPlan {

    private static final int MAX_QUESTIONS = 4;
    private static final int MAX_OPTIONS = 6;

    public String understanding;
    public List<Feature> features = new ArrayList<>();
    public List<Screen> screens = new ArrayList<>();
    /**
     * How the user moves through the app and how it responds, e.g. while playing a quiz.
     */
    public String flow;
    public String design;
    /**
     * What the app stores and keeps between launches.
     */
    public String data;
    public List<Question> questions = new ArrayList<>();

    public static class Feature {
        public String name;
        public String why;
    }

    public static class Screen {
        public String name;
        public String purpose;
        public String ui;
    }

    public static class Question {
        public String question;
        public List<String> options = new ArrayList<>();
        /**
         * Whether more than one option may be chosen.
         */
        public boolean multiple;
        /**
         * Whether the user may type an answer of their own.
         */
        public boolean allowCustom = true;
    }

    /**
     * @return The plan in a model's answer, or null if there's none to be read.
     */
    @Nullable
    public static AiAppPlan parse(@NonNull String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        AiAppPlan plan;
        try {
            plan = new Gson().fromJson(answer.substring(start, end + 1), AiAppPlan.class);
        } catch (JsonParseException e) {
            return null;
        }
        if (plan == null || plan.understanding == null || plan.understanding.trim().isEmpty()) {
            return null;
        }
        plan.features = clean(plan.features);
        plan.screens = clean(plan.screens);
        List<Question> questions = new ArrayList<>();
        for (Question question : clean(plan.questions)) {
            if (question.question == null || question.question.trim().isEmpty() || questions.size() >= MAX_QUESTIONS) {
                continue;
            }
            List<String> options = new ArrayList<>();
            for (String option : clean(question.options)) {
                if (!option.trim().isEmpty() && options.size() < MAX_OPTIONS) {
                    options.add(option.trim());
                }
            }
            question.options = options;
            if (options.isEmpty()) {
                question.allowCustom = true;
            }
            questions.add(question);
        }
        plan.questions = questions;
        return plan;
    }

    private static <T> List<T> clean(@Nullable List<T> list) {
        List<T> cleaned = new ArrayList<>();
        if (list != null) {
            for (T item : list) {
                if (item != null) {
                    cleaned.add(item);
                }
            }
        }
        return cleaned;
    }

    public boolean hasQuestions() {
        return !questions.isEmpty();
    }

    @NonNull
    public String toJson() {
        return new Gson().toJson(this);
    }

    @Nullable
    public static AiAppPlan fromJson(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return new Gson().fromJson(json, AiAppPlan.class);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /**
     * @return The plan as the user reads it in the chat, in Markdown.
     */
    @NonNull
    public String toMarkdown() {
        StringBuilder out = new StringBuilder("**My plan for your app**\n").append(understanding.trim());
        if (!features.isEmpty()) {
            out.append("\n\n**Features**");
            for (Feature feature : features) {
                out.append("\n- **").append(text(feature.name)).append("**");
                if (feature.why != null && !feature.why.trim().isEmpty()) {
                    out.append(": ").append(feature.why.trim());
                }
            }
        }
        if (!screens.isEmpty()) {
            out.append("\n\n**Screens**");
            for (Screen screen : screens) {
                out.append("\n- **").append(text(screen.name)).append("**");
                String purpose = text(screen.purpose);
                String ui = text(screen.ui);
                if (!purpose.isEmpty()) {
                    out.append(": ").append(purpose);
                }
                if (!ui.isEmpty()) {
                    String separator = purpose.isEmpty() ? ": " : purpose.matches(".*[.!?]$") ? " " : ". ";
                    out.append(separator).append(ui);
                }
            }
        }
        appendSection(out, "How it works", flow);
        appendSection(out, "Look and feel", design);
        appendSection(out, "What it saves", data);
        return out.toString();
    }

    /**
     * @return The plan as part of the request that builds the app.
     */
    @NonNull
    public String toBuildInstructions() {
        return toMarkdown().replace("**My plan for your app**", "Plan agreed with the user:");
    }

    private static void appendSection(StringBuilder out, String title, @Nullable String body) {
        if (body != null && !body.trim().isEmpty()) {
            out.append("\n\n**").append(title).append("**\n").append(body.trim());
        }
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
