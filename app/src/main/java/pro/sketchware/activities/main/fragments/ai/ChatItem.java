package pro.sketchware.activities.main.fragments.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.ai.generator.AiProjectGenerator;
import pro.sketchware.ai.generator.AiProjectSpec;
import pro.sketchware.ai.generator.GeneratedFile;

/**
 * One row of the AI chat. Rows are immutable: a change replaces the row with a new one that has
 * the same {@link #getId()}.
 */
public final class ChatItem {

    private final long id;
    private final Type type;
    /**
     * The user's prompt, the working model's name, or the error message, depending on the type.
     */
    private final String text;
    /**
     * For {@link Type#ERROR}: the prompt that failed, to offer trying it again.
     * For {@link Type#USER}: the name of the project the prompt changes, if any.
     */
    private final String detail;
    private final AiProjectGenerator.Stage stage;
    private final String stageDetail;
    private final AiProjectGenerator.Result result;
    private final GeneratedFile file;
    /**
     * For {@link Type#PROGRESS}: when the task started, in {@link android.os.SystemClock#elapsedRealtime()}
     * time, so the elapsed time shown keeps counting across stage changes.
     */
    private final long startedAt;
    /**
     * For {@link Type#PROGRESS}: the steps done so far, oldest first.
     */
    private final List<String> log;
    /**
     * For {@link Type#PROGRESS}: the answer in words received so far, if one is streaming in.
     */
    private final String preview;
    /**
     * For {@link Type#ERROR}: whether the task can continue from its saved steps.
     */
    private final boolean resumable;

    private ChatItem(long id, Type type, String text, String detail, AiProjectGenerator.Stage stage,
                     String stageDetail, AiProjectGenerator.Result result, GeneratedFile file) {
        this(id, type, text, detail, stage, stageDetail, result, file, 0, List.of(), null, false);
    }

    private ChatItem(long id, Type type, String text, String detail, AiProjectGenerator.Stage stage,
                     String stageDetail, AiProjectGenerator.Result result, GeneratedFile file, long startedAt,
                     List<String> log, String preview, boolean resumable) {
        this.resumable = resumable;
        this.startedAt = startedAt;
        this.log = log;
        this.preview = preview;
        this.id = id;
        this.type = type;
        this.text = text;
        this.detail = detail;
        this.stage = stage;
        this.stageDetail = stageDetail;
        this.result = result;
        this.file = file;
    }

    /**
     * @param projectName The project the prompt changes, or null if it asks for a new one.
     */
    @NonNull
    public static ChatItem user(long id, @NonNull String prompt, @Nullable String projectName) {
        return new ChatItem(id, Type.USER, prompt, projectName, null, null, null, null);
    }

    /**
     * A plain message from the AI, e.g. confirming an undo or answering a question.
     */
    @NonNull
    public static ChatItem info(long id, @NonNull String message) {
        return new ChatItem(id, Type.INFO, message, null, null, null, null, null);
    }

    /**
     * @param startedAt When the task started, see {@link #getStartedAt()}.
     */
    @NonNull
    public static ChatItem progress(long id, @NonNull AiProjectGenerator.Stage stage, @NonNull String modelName,
                                    @Nullable String stageDetail, long startedAt) {
        return progress(id, stage, modelName, stageDetail, startedAt, List.of(), null);
    }

    /**
     * @param log     Steps done so far, see {@link #getLog()}.
     * @param preview The answer received so far, see {@link #getPreview()}.
     */
    @NonNull
    public static ChatItem progress(long id, @NonNull AiProjectGenerator.Stage stage, @NonNull String modelName,
                                    @Nullable String stageDetail, long startedAt, @NonNull List<String> log,
                                    @Nullable String preview) {
        return new ChatItem(id, Type.PROGRESS, modelName, null, stage, stageDetail, null, null, startedAt,
                new ArrayList<>(log), preview, false);
    }

    @NonNull
    public static ChatItem result(long id, @NonNull AiProjectGenerator.Result result) {
        return new ChatItem(id, Type.RESULT, null, null, null, null, result, null);
    }

    @NonNull
    public static ChatItem savedResult(long id, @NonNull String scId, @NonNull String appName,
                                       @NonNull String packageName, int screenCount,
                                       boolean isUpdate, @NonNull String reply,
                                       @Nullable String compileErrors, boolean compileChecked,
                                       @Nullable String summary, @NonNull List<String> highlights,
                                       @NonNull List<String> changes,
                                       @NonNull List<String> notes) {
        AiProjectSpec spec = new AiProjectSpec();
        spec.appName = appName;
        spec.packageName = packageName;
        spec.summary = summary;
        spec.highlights = new ArrayList<>(highlights);
        for (int i = 0; i < screenCount; i++) {
            spec.screens.add(new AiProjectSpec.Screen());
        }
        AiProjectGenerator.Result restoredResult = new AiProjectGenerator.Result(scId, isUpdate, spec,
                new ArrayList<>(), new ArrayList<>(), reply.isEmpty() ? null : reply, compileErrors, compileChecked,
                changes, notes);
        return new ChatItem(id, Type.RESULT, null, null, null, null, restoredResult, null);
    }

