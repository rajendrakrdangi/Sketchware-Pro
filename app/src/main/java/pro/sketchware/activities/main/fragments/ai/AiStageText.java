package pro.sketchware.activities.main.fragments.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import pro.sketchware.R;
import pro.sketchware.ai.generator.AiProjectGenerator;

/**
 * The words for each stage of an AI task, shared by the progress row and its step log.
 */
final class AiStageText {

    private AiStageText() {
    }

    /**
     * @param detail What the stage reported about itself, which wins over the generic text.
     */
    @NonNull
    static String describe(@NonNull Context context, @NonNull AiProjectGenerator.Stage stage,
                           @Nullable String modelName, @Nullable String detail) {
        if (detail != null && !detail.trim().isEmpty()) {
            return detail.trim();
        }
        return switch (stage) {
            case PLANNING -> context.getString(R.string.ai_stage_planning);
            case ANALYZING -> context.getString(R.string.ai_stage_analyzing);
            case DIAGNOSING -> context.getString(R.string.ai_stage_diagnosing);
            case ANSWERING -> context.getString(R.string.ai_stage_answering, modelName);
            case READING -> context.getString(R.string.ai_stage_reading);
            case CONNECTING -> context.getString(R.string.ai_stage_connecting);
            case GENERATING -> context.getString(R.string.ai_stage_generating, modelName);
            case REPAIRING -> context.getString(R.string.ai_stage_repairing);
            case PARSING_VIEWS -> context.getString(R.string.ai_stage_parsing_ui);
            case CREATING_COMPONENTS -> context.getString(R.string.ai_stage_creating_components);
            case BUILDING_BLOCKS -> context.getString(R.string.ai_stage_building_blocks);
            case SAVING_FILES -> context.getString(R.string.ai_stage_saving_files);
            case FINALIZING -> context.getString(R.string.ai_stage_finalizing);
            case BUILDING -> context.getString(R.string.ai_stage_building);
            case COMPILING -> context.getString(R.string.ai_stage_compiling);
            case FIXING_COMPILE_ERRORS -> context.getString(R.string.ai_stage_fixing_compile_errors_generic);
        };
    }
}
