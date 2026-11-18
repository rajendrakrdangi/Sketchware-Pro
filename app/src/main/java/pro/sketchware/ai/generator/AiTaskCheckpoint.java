package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.ai.api.AiMessage;

/**
 * How far a project task got, saved with the chat after every finished step, so a task the app
 * was closed in, or that lost its connection, continues from there instead of starting over.
 * <p>
 * The model's answer is the expensive part. Once it's here, resuming builds and compiles from the
 * saved answer without asking the model again; only work the model hadn't finished is requested
 * anew. Plain fields only, so it's saved as JSON like the rest of the chat.
 */
public class AiTaskCheckpoint {

    /**
     * The user's request, shown and used again when resuming.
     */
    public String prompt;
    /**
     * The project being changed, or the one created by this task once it exists; null before a
     * new project was created.
     */
    public String target;
    /**
     * Whether this task changes an existing project rather than creating one.
     */
    public boolean update;
    /**
     * The project as it was before an update, in the spec format, for the change summary.
     */
    public String beforeSpecJson;
    public List<String> unsupported = new ArrayList<>();
    /**
     * Everything sent to the model so far, including earlier repair rounds.
     */
    public List<Message> conversation = new ArrayList<>();
    /**
     * The model's answer for the current attempt, once it has fully arrived; null while waiting
     * for one.
     */
    public String answer;
    /**
     * Whether {@link #answer} has already been written to {@link #target}.
     */
    public boolean built;
    /**
     * Whether the project's backup for Undo was already taken; a resumed task must not replace it
     * with the half-changed project.
     */
    public boolean backedUp;
    /**
     * The attempt the current answer belongs to; attempts after the first fix earlier problems.
     */
    public int attempt;
    /**
     * Whether the request already holds a plan agreed with the user, which isn't planned again
     * when resuming.
     */
    public boolean planned;
    /**
     * How freely an existing project may be changed, see {@link pro.sketchware.ai.AiEditPermission}.
     */
    public String permission;
    /**
     * Whether the user approved the answer's changes, for changes that wait for approval.
     */
    public boolean approved;
    /**
     * Whether answers hold only what changes, see {@link AiSpecPatch}.
     */
    public boolean patch;
    /**
     * For changes-only answers: the app the next answer changes. The project as it was until a
     * change is built, then the built app, so fixes to it can be just the fixes.
     */
    public String baseSpecJson;
    /**
     * How often the model asked to read the project's Java files during this task.
     */
    public int reads;

    @NonNull
    public List<AiMessage> conversation() {
        List<AiMessage> messages = new ArrayList<>();
        for (Message message : conversation) {
            if (message != null && message.content != null) {
                messages.add("assistant".equals(message.role) ? AiMessage.assistant(message.content) : AiMessage.user(message.content));
            }
        }
        return messages;
    }

    public void setConversation(@NonNull List<AiMessage> messages) {
        conversation = new ArrayList<>();
        for (AiMessage message : messages) {
            conversation.add(new Message(message.getRole() == AiMessage.Role.USER ? "user" : "assistant", message.getContent()));
        }
    }

    /**
     * @return Whether resuming saves a request: the model's answer is already here.
     */
    public boolean hasAnswer() {
        return answer != null && !answer.isEmpty();
    }

    /**
     * @return A copy, so a snapshot handed to the UI can't change while the task goes on.
     */
    @NonNull
    public AiTaskCheckpoint copy() {
        AiTaskCheckpoint copy = new AiTaskCheckpoint();
        copy.prompt = prompt;
        copy.target = target;
        copy.update = update;
        copy.beforeSpecJson = beforeSpecJson;
        copy.unsupported = new ArrayList<>(unsupported);
        copy.conversation = new ArrayList<>(conversation);
        copy.answer = answer;
        copy.built = built;
        copy.backedUp = backedUp;
        copy.attempt = attempt;
        copy.planned = planned;
        copy.permission = permission;
        copy.approved = approved;
        copy.patch = patch;
        copy.baseSpecJson = baseSpecJson;
        copy.reads = reads;
        return copy;
    }

    public static class Message {
        public String role;
        public String content;

        public Message() {
        }

        Message(@NonNull String role, @Nullable String content) {
            this.role = role;
            this.content = content;
        }
    }
}
