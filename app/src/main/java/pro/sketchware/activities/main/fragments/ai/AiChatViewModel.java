package pro.sketchware.activities.main.fragments.ai;

import android.app.Application;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import pro.sketchware.R;
import pro.sketchware.ai.AiErrors;
import pro.sketchware.ai.AiIdentity;
import pro.sketchware.ai.AiIntentClassifier;
import pro.sketchware.ai.AiTaskRunner;
import pro.sketchware.ai.api.AiMessage;
import pro.sketchware.ai.config.AiConfiguration;
import pro.sketchware.ai.generator.AiProjectBackup;
import pro.sketchware.ai.generator.AiAppPlan;
import pro.sketchware.ai.generator.AiProjectGenerator;
import pro.sketchware.ai.generator.AiTaskCheckpoint;
import pro.sketchware.ai.generator.GeneratedFile;
import pro.sketchware.ai.history.AiChatSession;
import pro.sketchware.ai.history.AiChatTitles;
import pro.sketchware.ai.history.AiHistoryManager;
import pro.sketchware.ai.history.ChatItemDto;

/**
 * Holds the AI chat and runs what it asks for, so both outlive the tab's views.
 * <p>
 * Each message is first classified (see {@link AiIntentClassifier}): questions, debugging and
 * project analysis are answered in words and never touch project files; only requests to create
 * or change something run the project generator. Every created project is attached right away,
 * so follow-up messages refine it.
 * <p>
 * The conversation is saved as soon as a message is sent and again when the answer arrives, on
 * a thread of its own, so saving never waits for the model.
 */
public class AiChatViewModel extends AndroidViewModel {

    /**
     * How much of the conversation is sent along with a message. Older messages are left out so
     * that models with small context windows keep working in long chats.
     */
    private static final int MAX_HISTORY_MESSAGES = 16;
    private static final int MAX_HISTORY_CHARS = 24_000;

    private final AiTaskRunner taskRunner = new AiTaskRunner();
    /**
     * Saving and loading history must not queue behind a request that can take minutes.
     */
    private final AiTaskRunner historyRunner = new AiTaskRunner();
    private final List<ChatItem> items = new ArrayList<>();
    private final MutableLiveData<List<ChatItem>> itemsData = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<AttachedProject> attachedProject = new MutableLiveData<>();
    private final MutableLiveData<List<AiChatSession>> sessions = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> sessionsLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> activeSessionId = new MutableLiveData<>();
    /**
     * Fires once per created or changed project, for refreshing the project list.
     */
    private final MutableLiveData<String> changedProject = new MutableLiveData<>();
    private final MutableLiveData<String> historyError = new MutableLiveData<>();
    private final AiHistoryManager historyManager;
    private String currentSessionId;
    private String currentSessionTitle;
    private String sessionQuery;
    /**
     * The unfinished project task of this chat, saved after every finished step; null if none.
     */
    private AiTaskCheckpoint pendingTask;
    private long nextId = 1;

    public AiChatViewModel(@NonNull Application application) {
        super(application);
        historyManager = new AiHistoryManager(application);
        currentSessionId = UUID.randomUUID().toString();
        activeSessionId.setValue(currentSessionId);
        refreshSessions();
    }

