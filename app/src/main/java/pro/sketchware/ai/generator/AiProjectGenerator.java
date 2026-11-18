package pro.sketchware.ai.generator;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mod.jbk.util.LogUtil;
import pro.sketchware.R;
import pro.sketchware.ai.AiEditPermission;
import pro.sketchware.ai.AiIntentClassifier;
import pro.sketchware.ai.api.AiClient;
import pro.sketchware.ai.api.AiClientFactory;
import pro.sketchware.ai.api.AiException;
import pro.sketchware.ai.api.AiMessage;
import pro.sketchware.ai.config.AiConfiguration;

/**
 * Generates a whole Sketchware project from a description: asks the model, turns its answer into
 * a project, and lets the model correct itself when the answer can't be used.
 * <p>
 * {@link #generate(String, ProgressListener)} blocks for as long as the model takes, so run it on
 * a background thread.
 */
public class AiProjectGenerator {

    /**
     * How many times the model gets to correct an unusable answer before giving up.
     */
    private static final int MAX_REPAIR_ATTEMPTS = 3;
    private static final String TAG = "AiProjectGenerator";

    /**
     * How often, at most, a streaming answer updates the progress shown.
     */
    private static final long PROGRESS_INTERVAL_MS = 250;

    private final Context context;
    private final AiClient client;
    /**
     * What this install offers, read once per request and shared by prompt, parser, builder and
     * reader, so all of them agree on what exists.
     */
    private final AiSketchwareCatalog catalog;
    private final AiResponseParser parser;
    private final AiProjectBuilder builder;
    private final AiProjectReader reader;
    private final ProjectCompileChecker compileChecker;
    private final Gson gson = new Gson();

    public AiProjectGenerator(@NonNull Context context, @NonNull AiConfiguration configuration) {
        this.context = context.getApplicationContext();
        client = AiClientFactory.create(configuration);
        catalog = AiSketchwareCatalog.load();
        parser = new AiResponseParser(catalog);
        builder = new AiProjectBuilder(context, catalog);
        reader = new AiProjectReader(context, catalog);
        compileChecker = new ProjectCompileChecker(context);
    }

    /**
     * @param description What the user wants, in their own words.
     * @return The created project.
     * @throws AiException     If the provider couldn't be reached or refused the request.
     * @throws AiSpecException If the model kept answering with something that can't be built.
     */
    @NonNull
    public Result generate(@NonNull String description, @NonNull ProgressListener listener) throws AiException, AiSpecException {
        return generate(description, Collections.emptyList(), listener);
    }

    /**
     * Generates a new project with prior conversation context for intelligent requirement understanding.
     */
    @NonNull
    public Result generate(@NonNull String description, @NonNull List<AiMessage> history, @NonNull ProgressListener listener) throws AiException, AiSpecException {
        List<AiMessage> conversation = new ArrayList<>(history);
        conversation.add(AiMessage.user(description));
        AiTaskCheckpoint state = new AiTaskCheckpoint();
        state.prompt = description;
        state.setConversation(conversation);
        return run(state, listener);
    }

    /**
     * Changes an existing project as described. The project is backed up first, see
     * {@link AiProjectBackup}.
     *
     * @param request What to change, in the user's own words.
     * @return The changed project.
     * @throws AiException     If the provider couldn't be reached or refused the request.
     * @throws AiSpecException If the project can't be read, or the model kept answering with
     *                         something that can't be built. The project is unchanged then.
     */
    @NonNull
    public Result edit(@NonNull String sc_id, @NonNull String request, @NonNull ProgressListener listener) throws AiException, AiSpecException {
        return edit(sc_id, request, Collections.emptyList(), listener);
    }

    /**
     * Changes an existing project with prior conversation context.
     */
    @NonNull
    public Result edit(@NonNull String sc_id, @NonNull String request, @NonNull List<AiMessage> history, @NonNull ProgressListener listener) throws AiException, AiSpecException {
        return edit(sc_id, request, history, listener, AiEditPermission.FULL);
    }

