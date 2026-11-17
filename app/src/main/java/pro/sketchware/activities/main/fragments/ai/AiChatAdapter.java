package pro.sketchware.activities.main.fragments.ai;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import pro.sketchware.R;
import pro.sketchware.ai.generator.AiAppPlan;
import pro.sketchware.ai.generator.AiProjectGenerator;
import pro.sketchware.ai.generator.GeneratedFile;
import pro.sketchware.ai.ui.CodeSyntaxHighlighter;
import pro.sketchware.ai.ui.DiffRenderer;
import pro.sketchware.databinding.ItemAiChatAssistantBinding;
import pro.sketchware.databinding.ItemAiChatFileBinding;
import pro.sketchware.databinding.ItemAiChatQuestionsBinding;
import pro.sketchware.databinding.ItemAiChatUserBinding;
import pro.sketchware.utility.ThemeUtils;

/**
 * Shows the AI chat: the user's prompts on the right, the AI's answers on the left, and every
 * file it created, changed or deleted as a card with its real diff.
 */
class AiChatAdapter extends ListAdapter<ChatItem, AiChatAdapter.Holder> {

    private static final long TYPING_DOT_DURATION_MS = 420;
    private static final long TYPING_DOT_STAGGER_MS = 140;
    private static final float TYPING_DOT_LIFT_DP = 4;
    private static final long TIMER_TICK_MS = 1000;
    /**
     * Steps of the log shown under the current one; older ones are only counted.
     */
    private static final int MAX_LOG_LINES = 8;
    /**
     * Payload for expanding or collapsing a file card, which rebinds only that part.
     */
    private static final Object PAYLOAD_EXPANSION = new Object();
    /**
     * Files longer than this show their first lines only; Copy still copies everything.
     */
    private static final int MAX_RENDERED_LINES = 1500;