    /**
     * @return This result with the files it changed, which are saved as separate rows.
     */
    @NonNull
    public ChatItem withFiles(@NonNull List<GeneratedFile> files) {
        AiProjectGenerator.Result old = result;
        if (type != Type.RESULT || old == null) {
            return this;
        }
        AiProjectGenerator.Result restored = new AiProjectGenerator.Result(old.getScId(), old.isUpdate(), old.getSpec(),
                files, old.getNotCarriedOver(), old.isReply() ? old.getReply() : null, old.getCompileErrors(),
                old.isCompileChecked(), old.getChanges(), old.getNotes());
        return new ChatItem(id, type, text, detail, stage, stageDetail, restored, file, startedAt, log, preview, resumable);
    }

    /**
     * The questions of an app plan, answered with taps before the app is built.
     *
     * @param planJson The plan, see {@link pro.sketchware.ai.generator.AiAppPlan#toJson()}.
     * @param prompt   The user's request the plan is for.
     * @param answers  The answers given, or null while they're still open.
     */
    @NonNull
    public static ChatItem questions(long id, @NonNull String planJson, @NonNull String prompt, @Nullable String answers) {
        return new ChatItem(id, Type.QUESTIONS, planJson, prompt, null, answers, null, null);
    }

    /**
     * @return For {@link Type#QUESTIONS}: the plan as JSON.
     */
    @Nullable
    public String getPlanJson() {
        return type == Type.QUESTIONS ? text : null;
    }

    /**
     * @return For {@link Type#QUESTIONS}: the request the plan is for.
     */
    @Nullable
    public String getQuestionsPrompt() {
        return type == Type.QUESTIONS ? detail : null;
    }

    /**
     * @return For {@link Type#QUESTIONS}: the answers given, or null while they're open.
     */
    @Nullable
    public String getAnswers() {
        return type == Type.QUESTIONS ? stageDetail : null;
    }

    /**
     * A step of an AI task that finished, kept in the chat above the step being worked on.
     */
    @NonNull
    public static ChatItem step(long id, @NonNull String text) {
        return new ChatItem(id, Type.STEP, text, null, null, null, null, null);
    }

    /**
     * @param resumable Whether the task's finished steps were saved, so it can continue from
     *                  there instead of starting over.
     */
    @NonNull
    public static ChatItem error(long id, @NonNull String message, @NonNull String failedPrompt, boolean resumable) {
        return new ChatItem(id, Type.ERROR, message, failedPrompt, null, null, null, null, 0, List.of(), null, resumable);
    }

    @NonNull
    public static ChatItem error(long id, @NonNull String message, @NonNull String failedPrompt) {
        return new ChatItem(id, Type.ERROR, message, failedPrompt, null, null, null, null);
    }

    @NonNull
    public static ChatItem file(long id, @NonNull GeneratedFile file) {
        return new ChatItem(id, Type.FILE, null, null, null, null, null, file);
    }

    public long getId() {
        return id;
    }

    @NonNull
    public Type getType() {
        return type;
    }

    @Nullable
    public String getText() {
        return text;
    }

    @Nullable
    public String getFailedPrompt() {
        return type == Type.ERROR ? detail : null;
    }

    @Nullable
    public String getProjectName() {
        return type == Type.USER ? detail : null;
    }

    @Nullable
    public AiProjectGenerator.Stage getStage() {
        return stage;
    }

    @Nullable
    public String getStageDetail() {
        return stageDetail;
    }

    @Nullable
    public AiProjectGenerator.Result getResult() {
        return result;
    }

    @Nullable
    public GeneratedFile getFile() {
        return file;
    }

    public long getStartedAt() {
        return startedAt;
    }

    @NonNull
    public List<String> getLog() {
        return log == null ? List.of() : log;
    }

    @Nullable
    public String getPreview() {
        return preview;
    }

    /**
     * @return For {@link Type#ERROR}: whether the failed task can be resumed from its last step.
     */
    public boolean isResumable() {
        return resumable;
    }

    public enum Type {
        USER,
        PROGRESS,
        RESULT,
        ERROR,
        INFO,
        FILE,
        STEP,
        QUESTIONS
    }
}