    /**
     * Changes an existing project as freely as the user allows.
     *
     * @param permission How much besides the request may change; with
     *                   {@link AiEditPermission#ASK} the changes wait for approval, see
     *                   {@link Result#isApproval()}.
     */
    @NonNull
    public Result edit(@NonNull String sc_id, @NonNull String request, @NonNull List<AiMessage> history,
                       @NonNull ProgressListener listener, @NonNull AiEditPermission permission) throws AiException, AiSpecException {
        listener.onProgress(Stage.READING, null);
        AiProjectReader.Result current = reader.read(sc_id);
        List<AiMessage> conversation = new ArrayList<>(history);
        // Without Sketchware's indentation a big app fits the model; the user's Java files go as
        // outlines, read when the model asks for them
        AiSpecPatch.compactLayouts(current.getSpec());
        conversation.add(AiMessage.user(AiProjectPrompt.edit(AiJavaClasses.promptView(gson.toJson(current.getSpec())), request, permission)));
        AiTaskCheckpoint state = new AiTaskCheckpoint();
        state.permission = permission.name();
        state.patch = true;
        state.prompt = request;
        state.target = sc_id;
        state.update = true;
        // The project as it is now, for the real difference afterwards
        state.beforeSpecJson = gson.toJson(current.getSpec());
        state.unsupported = new ArrayList<>(current.getUnsupported());
        state.setConversation(conversation);
        return run(state, listener);
    }

    /**
     * Plans a new app before building it: what it is, its features and why, its screens and how
     * they look and respond, and the questions that really change the result.
     *
     * @return The plan, or null if the model's answer had no usable plan, in which case the app is
     * built from the request alone.
     */
    @Nullable
    public AiAppPlan plan(@NonNull String request, @NonNull List<AiMessage> history, @NonNull ProgressListener listener) throws AiException {
        listener.onProgress(Stage.PLANNING, null);
        List<AiMessage> conversation = new ArrayList<>(history);
        conversation.add(AiMessage.user(request));
        Throttle throttle = new Throttle();
        String answer = client.complete(AiPlanPrompt.system(catalog), conversation, new AiClient.StreamListener() {
            @Override
            public void onText(@NonNull String text) {
                if (throttle.ready()) {
                    listener.onSteps(planSteps(text), text.length());
                }
            }

            @Override
            public void onThinking(int charactersSoFar) {
                if (throttle.ready()) {
                    listener.onThinking(charactersSoFar);
                }
            }
        });
        return AiAppPlan.parse(answer);
    }

    /**
     * What the planning answer has covered so far, for showing while it arrives.
     */
    private List<String> planSteps(String partial) {
        List<String> steps = new ArrayList<>();
        String[][] sections = {
                {"\"understanding\"", context.getString(R.string.ai_plan_step_understanding)},
                {"\"features\"", context.getString(R.string.ai_plan_step_features)},
                {"\"screens\"", context.getString(R.string.ai_plan_step_screens)},
                {"\"flow\"", context.getString(R.string.ai_plan_step_flow)},
                {"\"design\"", context.getString(R.string.ai_plan_step_design)},
                {"\"questions\"", context.getString(R.string.ai_plan_step_questions)}};
        for (String[] section : sections) {
            if (partial.contains(section[0])) {
                steps.add(section[1]);
            }
        }
        return steps;
    }

    /**
     * Builds a new app the way it was planned and, if there were questions, answered.
     */
    @NonNull
    public Result generateFromPlan(@NonNull String request, @NonNull AiAppPlan plan, @Nullable String answers,
                                   @NonNull List<AiMessage> history, @NonNull ProgressListener listener) throws AiException, AiSpecException {
        List<AiMessage> conversation = new ArrayList<>(history);
        conversation.add(AiMessage.user(AiPlanPrompt.buildRequest(request, plan, answers)));
        AiTaskCheckpoint state = new AiTaskCheckpoint();
        state.prompt = request;
        state.planned = true;
        state.setConversation(conversation);
        // The agreed plan is kept, so an interruption doesn't plan again
        listener.onCheckpoint(state.copy(), null);
        return run(state, listener);
    }

