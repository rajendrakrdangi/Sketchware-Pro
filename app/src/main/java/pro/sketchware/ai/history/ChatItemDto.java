package pro.sketchware.ai.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;

import pro.sketchware.activities.main.fragments.ai.ChatItem;
import pro.sketchware.ai.generator.AiProjectGenerator;
import pro.sketchware.ai.generator.GeneratedFile;

/**
 * Data Transfer Object for persisting {@link ChatItem} rows to disk.
 */
public class ChatItemDto implements Serializable {

    public long id;
    public String type;
    public String text;
    public String detail;
    public String stage;
    public String stageDetail;

    // Result fields
    public String scId;
    public String appName;
    public String packageName;
    public int screenCount;
    public boolean isUpdate;
    public String reply;
    public String compileErrors;
    public boolean compileChecked;
    public String summary;
    public java.util.List<String> highlights;
    /**
     * For ERROR rows: whether the task can be resumed from its saved steps.
     */
    public boolean resumable;
    public java.util.List<String> changes;
    public java.util.List<String> notes;

    // File fields
    public String fileName;
    public String fileDescription;
    public String fileContent;
    /**
     * {@link GeneratedFile.Operation} name; absent in sessions saved by older versions.
     */
    public String fileOperation;
    public String filePreviousContent;

    public ChatItemDto() {
    }

    @NonNull
    public static ChatItemDto fromChatItem(@NonNull ChatItem item) {
        ChatItemDto dto = new ChatItemDto();
        dto.id = item.getId();
        dto.type = item.getType().name();
        dto.text = item.getText();

        if (item.getType() == ChatItem.Type.USER) {
            dto.detail = item.getProjectName();
        } else if (item.getType() == ChatItem.Type.QUESTIONS) {
            dto.detail = item.getQuestionsPrompt();
        } else if (item.getType() == ChatItem.Type.APPROVAL) {
            dto.detail = item.getApprovalScId();
        } else if (item.getType() == ChatItem.Type.ERROR) {
            dto.detail = item.getFailedPrompt();
            dto.resumable = item.isResumable();
        }

        if (item.getStage() != null) {
            dto.stage = item.getStage().name();
        }
        dto.stageDetail = item.getStageDetail();

        AiProjectGenerator.Result result = item.getResult();
        if (result != null) {
            dto.scId = result.getScId();
            dto.isUpdate = result.isUpdate();
            dto.reply = result.getReply();
            dto.compileErrors = result.getCompileErrors();
            dto.compileChecked = result.isCompileChecked();
            dto.changes = new java.util.ArrayList<>(result.getChanges());
            dto.notes = new java.util.ArrayList<>(result.getNotes());
            dto.summary = result.getSpec() == null ? null : result.getSpec().summary;
            dto.highlights = result.getSpec() == null ? null : new java.util.ArrayList<>(result.getSpec().highlights);
            if (result.getSpec() != null) {
                dto.appName = result.getSpec().appName;
                dto.packageName = result.getSpec().packageName;
                dto.screenCount = result.getSpec().screens != null ? result.getSpec().screens.size() : 0;
            }
        }

        GeneratedFile file = item.getFile();
        if (file != null) {
            dto.fileName = file.getName();
            dto.fileDescription = file.getDescription();
            dto.fileContent = file.getContent();
            dto.fileOperation = file.getOperation().name();
            dto.filePreviousContent = file.getPreviousContent();
        }

        return dto;
    }

    @Nullable
    public ChatItem toChatItem() {
        if (type == null) {
            return null;
        }

        try {
            ChatItem.Type itemType = ChatItem.Type.valueOf(type);
            switch (itemType) {
                case USER:
                    return ChatItem.user(id, text != null ? text : "", detail);
                case INFO:
                    return ChatItem.info(id, text != null ? text : "");
                case ERROR:
                    return ChatItem.error(id, text != null ? text : "", detail != null ? detail : "", resumable);
                case STEP:
                    return text == null ? null : ChatItem.step(id, text);
                case QUESTIONS:
                    return text == null ? null : ChatItem.questions(id, text, detail != null ? detail : "", stageDetail);
                case APPROVAL:
                    // A decision that was still open when the app closed can still be made: the
                    // answer is in the saved task
                    return text == null || detail == null ? null : ChatItem.approval(id, text, detail, stageDetail);
                case FILE:
                    if (fileName != null && fileContent != null) {
                        return ChatItem.file(id, new GeneratedFile(fileName,
                                fileDescription != null ? fileDescription : "", fileContent,
                                filePreviousContent, parseOperation(fileOperation)));
                    }
                    return null;
                case RESULT:
                    return ChatItem.savedResult(id, scId != null ? scId : "",
                            appName != null ? appName : "", packageName != null ? packageName : "",
                            screenCount, isUpdate, reply != null ? reply : "", compileErrors, compileChecked,
                            summary, highlights == null ? java.util.List.of() : highlights,
                            changes == null ? java.util.List.of() : changes,
                            notes == null ? java.util.List.of() : notes);
                case PROGRESS:
                default:
                    return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static GeneratedFile.Operation parseOperation(String name) {
        if (name != null) {
            try {
                return GeneratedFile.Operation.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return GeneratedFile.Operation.CREATED;
    }
}
