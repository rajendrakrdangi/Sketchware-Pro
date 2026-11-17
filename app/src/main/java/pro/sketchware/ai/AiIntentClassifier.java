package pro.sketchware.ai;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what a chat message asks for, so that a question gets an answer instead of a new or
 * changed project. Runs locally on the wording alone, so it costs no extra request.
 * <p>
 * Only {@link Intent#CREATE_PROJECT} and {@link Intent#EDIT_PROJECT} ever write project files.
 * When in doubt between answering and changing, it answers: an unwanted answer costs a message,
 * an unwanted change costs the user's project.
 */
public final class AiIntentClassifier {

    private static final Pattern IDENTITY = Pattern.compile(
            "\\bwho\\s+(created|made|built|developed|designed|programmed|owns|trained)\\s+(you|u|this\\s+(ai|assistant|bot))\\b"
                    + "|\\bwho\\s+are\\s+(you|u)\\b|\\bwhat('?s|\\s+is)\\s+your\\s+name\\b|\\byour\\s+(creator|developer|maker|owner)\\b"
                    + "|\\b(tumhe|aapko|tujhe|tumko)\\s+kis(ne)?\\s+banaya\\b|\\bkisne\\s+banaya\\b|\\b(tum|aap)\\s+kaun\\s+ho\\b"
                    + "|\\bwho\\s+is\\s+rkd\\b|\\babout\\s+yourself\\b");

    /**
     * Imperative verbs that ask for something to be built or changed, optionally after a polite
     * opener such as "please" or "can you".
     */
    private static final Pattern ACTION = Pattern.compile(
            "^(?:(?:hey|hi|ok|okay|now|so|then|also|and|pls|please|kindly|rkd(?:\\s+ai)?)[,!.\\s]+)*"
                    + "(?:(?:can|could|would|will)\\s+(?:you|u)\\s+(?:please\\s+)?|i\\s+(?:want|need|would\\s+like)\\s+(?:you\\s+)?(?:to\\s+)?"
                    + "|let'?s\\s+|go\\s+ahead\\s+and\\s+)?"
                    + "(create|make|build|generate|develop|design|add|insert|implement|include|put|change|update|modify|edit"
                    + "|fix|repair|correct|resolve|solve|remove|delete|drop|rename|replace|move|set|apply|convert|refactor|improve"
                    + "|redesign|restyle|enable|disable|turn|connect|integrate|attach|append|increase|decrease|translate)\\b");

    /**
     * Hindi/Hinglish requests usually end with the verb: "login screen bana do", "error fix karo".
     */
    private static final Pattern ACTION_HINGLISH = Pattern.compile(
            "\\b(bana|banao|bana\\s*do|banado|add\\s+kar(o|do|\\s+do)?|fix\\s+kar(o|do|\\s+do)?|theek\\s+kar(o|do)?"
                    + "|thik\\s+kar(o|do)?|hata\\s*do|hatao|badal\\s*do|badlo|change\\s+kar(o|do)?|update\\s+kar(o|do)?"
                    + "|remove\\s+kar(o|do)?|delete\\s+kar(o|do)?|laga\\s*do|lagao|daal\\s*do|dalo)\\b");

    /**
     * Asks for an explanation or example, never for project files: "write a function that…",
     * "show me an example of…".
     */
    private static final Pattern EXPLAIN_REQUEST = Pattern.compile(
            "^(?:(?:pls|please|kindly)\\s+)?(?:(?:can|could)\\s+(?:you|u)\\s+)?"
                    + "(explain|describe|define|tell\\s+me|show\\s+me|give\\s+me|write\\s+(?:me\\s+)?(?:a|an|the|some)?\\s*"
                    + "(?:code|snippet|example|function|method|class|program|sample)|teach\\s+me|compare|summari[sz]e|list)\\b");

    private static final Pattern QUESTION_START = Pattern.compile(
            "^(?:(?:hey|hi|ok|okay|so|and|also|pls|please)[,!.\\s]+)*"
                    + "(what|what's|whats|why|how|when|where|which|who|whose|is|are|am|was|were|does|do|did|should|shall"
                    + "|can\\s+i|could\\s+i|may\\s+i|is\\s+it|kya|kaise|kyu|kyun|kyon|kab|kahan|kaun|konsa|kitna)\\b");

    private static final Pattern DEBUG = Pattern.compile(
            // \w* catches class names such as NullPointerException or OutOfMemoryError
            "\\b(\\w*exceptions?|\\w*errors?|crash|crashes|crashing|crashed|npe|stack\\s*trace|stacktrace|bug|bugs"
                    + "|not\\s+working|doesn'?t\\s+work|does\\s+not\\s+work|isn'?t\\s+working|fails?|failed|failing|broken"
                    + "|cannot\\s+resolve|cannot\\s+find\\s+symbol|compile|compiling|build\\s+fail|anr|freez(e|es|ing)|kaam\\s+nahi)\\b");

    private static final Pattern ANALYZE = Pattern.compile(
            "\\b(analy[sz]e|analysis|review|audit|inspect|check|explain|overview|summary|summari[sz]e)\\b.*\\b(project|app|code|screen|activity|logic|layout)\\b"
                    + "|\\b(my|this|the)\\s+(project|app)\\b.*\\b(do|does|contain|have|has|work|works|structure)\\b");

    /**
     * A message without a project attached that describes a whole app, e.g. "a notes app with
     * dark mode", is a request for a new one.
     */
    private static final Pattern APP_DESCRIPTION = Pattern.compile(
            "\\b(app|application|project|game|calculator|tracker|player|browser|launcher|clone|todo|to-do|notes?)\\b");

    private AiIntentClassifier() {
    }

    @NonNull
    public static Intent classify(@NonNull String message, boolean projectAttached) {
        String text = message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return Intent.QUESTION;
        }
        if (IDENTITY.matcher(text).find() && text.length() < 120) {
            return Intent.IDENTITY;
        }
        boolean explain = EXPLAIN_REQUEST.matcher(text).find();
        boolean action = !explain && (ACTION.matcher(text).find() || ACTION_HINGLISH.matcher(text).find());
        boolean question = QUESTION_START.matcher(text).find() || text.endsWith("?");
        boolean debug = DEBUG.matcher(text).find();

        if (action) {
            // "Can you add a login screen?" is a request, not a question
            if (projectAttached) {
                return Intent.EDIT_PROJECT;
            }
            if (APP_DESCRIPTION.matcher(text).find() || !debug) {
                return Intent.CREATE_PROJECT;
            }
            // "Fix this NullPointerException" with no project to fix: explain instead
            return Intent.DEBUG;
        }
        if (debug) {
            return Intent.DEBUG;
        }
        if (projectAttached && ANALYZE.matcher(text).find()) {
            return Intent.ANALYZE;
        }
        if (explain || question) {
            return Intent.QUESTION;
        }
        // A plain statement: with a project attached it describes a change, like it always did;
        // without one it may describe a new app. The project prompt can still answer in words.
        if (projectAttached) {
            return Intent.EDIT_PROJECT;
        }
        return APP_DESCRIPTION.matcher(text).find() ? Intent.CREATE_PROJECT : Intent.QUESTION;
    }

    public enum Intent {
        /** "Who created you?": answered locally. */
        IDENTITY,
        /** General or technical questions, explanations, code examples, documentation. */
        QUESTION,
        /** Explaining an error or crash; the project is only read. */
        DEBUG,
        /** Explaining or reviewing the attached project; the project is only read. */
        ANALYZE,
        /** Creating a new project. */
        CREATE_PROJECT,
        /** Changing the attached project's files. */
        EDIT_PROJECT;

        /**
         * @return Whether handling this intent writes project files.
         */
        public boolean changesFiles() {
            return this == CREATE_PROJECT || this == EDIT_PROJECT;
        }
    }
}