    /**
     * Continues a task from its last checkpoint, e.g. after the app was closed or the connection
     * dropped. A saved answer is built and compiled without asking the model again; the model is
     * only asked for what it hadn't answered yet.
     */
    @NonNull
    public Result resume(@NonNull AiTaskCheckpoint checkpoint, @NonNull ProgressListener listener) throws AiException, AiSpecException {
        if (checkpoint.hasAnswer()) {
            listener.onCheckpoint(checkpoint.copy(), context.getString(R.string.ai_step_resumed_saved));
        } else {
            listener.onCheckpoint(checkpoint.copy(), context.getString(R.string.ai_step_resumed_request));
        }
        return run(checkpoint, listener);
    }

    /**
     * Answers a message in words without writing anything: a question, a request to explain an
     * error, or one about the attached project, which is then read and, for errors, compiled to
     * give the model the real compiler output.
     *
     * @param sc_id The attached project, or null.
     */
    @NonNull
    public Result answer(@NonNull AiIntentClassifier.Intent intent, @Nullable String sc_id, @Nullable String projectName,
                         @NonNull String message, @NonNull List<AiMessage> history,
                         @NonNull ProgressListener listener) throws AiException {
        String projectJson = null;
        List<String> unsupported = List.of();
        String compileErrors = null;
        boolean compiled = false;
        if (sc_id != null) {
            listener.onProgress(Stage.ANALYZING, null);
            try {
                AiProjectReader.Result current = reader.read(sc_id);
                // Compact, with the user's Java files as outlines, so a big app fits
                AiSpecPatch.compactLayouts(current.getSpec());
                projectJson = AiJavaClasses.promptView(gson.toJson(current.getSpec()));
                unsupported = current.getUnsupported();
            } catch (AiSpecException e) {
                // The question can still be answered, just without the project
                LogUtil.e(TAG, "Couldn't read project " + sc_id + " for context", e);
            }
            if (projectJson != null && intent == AiIntentClassifier.Intent.DEBUG) {
                listener.onProgress(Stage.DIAGNOSING, null);
                CompileOutcome outcome = compile(sc_id, listener);
                compiled = outcome.ran;
                compileErrors = outcome.errors;
            }
        }
        listener.onProgress(Stage.ANSWERING, null);
        List<AiMessage> conversation = new ArrayList<>(history);
        conversation.add(AiMessage.user(message));
        String system = AiChatPrompt.system(intent, projectName, projectJson, unsupported, compileErrors, compiled,
                AiChatPrompt.needsCatalog(intent, message, projectJson != null) ? catalog.promptSection() : null);
        Throttle throttle = new Throttle();
        String reply = client.complete(system, conversation, new AiClient.StreamListener() {
            @Override
            public void onText(@NonNull String text) {
                if (throttle.ready()) {
                    listener.onPreview(text);
                }
            }

            @Override
            public void onThinking(int charactersSoFar) {
                if (throttle.ready()) {
                    listener.onThinking(charactersSoFar);
                }
            }
        }).trim();
        if (reply.isEmpty()) {
            throw new AiException("The model sent an empty answer. Try again or pick another model.");
        }
        return Result.reply(reply);
    }

