package pro.sketchware.ai.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import pro.sketchware.ai.generator.AiTaskCheckpoint;

/**
 * A persistent chat session recording the conversation history, target project, and generated artifacts.
 */
public class AiChatSession implements Serializable {

    private String id;
    private String title;
    private String attachedScId;
    private String attachedProjectName;
    private long createdAt;
    private long updatedAt;
    /**
     * Whether the user named the chat; automatic titles never replace a name the user chose.
     */
    private boolean titleChosen;
    private List<ChatItemDto> items = new ArrayList<>();
    /**
     * Where an unfinished project task stands, for resuming it; null when none is pending.
     */
    private AiTaskCheckpoint pendingTask;

    @Nullable
    public AiTaskCheckpoint getPendingTask() {
        return pendingTask;
    }

    public void setPendingTask(@Nullable AiTaskCheckpoint pendingTask) {
        this.pendingTask = pendingTask;
    }

    public boolean isTitleChosen() {
        return titleChosen;
    }

    public void setTitleChosen(boolean titleChosen) {
        this.titleChosen = titleChosen;
    }

    public AiChatSession() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public AiChatSession(@NonNull String id, @NonNull String title) {
        this.id = id;
        this.title = title;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    @NonNull
    public String getId() {
        return id != null ? id : "";
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    @NonNull
    public String getTitle() {
        return title != null && !title.isEmpty() ? title : AiChatTitles.FALLBACK;
    }

    public void setTitle(@NonNull String title) {
        this.title = title;
    }

    @Nullable
    public String getAttachedScId() {
        return attachedScId;
    }

    public void setAttachedScId(@Nullable String attachedScId) {
        this.attachedScId = attachedScId;
    }

    @Nullable
    public String getAttachedProjectName() {
        return attachedProjectName;
    }

    public void setAttachedProjectName(@Nullable String attachedProjectName) {
        this.attachedProjectName = attachedProjectName;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @NonNull
    public List<ChatItemDto> getItems() {
        if (items == null) {
            items = new ArrayList<>();
        }
        return items;
    }

    public void setItems(@NonNull List<ChatItemDto> items) {
        this.items = items;
    }
}
