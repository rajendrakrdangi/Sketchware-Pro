package pro.sketchware.ai.history;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.R;
import pro.sketchware.databinding.ItemAiChatSessionBinding;
import pro.sketchware.utility.ThemeUtils;

/**
 * Adapter for displaying saved AI chat sessions in the history navigation drawer.
 */
public class AiHistoryAdapter extends RecyclerView.Adapter<AiHistoryAdapter.Holder> {

    public interface Listener {
        void onSessionClick(@NonNull AiChatSession session);
        void onSessionRename(@NonNull AiChatSession session);

        void onSessionDelete(@NonNull AiChatSession session);
    }

    private static final int MENU_RENAME = 1;
    private static final int MENU_DELETE = 2;

    private final List<AiChatSession> sessions = new ArrayList<>();
    private final Listener listener;
    private String activeSessionId;

    public AiHistoryAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<AiChatSession> newSessions, String activeId) {
        sessions.clear();
        sessions.addAll(newSessions);
        this.activeSessionId = activeId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAiChatSessionBinding binding = ItemAiChatSessionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AiChatSession session = sessions.get(position);
        Context context = holder.binding.getRoot().getContext();

        holder.binding.sessionTitle.setText(session.getTitle());

        if (session.getAttachedProjectName() != null && !session.getAttachedProjectName().isEmpty()) {
            holder.binding.sessionProject.setVisibility(View.VISIBLE);
            holder.binding.sessionProject.setText(session.getAttachedProjectName());
        } else {
            holder.binding.sessionProject.setVisibility(View.GONE);
        }

        long now = System.currentTimeMillis();
        CharSequence timeStr = now - session.getUpdatedAt() < DateUtils.MINUTE_IN_MILLIS
                ? context.getString(R.string.ai_history_just_now)
                : DateUtils.getRelativeTimeSpanString(session.getUpdatedAt(), now,
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        holder.binding.sessionTime.setText(timeStr);

        boolean isActive = session.getId().equals(activeSessionId);
        holder.binding.cardSession.setStrokeWidth(isActive ? 3 : 1);
        int strokeColor = ThemeUtils.getColor(holder.binding.cardSession,
                isActive ? R.attr.colorPrimary : R.attr.colorOutlineVariant);
        holder.binding.cardSession.setStrokeColor(strokeColor);

        holder.binding.cardSession.setOnClickListener(v -> listener.onSessionClick(session));
        holder.binding.btnSessionOptions.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(context, v);
            popup.getMenu().add(Menu.NONE, MENU_RENAME, 0, R.string.ai_history_rename);
            popup.getMenu().add(Menu.NONE, MENU_DELETE, 1, R.string.common_word_delete);
            popup.setOnMenuItemClickListener(menuItem -> {
                if (menuItem.getItemId() == MENU_RENAME) {
                    listener.onSessionRename(session);
                } else {
                    listener.onSessionDelete(session);
                }
                return true;
            });
            popup.show();
        });
        holder.binding.cardSession.setOnLongClickListener(v -> {
            listener.onSessionRename(session);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemAiChatSessionBinding binding;

        Holder(@NonNull ItemAiChatSessionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