    /**
     * Asks for, builds and compiles the project, fixing problems with the model's help, and
     * reports a {@link ProgressListener#onCheckpoint checkpoint} after every finished step.
     *
     * @param state Where the task stands: a new one, or one to resume.
     */
    private Result run(AiTaskCheckpoint state, ProgressListener listener) throws AiException, AiSpecException {
        String system = AiProjectPrompt.system(catalog);
        List<AiMessage> conversation = state.conversation();
        AiProjectSpec beforeSpec = state.beforeSpecJson == null ? null : gson.fromJson(state.beforeSpecJson, AiProjectSpec.class);
        List<GeneratedFile> before = beforeSpec == null ? List.of() : AiProjectFiles.describe(beforeSpec);
        AiSpecException lastProblem = null;
        Result lastBuilt = null;
        for (int attempt = state.attempt; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            state.attempt = attempt;
            String answer;
            if (state.hasAnswer()) {
                // Saved before the task was interrupted: no new request
                answer = state.answer;
            } else {
                if (attempt == 0) {
                    listener.onProgress(Stage.CONNECTING, null);
                    listener.onProgress(Stage.GENERATING, null);
                } else if (lastBuilt == null) {
                    listener.onProgress(Stage.REPAIRING, null);
                }
                answer = requestAnswer(system, conversation, listener);
                state.answer = answer;
                state.built = false;
                state.setConversation(conversation);
                listener.onCheckpoint(state.copy(), context.getString(R.string.ai_step_answer, estimateTokens(answer)));
            }

            java.util.List<String> wanted = state.patch && beforeSpec != null ? AiSpecPatch.readRequest(answer) : null;
            if (wanted != null && state.reads < MAX_READS) {
                // The model asked to read some of the project's Java files before changing anything
                conversation.add(AiMessage.assistant(answer));
                conversation.add(AiMessage.user(AiProjectPrompt.files(beforeSpec.projectJavaFiles, wanted)));
                state.reads++;
                state.answer = null;
                state.setConversation(conversation);
                listener.onCheckpoint(state.copy(), context.getString(R.string.ai_step_read_files, String.join(", ", wanted)));
                // Reading isn't a failed attempt
                attempt--;
                continue;
            }

            AiProjectSpec spec;
            try {
                listener.onProgress(Stage.BUILDING, null);
                // A change comes as only what changes, applied to the app here, so nothing
                // else has to be retyped by the model, or can drift while it is
                String json = state.patch && answer.indexOf('{') >= 0
                        ? AiSpecPatch.apply(state.baseSpecJson != null ? state.baseSpecJson : state.beforeSpecJson, answer)
                        : answer;
                spec = parser.parse(json, beforeSpec);
                if (spec.isReply()) {
                    if (lastBuilt != null) {
                        // Gave up fixing the compile errors: what was built is all there is
                        return lastBuilt;
                    }
                    return Result.reply(spec.reply.trim());
                }
                if (!state.built && state.update && !state.approved
                        && state.permission != null && AiEditPermission.fromName(state.permission).needsApproval()) {
                    // Nothing is written until the user has seen and approved every change; the
                    // answer is saved, so approving builds it without asking the model again
                    List<GeneratedFile> proposed = AiProjectFiles.compare(before, AiProjectFiles.describe(spec));
                    if (proposed.isEmpty()) {
                        // Nothing would change: an answer in words, not something to approve
                        String summary = spec.summary == null || spec.summary.isBlank()
                                ? context.getString(R.string.ai_approval_nothing) : spec.summary.trim();
                        return Result.reply(summary);
                    }
                    listener.onCheckpoint(state.copy(), context.getString(R.string.ai_step_approval));
                    return Result.approval(state.target, spec, proposed, AiChangeSummary.describe(beforeSpec, spec));
                }
                if (!state.built) {
                    if (state.target == null) {
                        state.target = builder.build(spec, listener);
                    } else {
                        // Only the first change of an existing project replaces its backup
                        boolean backUp = state.update && !state.backedUp;
                        if (backUp) {
                            state.backedUp = true;
                            listener.onCheckpoint(state.copy(), null);
                        }
                        builder.update(state.target, spec, listener, backUp);
                    }
                    state.built = true;
                    if (state.patch) {
                        // Fixes after this change are changes to the app as built now
                        state.baseSpecJson = gson.toJson(spec);
                    }
                    listener.onCheckpoint(state.copy(), context.getString(state.update || attempt > 0
                            ? R.string.ai_step_built_update : R.string.ai_step_built_new, spec.appName, state.target));
                }
            } catch (AiSpecException e) {
                lastProblem = e;
                conversation.add(AiMessage.assistant(answer));
                conversation.add(AiMessage.user(state.patch ? AiProjectPrompt.repairChanges(e.getMessage())
                        : AiProjectPrompt.repair(e.getMessage())));
                state.answer = null;
                state.built = false;
                state.attempt = attempt + 1;
                state.setConversation(conversation);
                listener.onCheckpoint(state.copy(), context.getString(R.string.ai_step_answer_problem, shorten(e.getMessage())));
                continue;
            }

            listener.onProgress(Stage.COMPILING, null);
            CompileOutcome outcome = compile(state.target, listener);
            String compileErrors = outcome.errors;
            List<GeneratedFile> after = AiProjectFiles.describe(spec);
            List<GeneratedFile> files = before.isEmpty() ? after : AiProjectFiles.compare(before, after);
            List<String> notes = new ArrayList<>(builder.getNotes());
            for (String suggestion : spec.suggestions) {
                notes.add(context.getString(R.string.ai_report_suggestion, suggestion));
            }
            lastBuilt = new Result(state.target, state.update, spec, files, state.unsupported, null, compileErrors, outcome.ran,
                    AiChangeSummary.describe(beforeSpec, spec), notes);
            if (compileErrors == null) {
                return lastBuilt;
            }
            if (attempt < MAX_REPAIR_ATTEMPTS) {
                int count = CompileErrorReport.countErrors(compileErrors);
                String firstError = firstLine(compileErrors);
                listener.onProgress(Stage.FIXING_COMPILE_ERRORS, count <= 0 ? null
                        : context.getResources().getQuantityString(R.plurals.ai_stage_fixing_compile_errors, count, count)
                        + (firstError == null ? "" : "\n" + firstError));
                conversation.add(AiMessage.assistant(answer));
                conversation.add(AiMessage.user(state.patch ? AiProjectPrompt.repairCompileErrorsWithChanges(compileErrors)
                        : AiProjectPrompt.repairCompileErrors(compileErrors)));
                state.answer = null;
                state.built = false;
                state.attempt = attempt + 1;
                state.setConversation(conversation);
                listener.onCheckpoint(state.copy(), context.getResources().getQuantityString(
                        R.plurals.ai_step_compile_errors, Math.max(count, 1), Math.max(count, 1)));
            }
        }
        if (lastBuilt != null) {
            return lastBuilt;
        }
        throw lastProblem;
    }

