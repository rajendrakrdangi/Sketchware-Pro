package pro.sketchware.ai.history;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import mod.jbk.util.LogUtil;

/**
 * Stores AI chat sessions in the app's private storage: one JSON file per session with all its
 * messages, and an index with every session's header for listing them quickly.
 * <p>
 * All methods do file I/O, so call them off the main thread. They're synchronized, so saves from
 * different threads can't interleave.
 */
public class AiHistoryManager {

    private static final String TAG = "AiHistoryManager";
    private static final String DIR_NAME = "ai_chat_history";
    private static final String SESSIONS_INDEX_FILE = "sessions_index.json";
    private static final String TEMP_SUFFIX = ".tmp";

    private final File historyDir;
    private final File indexFile;
    private final Gson gson = new Gson();

    public AiHistoryManager(@NonNull Context context) {
        this.historyDir = new File(context.getFilesDir(), DIR_NAME);
        this.indexFile = new File(historyDir, SESSIONS_INDEX_FILE);
    }

    private void ensureDirectory() throws IOException {
        // Checked on every write, not once: app data can be cleared while the app is running
        if (!historyDir.isDirectory() && !historyDir.mkdirs() && !historyDir.isDirectory()) {
            throw new IOException("Couldn't create " + historyDir.getPath());
        }
    }

    /**
     * @return All saved sessions, most recently updated first, without their messages.
     */
    @NonNull
    public synchronized List<AiChatSession> getSessions() {
        List<AiChatSession> list = readIndex();
        if (list == null) {
            // Missing or damaged index: the session files are the source of truth
            list = rebuildIndex();
        }
        list.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        return list;
    }