    @NonNull
    public String getCurrentSessionId() {
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString();
        }
        return currentSessionId;
    }

    @NonNull
    public LiveData<List<ChatItem>> getItems() {
        return itemsData;
    }

    /**
     * @return Whether the AI is working, during which no new message can be sent.
     */
    @NonNull
    public LiveData<Boolean> isBusy() {
        return busy;
    }

    /**
     * @return The project the next message changes, or null for a new project.
     */
    @NonNull
    public LiveData<AttachedProject> getAttachedProject() {
        return attachedProject;
    }

    @NonNull
    public LiveData<String> getChangedProject() {
        return changedProject;
    }

    /**
     * @return The saved conversations matching the current search, most recent first.
     */
    @NonNull
    public LiveData<List<AiChatSession>> getSessions() {
        return sessions;
    }

    @NonNull
    public LiveData<Boolean> isSessionsLoading() {
        return sessionsLoading;
    }

    /**
     * @return The conversation shown in the chat, for highlighting it in the history.
     */
    @NonNull
    public LiveData<String> getActiveSessionId() {
        return activeSessionId;
    }

    public void attach(@Nullable AttachedProject project) {
        attachedProject.setValue(project);
    }

    // ---------------------------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------------------------

    public void refreshSessions() {
        sessionsLoading.setValue(true);
        String query = sessionQuery;
        historyRunner.run(mainThread -> historyManager.search(query), new AiTaskRunner.Callback<>() {
            @Override
            public void onSuccess(List<AiChatSession> result) {
                sessions.setValue(result);
                sessionsLoading.setValue(false);
            }

            @Override
            public void onError(@NonNull Exception e) {
                sessionsLoading.setValue(false);
            }
        });
    }

    public void searchSessions(@Nullable String query) {
        sessionQuery = query;
        refreshSessions();
    }

    public void renameSession(@NonNull String sessionId, @NonNull String title) {
        String cleaned = title.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return;
        }
        if (sessionId.equals(currentSessionId)) {
            currentSessionTitle = cleaned;
        }
        historyRunner.run(mainThread -> historyManager.renameSession(sessionId, cleaned),
                new AiTaskRunner.Callback<>() {
                    @Override
                    public void onSuccess(Boolean renamed) {
                        refreshSessions();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        refreshSessions();
                    }
                });
    }

    /**
     * Deletes a conversation; deleting the one on screen also starts a new chat.
     */
    public void deleteSession(@NonNull String sessionId) {
        if (sessionId.equals(currentSessionId)) {
            if (isWorking()) {
                return;
            }
            newChat();
        }
        historyRunner.run(mainThread -> {
            historyManager.deleteSession(sessionId);
            return null;
        }, refreshAfter());
    }

    public void clearHistory() {
        if (isWorking()) {
            return;
        }
        newChat();
        historyRunner.run(mainThread -> {
            historyManager.clearAll();
            return null;
        }, refreshAfter());
    }

    private <T> AiTaskRunner.Callback<T> refreshAfter() {
        return new AiTaskRunner.Callback<>() {
            @Override
            public void onSuccess(T result) {
                refreshSessions();
            }

            @Override
            public void onError(@NonNull Exception e) {
                refreshSessions();
            }
        };
    }

    /**
     * Clears the chat and detaches the project. The projects themselves stay.
     */
    public void newChat() {
        if (isWorking()) {
            return;
        }
        items.clear();
        pendingTask = null;
        attachedProject.setValue(null);
        currentSessionId = UUID.randomUUID().toString();
        currentSessionTitle = null;
        activeSessionId.setValue(currentSessionId);
        nextId = 1;
        publish();
    }

    /**
     * Loads a saved conversation into the chat, with its attached project.
     */
    public void loadSession(@NonNull String sessionId) {
        if (isWorking() || sessionId.equals(currentSessionId) && !items.isEmpty()) {
            return;
        }
        sessionsLoading.setValue(true);
        historyRunner.run(mainThread -> historyManager.getSession(sessionId),
                new AiTaskRunner.Callback<>() {
                    @Override
                    public void onSuccess(AiChatSession session) {
                        sessionsLoading.setValue(false);
                        if (session == null || isWorking()) {
                            // Deleted in the meantime
                            refreshSessions();
                            return;
                        }
                        currentSessionId = session.getId();
                        currentSessionTitle = session.getTitle();
                        pendingTask = session.getPendingTask();
                        activeSessionId.setValue(currentSessionId);
                        items.clear();
                        items.addAll(restore(session.getItems()));
                        nextId = 1;
                        for (ChatItem item : items) {
                            nextId = Math.max(nextId, item.getId() + 1);
                        }
                        if (session.getAttachedScId() != null && session.getAttachedProjectName() != null) {
                            attachedProject.setValue(new AttachedProject(session.getAttachedScId(), session.getAttachedProjectName()));
                        } else {
                            attachedProject.setValue(null);
                        }
                        publish();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        sessionsLoading.setValue(false);
                    }
                });
    }

    /**
     * Files are saved as rows of their own after the result they belong to; this gives each
     * result its files back, for its change summary.
     */
    private static List<ChatItem> restore(List<ChatItemDto> dtos) {
        List<ChatItem> restored = new ArrayList<>();
        for (ChatItemDto dto : dtos) {
            ChatItem item = dto.toChatItem();
            if (item != null) {
                restored.add(item);
            }
        }
        for (int i = 0; i < restored.size(); i++) {
            if (restored.get(i).getType() != ChatItem.Type.RESULT) {
                continue;
            }
            List<GeneratedFile> files = new ArrayList<>();
            for (int j = i + 1; j < restored.size() && restored.get(j).getType() == ChatItem.Type.FILE; j++) {
                files.add(restored.get(j).getFile());
            }
            restored.set(i, restored.get(i).withFiles(files));
        }
        return restored;
    }

    // ---------------------------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------------------------

    public void send(@NonNull String prompt, @NonNull AiConfiguration configuration) {
        if (isWorking()) {
            return;
        }
        // A new message replaces an unfinished task; its saved steps are no longer offered
        abandonPendingTask();
        AttachedProject target = attachedProject.getValue();
        AiIntentClassifier.Intent intent = AiIntentClassifier.classify(prompt, target != null);
        List<AiMessage> history = buildHistory();
        if (currentSessionTitle == null) {
            currentSessionTitle = AiChatTitles.fromMessage(prompt);
        }

        // General questions don't need the project; sending it would only fill the context window
        boolean usesProject = target != null && (intent.changesFiles()
                || intent == AiIntentClassifier.Intent.DEBUG || intent == AiIntentClassifier.Intent.ANALYZE);
        AttachedProject context = usesProject ? target : null;
        add(ChatItem.user(nextId++, prompt, context == null ? null : context.name()));

        if (intent == AiIntentClassifier.Intent.IDENTITY) {
            add(ChatItem.info(nextId++, AiIdentity.CREATOR_ANSWER));
            saveSession(null);
            return;
        }

        AiProjectGenerator.Stage firstStage = !intent.changesFiles()
                ? (context == null ? AiProjectGenerator.Stage.ANSWERING : AiProjectGenerator.Stage.ANALYZING)
                : (target == null ? AiProjectGenerator.Stage.PLANNING : AiProjectGenerator.Stage.READING);
        runTask(prompt, configuration, firstStage, (generator, listener) -> {
            if (intent.changesFiles() && target == null) {
                // A new app is planned first, and built the way it was planned
                AiAppPlan plan = generator.plan(prompt, history, listener);
                if (plan == null) {
                    return generator.generate(prompt, history, listener);
                }
                listener.onPlan(plan);
                if (plan.hasQuestions()) {
                    return AiProjectGenerator.Result.questions(plan);
                }
                return generator.generateFromPlan(prompt, plan, null, List.of(), listener);
            }
            if (!intent.changesFiles()) {
                return generator.answer(intent, context == null ? null : context.sc_id(),
                        context == null ? null : context.name(), prompt, history, listener);
            }
            return target == null
                    ? generator.generate(prompt, history, listener)
                    : generator.edit(target.sc_id(), prompt, history, listener);
        });
    }

    /**
     * Continues the last unfinished project task from its saved steps: a model answer that had
     * already arrived is built without asking the model again.
     */
    public void resume(@NonNull AiConfiguration configuration) {
        if (isWorking() || pendingTask == null) {
            return;
        }
        AiTaskCheckpoint checkpoint = pendingTask.copy();
        // The interruption notice is replaced by the resumed task's progress
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).getType() == ChatItem.Type.ERROR && items.get(i).isResumable()) {
                items.remove(i);
                break;
            }
        }
        publish();
        runTask(checkpoint.prompt == null ? "" : checkpoint.prompt, configuration, AiProjectGenerator.Stage.BUILDING,
                (generator, listener) -> generator.resume(checkpoint, listener));
    }

    /**
     * Builds the planned app with the user's answers to the plan's questions.
     *
     * @param answers The answers, or null to let RKD AI decide on its own.
     */
    public void answerQuestions(long itemId, @Nullable String answers, @NonNull AiConfiguration configuration) {
        if (isWorking()) {
            return;
        }
        ChatItem questions = null;
        for (ChatItem item : items) {
            if (item.getId() == itemId && item.getType() == ChatItem.Type.QUESTIONS) {
                questions = item;
            }
        }
        if (questions == null || questions.getAnswers() != null) {
            return;
        }
        AiAppPlan plan = AiAppPlan.fromJson(questions.getPlanJson());
        String prompt = questions.getQuestionsPrompt() == null ? "" : questions.getQuestionsPrompt();
        if (plan == null) {
            return;
        }
        String shown = answers == null ? getApplication().getString(R.string.ai_questions_decide_for_me) : answers;
        replace(itemId, ChatItem.questions(itemId, questions.getPlanJson(), prompt, shown));
        String forModel = answers == null
                ? "The user wants you to decide every open question yourself, as sensibly as possible."
                : answers;
        runTask(prompt, configuration, AiProjectGenerator.Stage.CONNECTING,
                (generator, listener) -> generator.generateFromPlan(prompt, plan, forModel, List.of(), listener));
    }

    /**
     * @return Whether a task can be resumed from saved steps.
     */
    public boolean hasPendingTask() {
        return pendingTask != null;
    }

    private interface TaskBody {
        AiProjectGenerator.Result run(@NonNull AiProjectGenerator generator,
                                      @NonNull AiProjectGenerator.ProgressListener listener) throws Exception;
    }

    /**
     * Runs one AI task with its live progress row. Every finished step is added to the chat above
     * that row and saved together with the task's checkpoint, so closing the app, or a dropped
     * connection, never costs the work already done.
     */
    private void runTask(@NonNull String prompt, @NonNull AiConfiguration configuration,
                         @NonNull AiProjectGenerator.Stage firstStage, @NonNull TaskBody body) {
        busy.setValue(true);
        long progressId = nextId++;
        long startedAt = SystemClock.elapsedRealtime();
        String modelName = modelLabel(configuration);
        ProgressTracker tracker = new ProgressTracker(progressId, modelName, startedAt, firstStage);
        add(tracker.toItem());
        // Saved right away, so the chat is in the history even if the app is closed meanwhile
        saveSession(prompt);

        Application application = getApplication();
        taskRunner.run(mainThread -> {
                    AiProjectGenerator generator = new AiProjectGenerator(application, configuration);
                    AiProjectGenerator.ProgressListener listener = new AiProjectGenerator.ProgressListener() {
                        @Override
                        public void onProgress(@NonNull AiProjectGenerator.Stage stage, @Nullable String detail) {
                            mainThread.post(() -> tracker.onStage(stage, detail));
                        }

                        @Override
                        public void onSteps(@NonNull List<String> steps, int charactersReceived) {
                            List<String> copy = new ArrayList<>(steps);
                            mainThread.post(() -> tracker.onSteps(copy, charactersReceived));
                        }

                        @Override
                        public void onPreview(@NonNull String answerSoFar) {
                            mainThread.post(() -> tracker.onPreview(answerSoFar));
                        }

                        @Override
                        public void onThinking(int charactersSoFar) {
                            mainThread.post(() -> tracker.onThinking(charactersSoFar));
                        }

                        @Override
                        public void onPlan(@NonNull AiAppPlan plan) {
                            mainThread.post(() -> {
                                // The plan stays in the chat, above the build that follows it
                                insertBefore(progressId, ChatItem.info(nextId++, plan.toMarkdown()));
                                tracker.startNextStep();
                                saveSession(prompt);
                            });
                        }

                        @Override
                        public void onCheckpoint(@NonNull AiTaskCheckpoint checkpoint, @Nullable String step) {
                            mainThread.post(() -> {
                                pendingTask = checkpoint;
                                if (step != null) {
                                    // The finished step moves up into the chat; the live row stays last
                                    insertBefore(progressId, ChatItem.step(nextId++, step));
                                    tracker.startNextStep();
                                }
                                saveSession(prompt);
                            });
                        }
                    };
                    return body.run(generator, listener);
                },
                new AiTaskRunner.Callback<>() {
                    @Override
                    public void onSuccess(AiProjectGenerator.Result result) {
                        pendingTask = null;
                        if (result.isQuestions() && result.getPlan() != null) {
                            replace(progressId, ChatItem.questions(progressId, result.getPlan().toJson(), prompt, null));
                            finish();
                            return;
                        }
                        if (result.isReply()) {
                            replace(progressId, ChatItem.info(progressId, result.getReply()));
                            finish();
                            return;
                        }
                        replace(progressId, ChatItem.result(progressId, result));
                        changedProject.setValue(result.getScId());
                        if (!result.isUpdate()) {
                            attachedProject.setValue(new AttachedProject(result.getScId(), result.getSpec().appName));
                        }
                        for (GeneratedFile file : result.getFiles()) {
                            add(ChatItem.file(nextId++, file));
                        }
                        finish();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        // With steps saved, the task continues from them instead of starting over
                        boolean resumable = isResumable(pendingTask);
                        if (!resumable) {
                            pendingTask = null;
                        }
                        replace(progressId, ChatItem.error(progressId, AiErrors.describe(application, e), prompt, resumable));
                        finish();
                    }
                });
    }

    /**
     * @return Whether a checkpoint holds work worth keeping: an answer, a built project, or
     * earlier repair rounds.
     */
    private static boolean isResumable(@Nullable AiTaskCheckpoint checkpoint) {
        return checkpoint != null && (checkpoint.hasAnswer() || checkpoint.built || checkpoint.attempt > 0 || checkpoint.planned);
    }

    private void abandonPendingTask() {
        if (pendingTask == null) {
            return;
        }
        pendingTask = null;
        for (int i = 0; i < items.size(); i++) {
            ChatItem item = items.get(i);
            if (item.getType() == ChatItem.Type.ERROR && item.isResumable()) {
                items.set(i, ChatItem.error(item.getId(), item.getText() == null ? "" : item.getText(),
                        item.getFailedPrompt() == null ? "" : item.getFailedPrompt(), false));
            }
        }
    }

    private void insertBefore(long id, ChatItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId() == id) {
                items.add(i, item);
                publish();
                return;
            }
        }
        add(item);
    }

    private void finish() {
        busy.setValue(false);
        saveSession(null);
    }

    /**
     * @return The earlier messages of this chat, newest last, within the history limits.
     */
    private List<AiMessage> buildHistory() {
        List<AiMessage> history = new ArrayList<>();
        int chars = 0;
        for (int i = items.size() - 1; i >= 0 && history.size() < MAX_HISTORY_MESSAGES; i--) {
            ChatItem item = items.get(i);
            AiMessage message = switch (item.getType()) {
                case USER -> item.getText() == null ? null : AiMessage.user(item.getText());
                case INFO -> item.getText() == null ? null : AiMessage.assistant(item.getText());
                case RESULT -> describeForHistory(item.getResult());
                default -> null;
            };
            if (message == null) {
                continue;
            }
            chars += message.getContent().length();
            if (chars > MAX_HISTORY_CHARS && !history.isEmpty()) {
                break;
            }
            history.add(0, message);
        }
        // Providers expect the conversation to start with the user
        while (!history.isEmpty() && history.get(0).getRole() != AiMessage.Role.USER) {
            history.remove(0);
        }
        return history;
    }

    @Nullable
    private static AiMessage describeForHistory(@Nullable AiProjectGenerator.Result result) {
        if (result == null || result.getSpec().appName == null) {
            return null;
        }
        return AiMessage.assistant((result.isUpdate() ? "I updated the project " : "I created the project ")
                + result.getSpec().appName + " (ID " + result.getScId() + ").");
    }

    /**
     * Puts a project back the way it was before the AI last changed it.
     */
    public void undo(@NonNull String sc_id, @NonNull String projectName) {
        if (isWorking()) {
            return;
        }
        busy.setValue(true);
        Application application = getApplication();
        taskRunner.run(mainThread -> {
            new AiProjectBackup(sc_id).restore();
            return sc_id;
        }, new AiTaskRunner.Callback<>() {
            @Override
            public void onSuccess(String restored) {
                add(ChatItem.info(nextId++, application.getString(R.string.ai_chat_undone, projectName)));
                changedProject.setValue(restored);
                finish();
            }

            @Override
            public void onError(@NonNull Exception e) {
                add(ChatItem.info(nextId++, application.getString(R.string.ai_chat_undo_failed, e.getMessage())));
                finish();
            }
        });
    }

    private boolean isWorking() {
        return Boolean.TRUE.equals(busy.getValue());
    }

    /**
     * @param runningPrompt The prompt being worked on, if any. Its progress row is saved as an
     *                      interruption, which is what a reopened chat should show if the app
     *                      is closed before the answer arrives.
     */
    private void saveSession(@Nullable String runningPrompt) {
        if (items.isEmpty() || currentSessionId == null) {
            return;
        }
        List<ChatItemDto> dtos = new ArrayList<>();
        for (ChatItem item : items) {
            if (item.getType() != ChatItem.Type.PROGRESS) {
                dtos.add(ChatItemDto.fromChatItem(item));
            } else if (runningPrompt != null) {
                boolean resumable = isResumable(pendingTask);
                dtos.add(ChatItemDto.fromChatItem(ChatItem.error(item.getId(), getApplication().getString(
                        resumable ? R.string.ai_chat_interrupted_resumable : R.string.ai_chat_interrupted),
                        runningPrompt, resumable)));
            }
        }
        if (dtos.isEmpty()) {
            return;
        }

        String title = currentSessionTitle != null ? currentSessionTitle : AiChatTitles.FALLBACK;
        AiChatSession session = new AiChatSession(currentSessionId, title);
        AttachedProject attached = attachedProject.getValue();
        if (attached != null) {
            session.setAttachedScId(attached.sc_id());
            session.setAttachedProjectName(attached.name());
        }
        session.setItems(dtos);
        session.setPendingTask(pendingTask == null ? null : pendingTask.copy());

        historyRunner.run(mainThread -> {
            historyManager.saveSession(session);
            return null;
        }, new AiTaskRunner.Callback<>() {
            @Override
            public void onSuccess(Object result) {
                refreshSessions();
            }

            @Override
            public void onError(@NonNull Exception e) {
                // A chat that can't be saved must not just be missing from the history
                historyError.setValue(getApplication().getString(R.string.ai_history_save_failed,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                refreshSessions();
            }
        });
    }

    /**
     * @return Fires with a message whenever saving the chat fails; cleared once shown.
     */
    @NonNull
    public LiveData<String> getHistoryError() {
        return historyError;
    }

    public void onHistoryErrorShown() {
        historyError.setValue(null);
    }

    private void add(ChatItem item) {
        items.add(item);
        publish();
    }

    private void replace(long id, ChatItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId() == id) {
                items.set(i, item);
                publish();
                return;
            }
        }
    }

    private void publish() {
        itemsData.setValue(new ArrayList<>(items));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        taskRunner.cancel();
        historyRunner.cancel();
    }

    /**
     * Builds the progress row of one task from what really happens: every stage the generator
     * reports, every step the model has written so far (see
     * {@link pro.sketchware.ai.generator.AiProgressNarrator}), and an answer streaming in. Only
     * touched on the main thread.
     */
    private final class ProgressTracker {
        private static final int MAX_LOG = 60;

        private final long id;
        private final String modelName;
        private final long startedAt;
        private final List<String> done = new ArrayList<>();
        private List<String> modelSteps = new ArrayList<>();
        private AiProjectGenerator.Stage stage;
        private String headline;
        private String preview;
        private int characters;
        private int thinkingCharacters;

        ProgressTracker(long id, String modelName, long startedAt, AiProjectGenerator.Stage stage) {
            this.id = id;
            this.modelName = modelName;
            this.startedAt = startedAt;
            this.stage = stage;
            headline = AiStageText.describe(getApplication(), stage, modelName, null);
        }

        void onStage(AiProjectGenerator.Stage newStage, @Nullable String detail) {
            String line = AiStageText.describe(getApplication(), newStage, modelName, detail);
            // The model's answer is complete once anything else happens
            if (!modelSteps.isEmpty()) {
                // Same order as shown while streaming: the headline, then its steps
                if (headline != null) {
                    addDone(List.of(headline));
                    headline = null;
                }
                addDone(modelSteps);
                modelSteps = new ArrayList<>();
                characters = 0;
            }
            if (headline != null && !headline.equals(line)) {
                addDone(List.of(headline));
            }
            stage = newStage;
            headline = line;
            publishRow();
        }

        /**
         * The steps so far were posted to the chat as finished; the row shows only what's next.
         */
        void startNextStep() {
            done.clear();
            modelSteps = new ArrayList<>();
            characters = 0;
            thinkingCharacters = 0;
            preview = null;
            publishRow();
        }

        void onSteps(List<String> steps, int charactersReceived) {
            thinkingCharacters = 0;
            modelSteps = steps;
            characters = charactersReceived;
            publishRow();
        }

        void onPreview(String answerSoFar) {
            preview = answerSoFar;
            thinkingCharacters = 0;
            publishRow();
        }

        /**
         * The model is reasoning before it writes; shown until its answer starts arriving.
         */
        void onThinking(int charactersSoFar) {
            thinkingCharacters = charactersSoFar;
            publishRow();
        }

        private void addDone(List<String> lines) {
            for (String line : lines) {
                if (done.isEmpty() || !done.get(done.size() - 1).equals(line)) {
                    done.add(line);
                }
            }
            while (done.size() > MAX_LOG) {
                done.remove(0);
            }
        }

        ChatItem toItem() {
            List<String> log = new ArrayList<>(done);
            String current = headline;
            if (thinkingCharacters > 0 && modelSteps.isEmpty() && preview == null) {
                current = getApplication().getString(R.string.ai_progress_thinking, modelName)
                        + getApplication().getString(R.string.ai_progress_received, formatSize(thinkingCharacters));
            }
            if (!modelSteps.isEmpty()) {
                // Every step but the last is written; the last is being written now
                if (headline != null) {
                    log.add(headline);
                }
                log.addAll(modelSteps.subList(0, modelSteps.size() - 1));
                current = modelSteps.get(modelSteps.size() - 1);
                if (characters > 0) {
                    current += getApplication().getString(R.string.ai_progress_received, formatSize(characters));
                }
            }
            return ChatItem.progress(id, stage, modelName, current, startedAt, log, preview);
        }

        private void publishRow() {
            replace(id, toItem());
        }
    }

    /**
     * Providers only report token counts once an answer is complete, so while it streams the
     * count is estimated at about four characters per token, and shown with "~" as an estimate.
     */
    /**
     * @return The model's ID without a vendor prefix, e.g. "gemini-2.5-flash", like the top bar;
     * a configuration's own name can be anything, like "ai".
     */
    private static String modelLabel(AiConfiguration configuration) {
        String model = configuration.getModel().trim();
        int slash = model.lastIndexOf('/');
        String version = slash >= 0 && slash < model.length() - 1 ? model.substring(slash + 1) : model;
        return version.isEmpty() ? configuration.getDisplayName() : version;
    }

    private static String formatSize(int characters) {
        int tokens = Math.max(1, Math.round(characters / 4f));
        return tokens < 1000 ? String.valueOf(tokens)
                : String.format(java.util.Locale.ROOT, "%.1fk", tokens / 1000f);
    }

    /**
     * A project the chat changes, with the name to show for it.
     */
    public static final class AttachedProject {
        private final String sc_id;
        private final String name;

        public AttachedProject(@NonNull String sc_id, @NonNull String name) {
            this.sc_id = sc_id;
            this.name = name;
        }

        @NonNull
        public String sc_id() {
            return sc_id;
        }

        @NonNull
        public String name() {
            return name;
        }
    }
}