    private String requestAnswer(String system, List<AiMessage> conversation, ProgressListener listener) throws AiException {
        Throttle throttle = new Throttle();
        return client.complete(system, conversation, new AiClient.StreamListener() {
            @Override
            public void onText(@NonNull String text) {
                if (!throttle.ready()) {
                    return;
                }
                if (AiProgressNarrator.isReply(text)) {
                    listener.onPreview(AiProgressNarrator.replyPreview(text));
                } else {
                    listener.onSteps(AiProgressNarrator.steps(text), text.length());
                }
            }

            @Override
            public void onThinking(int charactersSoFar) {
                if (throttle.ready()) {
                    listener.onThinking(charactersSoFar);
                }
            }
        });
    }

    /**
     * @return About how many tokens a text is, at roughly four characters each, e.g. "3.1k".
     */
    private static String estimateTokens(String text) {
        int tokens = Math.max(1, Math.round(text.length() / 4f));
        return tokens < 1000 ? String.valueOf(tokens) : String.format(java.util.Locale.ROOT, "%.1fk", tokens / 1000f);
    }

    private static String shorten(@Nullable String message) {
        if (message == null) {
            return "";
        }
        String line = message.replaceAll("\\s+", " ").trim();
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    /**
     * Compiles the project to check it. Whether the check actually ran is kept apart from its
     * outcome, so a failure to compile at all is never reported as "no errors".
     */
    @NonNull
    private CompileOutcome compile(String sc_id, ProgressListener listener) {
        Stage stage = Stage.COMPILING;
        try {
            // Sketchware's own build steps, e.g. "Compiling resources with AAPT2…"
            String errors = compileChecker.check(sc_id, (progress, step) -> {
                if (progress != null && !progress.trim().isEmpty()) {
                    listener.onProgress(stage, progress.trim());
                }
            });
            return new CompileOutcome(true, errors);
        } catch (Throwable e) {
            LogUtil.e(TAG, "Couldn't compile project " + sc_id + " to check it", e);
            return new CompileOutcome(false, null);
        }
    }

    /**
     * @return The first error line of a compile report, shortened, for showing what's being fixed.
     */
    @Nullable
    private static String firstLine(String compileErrors) {
        for (String line : compileErrors.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("error") || trimmed.contains(".java:")) {
                return trimmed.length() > 140 ? trimmed.substring(0, 140) + "…" : trimmed;
            }
        }
        return null;
    }