    /**
     * @param query Case-insensitive text to look for in titles and attached project names.
     */
    @NonNull
    public synchronized List<AiChatSession> search(@Nullable String query) {
        List<AiChatSession> sessions = getSessions();
        if (query == null || query.trim().isEmpty()) {
            return sessions;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<AiChatSession> matches = new ArrayList<>();
        for (AiChatSession session : sessions) {
            String project = session.getAttachedProjectName();
            if (session.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                    || (project != null && project.toLowerCase(Locale.ROOT).contains(needle))) {
                matches.add(session);
            }
        }
        return matches;
    }

    /**
     * Loads the complete session including all message items.
     */
    @Nullable
    public synchronized AiChatSession getSession(@NonNull String id) {
        File sessionFile = getSessionFile(id);
        if (!sessionFile.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(sessionFile)) {
            return gson.fromJson(reader, AiChatSession.class);
        } catch (Exception e) {
            LogUtil.e(TAG, "Couldn't read chat session " + id, e);
            return null;
        }
    }

    /**
     * Saves or updates a session. A session that is saved again keeps its creation time and,
     * if the user renamed it, its name.
     */
    public synchronized void saveSession(@NonNull AiChatSession session) throws IOException {
        List<AiChatSession> sessions = getSessions();
        AiChatSession existing = null;
        for (AiChatSession header : sessions) {
            if (header.getId().equals(session.getId())) {
                existing = header;
                break;
            }
        }
        if (existing != null) {
            session.setCreatedAt(existing.getCreatedAt());
            if (existing.isTitleChosen() && !session.isTitleChosen()) {
                session.setTitle(existing.getTitle());
                session.setTitleChosen(true);
            }
        }
        session.setUpdatedAt(System.currentTimeMillis());

        File sessionFile = getSessionFile(session.getId());
        if (!writeAtomically(sessionFile, gson.toJson(session))) {
            throw new IOException("Couldn't write " + sessionFile.getPath());
        }
        sessions.removeIf(header -> header.getId().equals(session.getId()));
        sessions.add(0, copyHeader(session));
        if (!writeAtomically(indexFile, gson.toJson(sessions))) {
            throw new IOException("Couldn't write " + indexFile.getPath());
        }
    }

    /**
     * Gives a session the name the user chose.
     *
     * @return Whether the session exists.
     */
    public synchronized boolean renameSession(@NonNull String id, @NonNull String title) {
        AiChatSession session = getSession(id);
        if (session == null) {
            return false;
        }
        session.setTitle(title);
        session.setTitleChosen(true);
        // Renaming isn't activity in the chat, so it doesn't move the session to the top
        if (!writeAtomically(getSessionFile(id), gson.toJson(session))) {
            return false;
        }
        List<AiChatSession> sessions = getSessions();
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getId().equals(id)) {
                sessions.set(i, copyHeader(session));
            }
        }
        saveIndex(sessions);
        return true;
    }

    /**
     * Deletes a session and its saved messages.
     */
    public synchronized void deleteSession(@NonNull String id) {
        File sessionFile = getSessionFile(id);
        if (sessionFile.exists()) {
            sessionFile.delete();
        }
        List<AiChatSession> sessions = getSessions();
        sessions.removeIf(session -> session.getId().equals(id));
        saveIndex(sessions);
    }

    /**
     * Clears all saved sessions.
     */
    public synchronized void clearAll() {
        File[] files = historyDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    @Nullable
    private List<AiChatSession> readIndex() {
        if (!indexFile.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(indexFile)) {
            Type listType = new TypeToken<ArrayList<AiChatSession>>() {
            }.getType();
            List<AiChatSession> list = gson.fromJson(reader, listType);
            if (list == null) {
                return null;
            }
            // An index written by an older version may hold the same session twice
            List<AiChatSession> unique = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (AiChatSession session : list) {
                if (session != null && !session.getId().isEmpty() && !seen.contains(session.getId())) {
                    seen.add(session.getId());
                    unique.add(session);
                }
            }
            return unique;
        } catch (Exception e) {
            LogUtil.e(TAG, "Chat history index is damaged, rebuilding it", e);
            return null;
        }
    }

    private List<AiChatSession> rebuildIndex() {
        List<AiChatSession> headers = new ArrayList<>();
        File[] files = historyDir.listFiles((dir, name) -> name.endsWith(".json") && !name.equals(SESSIONS_INDEX_FILE));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - ".json".length());
                AiChatSession session = getSession(id);
                if (session != null) {
                    headers.add(copyHeader(session));
                }
            }
        }
        if (!headers.isEmpty() || indexFile.exists()) {
            saveIndex(headers);
        }
        return headers;
    }

    private File getSessionFile(String id) {
        return new File(historyDir, id + ".json");
    }

    private AiChatSession copyHeader(AiChatSession source) {
        AiChatSession header = new AiChatSession(source.getId(), source.getTitle());
        header.setAttachedScId(source.getAttachedScId());
        header.setAttachedProjectName(source.getAttachedProjectName());
        header.setCreatedAt(source.getCreatedAt());
        header.setUpdatedAt(source.getUpdatedAt());
        header.setTitleChosen(source.isTitleChosen());
        return header;
    }

    private void saveIndex(List<AiChatSession> sessions) {
        writeAtomically(indexFile, gson.toJson(sessions));
    }

    /**
     * Writes to a temporary file first, so the app being killed mid-write can't leave a
     * half-written file behind.
     */
    private boolean writeAtomically(File target, String json) {
        try {
            ensureDirectory();
        } catch (IOException e) {
            LogUtil.e(TAG, "Couldn't save " + target.getName(), e);
            return false;
        }
        File temp = new File(target.getPath() + TEMP_SUFFIX);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write(json);
        } catch (IOException e) {
            LogUtil.e(TAG, "Couldn't save " + target.getName(), e);
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            // Some file systems can't rename over an existing file
            target.delete();
            if (!temp.renameTo(target)) {
                LogUtil.e(TAG, "Couldn't save " + target.getName(), null);
                temp.delete();
                return false;
            }
        }
        return true;
    }
}