    private static final DiffUtil.ItemCallback<ChatItem> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull ChatItem oldItem, @NonNull ChatItem newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull ChatItem oldItem, @NonNull ChatItem newItem) {
            // Items are immutable, so a different instance means different content
            return oldItem == newItem;
        }
    };

    private final Listener listener;
    private final Set<Long> expandedFiles = new HashSet<>();
    /**
     * Files showing their whole content instead of only the changed parts.
     */
    private final Set<Long> fullFiles = new HashSet<>();
    /**
     * Options chosen and answers typed in question cards, by row and question.
     */
    private final Map<Long, Map<Integer, Set<String>>> questionChoices = new HashMap<>();
    private final Map<Long, Map<Integer, String>> questionTexts = new HashMap<>();

    AiChatAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType().ordinal();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ViewBinding binding = switch (ChatItem.Type.values()[viewType]) {
            case USER -> ItemAiChatUserBinding.inflate(inflater, parent, false);
            case FILE -> ItemAiChatFileBinding.inflate(inflater, parent, false);
            case QUESTIONS -> ItemAiChatQuestionsBinding.inflate(inflater, parent, false);
            case PROGRESS, RESULT, ERROR, INFO, STEP -> ItemAiChatAssistantBinding.inflate(inflater, parent, false);
        };
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ChatItem item = getItem(position);
        holder.stopTyping();
        holder.stopTimer();
        if (holder.binding instanceof ItemAiChatUserBinding user) {
            user.message.setText(item.getText());
            user.project.setVisibility(item.getProjectName() == null ? View.GONE : View.VISIBLE);
            user.project.setText(item.getProjectName());
        } else if (holder.binding instanceof ItemAiChatFileBinding file) {
            bindFile(file, item);
        } else if (holder.binding instanceof ItemAiChatQuestionsBinding questions) {
            bindQuestions(questions, item);
        } else {
            bindAssistant(holder, (ItemAiChatAssistantBinding) holder.binding, item);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_EXPANSION) && holder.binding instanceof ItemAiChatFileBinding file) {
            ChatItem item = getItem(position);
            applyExpansion(file, Objects.requireNonNull(item.getFile()), item.getId(), true);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        holder.stopTyping();
        holder.stopTimer();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull Holder holder) {
        super.onViewDetachedFromWindow(holder);
        holder.stopTimer();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull Holder holder) {
        super.onViewAttachedToWindow(holder);
        int position = holder.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION && getItem(position).getType() == ChatItem.Type.PROGRESS) {
            holder.startTimer(getItem(position));
        }
    }

    private void bindAssistant(Holder holder, ItemAiChatAssistantBinding binding, ChatItem item) {
        Context context = binding.getRoot().getContext();
        boolean hasActions = item.getType() == ChatItem.Type.RESULT || item.getType() == ChatItem.Type.ERROR;
        binding.typing.setVisibility(item.getType() == ChatItem.Type.PROGRESS ? View.VISIBLE : View.GONE);
        binding.actions.setVisibility(hasActions ? View.VISIBLE : View.GONE);
        binding.message.setTextColor(ThemeUtils.getColor(binding.message,
                item.getType() == ChatItem.Type.ERROR ? R.attr.colorError : R.attr.colorOnSurface));

        switch (item.getType()) {
            case PROGRESS -> {
                holder.startTimer(item);
                holder.startTyping();
            }
            case RESULT -> bindResult(binding, Objects.requireNonNull(item.getResult()));
            case ERROR -> {
                binding.message.setText(item.getText());
                String err = item.getText() != null ? item.getText().toLowerCase(Locale.ROOT) : "";
                boolean isConfigIssue = err.contains("api key") || err.contains("ai configuration");
                String failedPrompt = item.getFailedPrompt();
                View.OnClickListener retry = failedPrompt == null || failedPrompt.isEmpty()
                        ? null : v -> listener.onRetry(failedPrompt);
                if (retry == null) {
                    binding.actions.setVisibility(isConfigIssue || item.isResumable() ? View.VISIBLE : View.GONE);
                }
                if (item.isResumable()) {
                    // Continues from the saved steps; starting over stays possible
                    bindActions(binding, R.string.ai_chat_resume, v -> listener.onResumeTask(),
                            R.string.ai_chat_retry, retry);
                } else if (isConfigIssue) {
                    bindActions(binding, R.string.ai_error_open_config, v -> listener.onOpenConfiguration(),
                            R.string.ai_chat_retry, retry);
                } else if (retry != null) {
                    bindActions(binding, R.string.ai_chat_retry, retry, 0, null);
                }
            }
            case INFO -> binding.message.setText(CodeSyntaxHighlighter.formatChatText(context, item.getText() != null ? item.getText() : ""));
            case STEP -> {
                // A finished step of a task, kept in the chat above the step being worked on
                binding.message.setTextColor(ThemeUtils.getColor(binding.message, R.attr.colorOnSurfaceVariant));
                SpannableStringBuilder step = new SpannableStringBuilder("✓ ");
                step.setSpan(new ForegroundColorSpan(DiffRenderer.ADDED_COLOR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                step.append(item.getText() == null ? "" : item.getText());
                step.setSpan(new RelativeSizeSpan(0.92f), 0, step.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                binding.message.setText(step);
            }
            default -> throw new IllegalStateException("Not an assistant item: " + item.getType());
        }
    }

    /**
     * The completion report: what was created, changed and deleted with real line counts, and
     * exactly which checks ran.
     */
    /**
     * The report after a project task, written for someone who doesn't code: what the app is now
     * and what they can do with it, in everyday words. The technical side (files, line counts)
     * stays in the file cards below.
     */
    private void bindResult(ItemAiChatAssistantBinding binding, AiProjectGenerator.Result result) {
        Context context = binding.getRoot().getContext();
        String appName = result.getSpec().appName == null ? "" : result.getSpec().appName;
        String sc_id = result.getScId();
        SpannableStringBuilder report = new SpannableStringBuilder();

        appendBold(report, context.getString(result.isUpdate() ? R.string.ai_report_title_updated : R.string.ai_report_title_created, appName));
        String summary = result.getSpec().summary;
        if (summary != null && !summary.trim().isEmpty()) {
            report.append("\n").append(summary.trim());
        }

        List<String> highlights = result.getSpec().highlights;
        if (highlights != null && !highlights.isEmpty()) {
            report.append("\n\n");
            appendBold(report, context.getString(result.isUpdate() ? R.string.ai_report_whats_new : R.string.ai_report_what_you_can_do));
            for (String highlight : highlights) {
                report.append("\n• ").append(highlight);
            }
        } else if (!result.getChanges().isEmpty()) {
            // Answers from before highlights existed: the factual list is all there is
            report.append("\n\n");
            appendBold(report, context.getString(R.string.ai_report_what_changed));
            for (String change : result.getChanges()) {
                report.append("\n• ").append(change);
            }
        }

        if (!result.getNotes().isEmpty()) {
            report.append("\n\n");
            appendBold(report, context.getString(R.string.ai_report_notes));
            for (String note : result.getNotes()) {
                report.append("\n• ").append(note);
            }
        }
        if (!result.getNotCarriedOver().isEmpty()) {
            report.append("\n\n").append(context.getString(R.string.ai_chat_not_carried_over,
                    String.join(", ", result.getNotCarriedOver())));
        }

        // Sessions saved by older versions don't know which checks ran
        boolean legacy = result.getFiles().isEmpty() && !result.isCompileChecked() && result.getCompileErrors() == null;
        String compileErrors = result.getCompileErrors();
        if (!legacy) {
            report.append("\n\n");
            if (compileErrors != null) {
                String errorSummary = compileErrors.length() > 700 ? compileErrors.substring(0, 700) + "…" : compileErrors;
                report.append(context.getString(R.string.ai_chat_compile_errors_left, errorSummary));
            } else if (result.isCompileChecked()) {
                report.append(context.getString(R.string.ai_report_check_ok));
            } else {
                report.append(context.getString(R.string.ai_report_check_skipped));
            }
            if (result.getFiles().isEmpty()) {
                report.append("\n").append(context.getString(R.string.ai_report_no_changes));
            }
            report.append("\n").append(context.getString(R.string.ai_report_try_it));
        }
        if (!result.isUpdate()) {
            report.append("\n\n").append(context.getString(R.string.ai_report_next_message));
        }
        binding.message.setText(report);

        if (result.isUpdate()) {
            bindActions(binding, R.string.ai_result_open, v -> listener.onOpenProject(sc_id),
                    R.string.ai_chat_undo, v -> listener.onUndo(sc_id, appName));
        } else {
            bindActions(binding, R.string.ai_result_open, v -> listener.onOpenProject(sc_id),
                    R.string.ai_chat_edit_details, v -> listener.onEditProject(sc_id));
        }
    }

    private static void appendBold(SpannableStringBuilder builder, String text) {
        int start = builder.length();
        builder.append(text);
        builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String describeStage(Context context, ChatItem item) {
        return AiStageText.describe(context, Objects.requireNonNull(item.getStage()), item.getText(), item.getStageDetail());
    }

    /**
     * The progress row: what's happening now with the elapsed time, the steps done before it, and
     * an answer as far as it has arrived.
     */
    private static CharSequence progressText(Context context, ChatItem item, String elapsed) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        String now = describeStage(context, item);
        int start = text.length();
        text.append(elapsed == null ? now : context.getString(R.string.ai_stage_elapsed, now, elapsed));
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<String> log = item.getLog();
        int from = Math.max(0, log.size() - MAX_LOG_LINES);
        if (from > 0) {
            appendSecondary(context, text, "\n" + context.getResources().getQuantityString(R.plurals.ai_progress_earlier_steps, from, from));
        }
        for (int i = from; i < log.size(); i++) {
            appendSecondary(context, text, "\n✓ " + log.get(i));
        }
        String preview = item.getPreview();
        if (preview != null && !preview.trim().isEmpty()) {
            text.append("\n\n").append(CodeSyntaxHighlighter.formatChatText(context, preview.trim()));
        }
        return text;
    }

    private static void appendSecondary(Context context, SpannableStringBuilder text, String line) {
        int start = text.length();
        text.append(line);
        text.setSpan(new ForegroundColorSpan(ThemeUtils.getColor(context, R.attr.colorOnSurfaceVariant)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.9f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * @return Elapsed time as 00:15, or 1:02:03 past an hour.
     */
    static String formatElapsed(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    private void bindActions(ItemAiChatAssistantBinding binding, int primaryText, View.OnClickListener primary,
                             int secondaryText, View.OnClickListener secondary) {
        binding.primaryAction.setText(primaryText);
        binding.primaryAction.setOnClickListener(primary);
        binding.secondaryAction.setVisibility(secondary == null ? View.GONE : View.VISIBLE);
        if (secondary != null) {
            binding.secondaryAction.setText(secondaryText);
            binding.secondaryAction.setOnClickListener(secondary);
        }
    }

    private void bindFile(ItemAiChatFileBinding binding, ChatItem item) {
        Context context = binding.getRoot().getContext();
        GeneratedFile file = Objects.requireNonNull(item.getFile());
        long id = item.getId();
        GeneratedFile.Operation operation = file.getOperation();

        binding.name.setText(file.getName());
        int operationName = switch (operation) {
            case CREATED -> R.string.ai_file_created;
            case MODIFIED -> R.string.ai_file_modified;
            case DELETED -> R.string.ai_file_deleted;
        };
        // The cards appear once the project is saved, so every listed operation has completed
        binding.details.setText(context.getString(R.string.ai_file_status,
                context.getString(operationName), context.getString(R.string.ai_file_completed))
                + " · " + file.getDescription());
        binding.stats.setText(DiffRenderer.stats(file.getDiff().getAdded(), file.getDiff().getDeleted()));
        binding.statusIcon.setImageResource(switch (operation) {
            case CREATED -> R.drawable.ic_mtrl_add;
            case MODIFIED -> R.drawable.ic_mtrl_edit;
            case DELETED -> R.drawable.ic_mtrl_delete;
        });
        binding.statusIcon.setColorFilter(switch (operation) {
            case CREATED -> DiffRenderer.ADDED_COLOR;
            case MODIFIED -> ThemeUtils.getColor(binding.statusIcon, R.attr.colorPrimary);
            case DELETED -> DiffRenderer.DELETED_COLOR;
        });

        binding.toggleView.setVisibility(operation == GeneratedFile.Operation.MODIFIED ? View.VISIBLE : View.GONE);
        applyExpansion(binding, file, id, false);

        // State lives in the adapter and is applied through a rebind of this row, so a recycled
        // or re-laid-out card always matches it and the list measures the new height itself
        binding.header.setOnClickListener(v -> {
            if (!expandedFiles.remove(id)) {
                expandedFiles.add(id);
            }
            notifyRowChanged(id);
        });
        binding.toggleView.setOnClickListener(v -> {
            if (!fullFiles.remove(id)) {
                fullFiles.add(id);
            }
            notifyRowChanged(id);
        });
        binding.copy.setVisibility(operation == GeneratedFile.Operation.DELETED ? View.GONE : View.VISIBLE);
        binding.copy.setOnClickListener(v -> listener.onCopy(file));
    }

    /**
     * The plan's questions with options to tap and, where allowed, a field for an answer of one's
     * own. Choices are kept in the adapter, so scrolling doesn't lose them.
     */
    private void bindQuestions(ItemAiChatQuestionsBinding binding, ChatItem item) {
        Context context = binding.getRoot().getContext();
        AiAppPlan plan = AiAppPlan.fromJson(item.getPlanJson());
        binding.questionsContainer.removeAllViews();
        String answers = item.getAnswers();
        if (plan == null || answers != null) {
            binding.actions.setVisibility(View.GONE);
            binding.answers.setVisibility(View.VISIBLE);
            binding.title.setText(R.string.ai_questions_answered_title);
            binding.answers.setText(answers == null ? "" : answers);
            return;
        }
        binding.title.setText(R.string.ai_questions_title);
        binding.answers.setVisibility(View.GONE);
        binding.actions.setVisibility(View.VISIBLE);
        long id = item.getId();
        Map<Integer, Set<String>> chosen = questionChoices.computeIfAbsent(id, key -> new HashMap<>());
        Map<Integer, String> typed = questionTexts.computeIfAbsent(id, key -> new HashMap<>());
        float density = context.getResources().getDisplayMetrics().density;

        for (int index = 0; index < plan.questions.size(); index++) {
            AiAppPlan.Question question = plan.questions.get(index);
            int questionIndex = index;
            TextView label = new TextView(context);
            label.setText(question.question);
            label.setTextColor(ThemeUtils.getColor(label, R.attr.colorOnSurface));
            label.setTextSize(15);
            label.setTypeface(label.getTypeface(), Typeface.BOLD);
            label.setPadding(0, (int) (14 * density), 0, (int) (4 * density));
            binding.questionsContainer.addView(label);

            Set<String> selected = chosen.computeIfAbsent(questionIndex, key -> new LinkedHashSet<>());
            ChipGroup group = new ChipGroup(context);
            group.setSingleSelection(!question.multiple);
            for (String option : question.options) {
                Chip chip = new Chip(context, null, com.google.android.material.R.attr.chipStyle);
                chip.setText(option);
                chip.setCheckable(true);
                chip.setChecked(selected.contains(option));
                chip.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) {
                        if (!question.multiple) {
                            selected.clear();
                        }
                        selected.add(option);
                    } else {
                        selected.remove(option);
                    }
                });
                group.addView(chip);
            }
            binding.questionsContainer.addView(group);

            if (question.allowCustom) {
                TextInputLayout layout = new TextInputLayout(context, null,
                        com.google.android.material.R.attr.textInputOutlinedDenseStyle);
                layout.setHint(context.getString(R.string.ai_questions_own_answer));
                TextInputEditText input = new TextInputEditText(layout.getContext());
                input.setText(typed.getOrDefault(questionIndex, ""));
                input.addTextChangedListener(new pro.sketchware.lib.base.BaseTextWatcher() {
                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        typed.put(questionIndex, s.toString());
                    }
                });
                layout.addView(input);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = (int) (4 * density);
                binding.questionsContainer.addView(layout, params);
            }
        }

        binding.submit.setOnClickListener(v -> {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < plan.questions.size(); index++) {
                List<String> parts = new ArrayList<>(chosen.getOrDefault(index, Set.of()));
                String own = typed.getOrDefault(index, "").trim();
                if (!own.isEmpty()) {
                    parts.add(own);
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(plan.questions.get(index).question).append(" → ")
                        .append(parts.isEmpty() ? context.getString(R.string.ai_questions_your_choice) : String.join(", ", parts));
            }
            listener.onAnswerQuestions(id, text.toString());
        });
        binding.skip.setOnClickListener(v -> listener.onAnswerQuestions(id, null));
    }

    private void notifyRowChanged(long id) {
        for (int i = 0; i < getItemCount(); i++) {
            if (getItem(i).getId() == id) {
                notifyItemChanged(i, PAYLOAD_EXPANSION);
                return;
            }
        }
    }

    /**
     * Shows or hides a card's code according to the adapter's state. The code is only rendered
     * while the card is open, since diffs of big files take a moment.
     */
    private void applyExpansion(ItemAiChatFileBinding binding, GeneratedFile file, long id, boolean animate) {
        boolean expanded = expandedFiles.contains(id);
        binding.body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        binding.chevron.animate().cancel();
        if (animate) {
            binding.chevron.animate().rotation(expanded ? 180 : 0).setDuration(150).start();
        } else {
            binding.chevron.setRotation(expanded ? 180 : 0);
        }
        if (!expanded) {
            // Frees the rendered text of closed cards
            binding.code.setText(null);
            return;
        }
        Context context = binding.getRoot().getContext();
        boolean full = fullFiles.contains(id);
        // A new file is all additions, so its diff would only repeat the code
        boolean hasDiff = file.getOperation() != GeneratedFile.Operation.CREATED;
        binding.toggleView.setText(full ? R.string.ai_diff_show_changes : R.string.ai_diff_show_full);
        if (hasDiff) {
            binding.code.setText(DiffRenderer.render(context, file, full));
        } else {
            String content = file.getContent();
            int lines = file.getLineCount();
            if (lines > MAX_RENDERED_LINES) {
                int cut = 0;
                for (int i = 0; i < MAX_RENDERED_LINES && cut >= 0; i++) {
                    cut = content.indexOf('\n', cut + 1);
                }
                content = content.substring(0, cut < 0 ? content.length() : cut) + "\n"
                        + context.getString(R.string.ai_diff_more_lines, lines - MAX_RENDERED_LINES);
            }
            binding.code.setText(CodeSyntaxHighlighter.highlight(context, content, file.getName()));
        }
    }

    interface Listener {
        void onOpenProject(@NonNull String sc_id);

        void onEditProject(@NonNull String sc_id);

        void onRetry(@NonNull String prompt);

        void onUndo(@NonNull String sc_id, @NonNull String projectName);

        void onCopy(@NonNull GeneratedFile file);

        void onOpenConfiguration();

        /**
         * Continues the interrupted task from its saved steps.
         */
        void onResumeTask();

        /**
         * @param answers The answers, or null to let RKD AI decide.
         */
        void onAnswerQuestions(long itemId, @Nullable String answers);
    }

    static class Holder extends RecyclerView.ViewHolder {

        private final ViewBinding binding;
        private AnimatorSet typingAnimation;
        private Runnable timer;

        Holder(@NonNull ViewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Shows the stage with the time elapsed since the task started, updated every second.
         * The start time belongs to the task, so changing stages never resets it.
         */
        void startTimer(@NonNull ChatItem item) {
            if (!(binding instanceof ItemAiChatAssistantBinding assistant)) {
                return;
            }
            stopTimer();
            Context context = itemView.getContext();
            timer = new Runnable() {
                @Override
                public void run() {
                    long elapsed = SystemClock.elapsedRealtime() - item.getStartedAt();
                    assistant.message.setText(progressText(context, item,
                            item.getStartedAt() <= 0 ? null : formatElapsed(elapsed)));
                    if (timer == this) {
                        itemView.postDelayed(this, TIMER_TICK_MS - elapsed % TIMER_TICK_MS);
                    }
                }
            };
            timer.run();
        }

        void stopTimer() {
            if (timer != null) {
                itemView.removeCallbacks(timer);
                timer = null;
            }
        }

        /**
         * Makes the three dots of an assistant row bounce one after another.
         */
        void startTyping() {
            if (!(binding instanceof ItemAiChatAssistantBinding assistant) || typingAnimation != null) {
                return;
            }
            float lift = -TYPING_DOT_LIFT_DP * itemView.getResources().getDisplayMetrics().density;
            View[] dots = {assistant.dot1, assistant.dot2, assistant.dot3};
            typingAnimation = new AnimatorSet();
            AnimatorSet.Builder builder = null;
            for (int i = 0; i < dots.length; i++) {
                ObjectAnimator bounce = ObjectAnimator.ofFloat(dots[i], View.TRANSLATION_Y, 0, lift);
                bounce.setDuration(TYPING_DOT_DURATION_MS);
                bounce.setStartDelay(i * TYPING_DOT_STAGGER_MS);
                bounce.setRepeatMode(ValueAnimator.REVERSE);
                bounce.setRepeatCount(ValueAnimator.INFINITE);
                builder = builder == null ? typingAnimation.play(bounce) : builder.with(bounce);
            }
            typingAnimation.start();
        }

        void stopTyping() {
            if (typingAnimation != null) {
                typingAnimation.cancel();
                typingAnimation = null;
                ItemAiChatAssistantBinding assistant = (ItemAiChatAssistantBinding) binding;
                assistant.dot1.setTranslationY(0);
                assistant.dot2.setTranslationY(0);
                assistant.dot3.setTranslationY(0);
            }
        }
    }
}