    /**
     * Lets through at most one progress update per {@link #PROGRESS_INTERVAL_MS}.
     */
    private static final class Throttle {
        private long last;

        boolean ready() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - last < PROGRESS_INTERVAL_MS) {
                return false;
            }
            last = now;
            return true;
        }
    }

    private static final class CompileOutcome {
        final boolean ran;
        @Nullable
        final String errors;

        CompileOutcome(boolean ran, @Nullable String errors) {
            this.ran = ran;
            this.errors = errors;
        }
    }

    public enum Stage {
        /** Thinking the app through before building it. */
        PLANNING,
        /** Reading the attached project to answer a question about it. */
        ANALYZING,
        /** Compiling the attached project to collect its real errors. */
        DIAGNOSING,
        /** Waiting for the model's answer in words. */
        ANSWERING,
        /** Reading the project that is to be changed. */
        READING,
        /** Connecting to the model. */
        CONNECTING,
        /** Waiting for the model's answer. */
        GENERATING,
        /** Validating code and self-correcting logic. */
        REPAIRING,
        /** Building UI layout & visual widgets. */
        PARSING_VIEWS,
        /** Setting up components, variables, and data stores. */
        CREATING_COMPONENTS,
        /** Generating visual logic blocks and events. */
        BUILDING_BLOCKS,
        /** Saving project files to storage. */
        SAVING_FILES,
        /** Finalizing project registration. */
        FINALIZING,
        /** Creating project (general stage). */
        BUILDING,
        /** Compiling the project's resources and Java code to check them. */
        COMPILING,
        /** Asking the model to fix the compile errors. */
        FIXING_COMPILE_ERRORS
    }

    /**
     * Called on the thread {@link #generate(String, ProgressListener)} runs on.
     */
    public interface ProgressListener {
        void onProgress(@NonNull Stage stage, @Nullable String detail);

        /**
         * While a project's JSON is streaming in: what the model has written so far, as steps
         * from {@link AiProgressNarrator}, and how much text arrived.
         */
        default void onSteps(@NonNull List<String> steps, int charactersReceived) {
        }

        /**
         * While an answer in words is streaming in: the answer so far.
         */
        default void onPreview(@NonNull String answerSoFar) {
        }

        /**
         * While a reasoning model thinks before answering: how much thinking arrived so far.
         */
        default void onThinking(int charactersSoFar) {
        }

        /**
         * The app was planned; the plan is shown to the user before building starts.
         */
        default void onPlan(@NonNull AiAppPlan plan) {
        }

        /**
         * A step of a project task finished, and this is where the task stands now. Save it, so
         * the task can be {@link #resume resumed} from here.
         *
         * @param step What was done, for showing as a finished step, or null for a quiet save.
         */
        default void onCheckpoint(@NonNull AiTaskCheckpoint checkpoint, @Nullable String step) {
        }

        default void onProgress(@NonNull Stage stage) {
            onProgress(stage, null);
        }
    }

    /**
     * How often a change may ask to read the project's Java files first.
     */
    private static final int MAX_READS = 2;

    public static final class Result {
        private final String sc_id;
        private final boolean updated;
        private final AiProjectSpec spec;
        private final List<GeneratedFile> files;
        private final List<String> notCarriedOver;
        private final String reply;
        private final String compileErrors;
        private final boolean compileChecked;
        private final List<String> changes;
        private final List<String> notes;

        /**
         * @return What changed, from comparing the project before and after, see {@link AiChangeSummary}.
         */
        @NonNull
        public List<String> getChanges() {
            return changes == null ? List.of() : changes;
        }

        /**
         * @return Things the user should know, e.g. that AdMob was turned on with a sample App ID.
         */
        @NonNull
        public List<String> getNotes() {
            return notes == null ? List.of() : notes;
        }

        public Result(String sc_id, boolean updated, AiProjectSpec spec, List<GeneratedFile> files, List<String> notCarriedOver) {
            this(sc_id, updated, spec, files, notCarriedOver, null);
        }

        public Result(String sc_id, boolean updated, AiProjectSpec spec, List<GeneratedFile> files,
                       List<String> notCarriedOver, String reply) {
            this(sc_id, updated, spec, files, notCarriedOver, reply, null);
        }

        public Result(String sc_id, boolean updated, AiProjectSpec spec, List<GeneratedFile> files,
                       List<String> notCarriedOver, String reply, String compileErrors) {
            this(sc_id, updated, spec, files, notCarriedOver, reply, compileErrors, false);
        }

        public Result(String sc_id, boolean updated, AiProjectSpec spec, List<GeneratedFile> files,
                       List<String> notCarriedOver, String reply, String compileErrors, boolean compileChecked) {
            this(sc_id, updated, spec, files, notCarriedOver, reply, compileErrors, compileChecked, List.of(), List.of());
        }

        public Result(String sc_id, boolean updated, AiProjectSpec spec, List<GeneratedFile> files,
                       List<String> notCarriedOver, String reply, String compileErrors, boolean compileChecked,
                       List<String> changes, List<String> notes) {
            this.changes = changes;
            this.notes = notes;
            this.compileChecked = compileChecked;
            this.sc_id = sc_id;
            this.updated = updated;
            this.spec = spec;
            this.files = files;
            this.notCarriedOver = notCarriedOver;
            this.reply = reply;
            this.compileErrors = compileErrors;
        }

        /**
         * @return The errors the project still has when compiled, or null if it compiles.
         */
        @Nullable
        public String getCompileErrors() {
            return compileErrors;
        }

        /**
         * @return Whether the project was really compiled; false if the compiler couldn't run.
         */
        public boolean isCompileChecked() {
            return compileChecked;
        }

        @Nullable
        private AiAppPlan plan;

        /**
         * @return A result that only asks the user the plan's questions; nothing was built.
         */
        @NonNull
        public static Result questions(@NonNull AiAppPlan plan) {
            Result result = new Result("", false, new AiProjectSpec(), List.of(), List.of(), null);
            result.plan = plan;
            return result;
        }

        /**
         * @return Whether the plan's questions wait for the user's answers.
         */
        public boolean isQuestions() {
            return plan != null;
        }

        @Nullable
        public AiAppPlan getPlan() {
            return plan;
        }

        private boolean approval;

        /**
         * @return Changes to an existing project that wait for the user's approval; nothing was
         * written yet.
         */
        @NonNull
        static Result approval(@NonNull String sc_id, @NonNull AiProjectSpec spec, @NonNull List<GeneratedFile> files,
                               @NonNull List<String> changes) {
            Result result = new Result(sc_id, true, spec, files, List.of(), null, null, false, changes, List.of());
            result.approval = true;
            return result;
        }

        /**
         * @return Whether these changes wait for the user's approval before they're applied.
         */
        public boolean isApproval() {
            return approval;
        }

        private static Result reply(String reply) {
            return new Result("", false, new AiProjectSpec(), List.of(), List.of(), reply);
        }

        /**
         * @return Whether the model answered in words, without creating or changing a project.
         */
        public boolean isReply() {
            return reply != null;
        }

        /**
         * @return The model's answer, for {@link #isReply()} results.
         */
        @NonNull
        public String getReply() {
            return reply == null ? "" : reply;
        }

        /**
         * @return Whether an existing project was changed, rather than a new one created.
         */
        public boolean isUpdate() {
            return updated;
        }

        /**
         * @return For updates: what the old project had that the AI couldn't keep.
         */
        @NonNull
        public List<String> getNotCarriedOver() {
            return notCarriedOver;
        }

        @NonNull
        public List<GeneratedFile> getFiles() {
            return files;
        }

        @NonNull
        public String getScId() {
            return sc_id;
        }

        @NonNull
        public AiProjectSpec getSpec() {
            return spec;
        }
    }
}
