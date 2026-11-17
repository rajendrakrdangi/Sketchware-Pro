package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tells what a model is writing while its project JSON is still arriving: "Designing the layout
 * of MainActivity", "Writing event save_button › onClick in MainActivity", and so on.
 * <p>
 * It scans the partial text with a tolerant JSON tokenizer that tracks where in the document each
 * key and value sits, so the steps come from what the model has really written so far, never from
 * guesses or timers.
 */
public final class AiProgressNarrator {

    private static final int MAX_SUMMARY_PREVIEW = 120;

    private AiProgressNarrator() {
    }

    /**
     * @param partialJson The model's answer so far, possibly cut off anywhere.
     * @return Every step seen so far, in order, the last one being what's being written now.
     */
    @NonNull
    public static List<String> steps(@NonNull String partialJson) {
        Scanner scanner = new Scanner();
        int start = partialJson.indexOf('{');
        if (start < 0) {
            return new ArrayList<>();
        }
        scanner.scan(partialJson, start);
        return scanner.steps;
    }

    /**
     * @return Whether the partial answer is a reply in words, i.e. <code>{"reply": ...}</code>.
     */
    public static boolean isReply(@NonNull String partialJson) {
        int start = partialJson.indexOf('{');
        if (start < 0) {
            return !partialJson.trim().isEmpty();
        }
        return partialJson.substring(start).matches("(?s)\\{\\s*\"reply\".*");
    }

    /**
     * @return The text of a partial <code>{"reply": "..."}</code> answer so far, readable, or the
     * plain text of an answer that isn't JSON at all.
     */
    @NonNull
    public static String replyPreview(@NonNull String partial) {
        int key = partial.indexOf("\"reply\"");
        if (key < 0) {
            return partial.trim();
        }
        int quote = partial.indexOf('"', partial.indexOf(':', key) + 1);
        if (quote < 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = quote + 1; i < partial.length(); i++) {
            char c = partial.charAt(i);
            if (c == '\\' && i + 1 < partial.length()) {
                char next = partial.charAt(++i);
                switch (next) {
                    case 'n' -> text.append('\n');
                    case 't' -> text.append('\t');
                    case 'u' -> {
                        if (i + 4 < partial.length()) {
                            try {
                                text.append((char) Integer.parseInt(partial.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    default -> text.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private static final class Frame {
        final boolean isObject;
        /**
         * The key this container is the value of, e.g. "screens".
         */
        final String key;
        /**
         * For objects: the key whose value comes next.
         */
        String pendingKey;
        int index = -1;
        /**
         * Values seen in this object, e.g. a screen's name or an event's target.
         */
        String name;
        String target;
        String type;

        Frame(boolean isObject, String key) {
            this.isObject = isObject;
            this.key = key;
        }
    }

    private static final class Scanner {
        final List<String> steps = new ArrayList<>();
        final List<Frame> stack = new ArrayList<>();

        void scan(String text, int start) {
            int i = start;
            int length = text.length();
            while (i < length) {
                char c = text.charAt(i);
                if (c == '"') {
                    int end = findStringEnd(text, i + 1);
                    String value = unescape(text.substring(i + 1, Math.min(end, length)));
                    boolean complete = end < length;
                    onString(value, complete, text, end + 1);
                    i = end + 1;
                    continue;
                }
                if (c == '{' || c == '[') {
                    String key = currentKey();
                    Frame parent = top();
                    if (parent != null && !parent.isObject) {
                        parent.index++;
                    }
                    Frame frame = new Frame(c == '{', key);
                    stack.add(frame);
                    onOpen(frame);
                } else if (c == '}' || c == ']') {
                    if (!stack.isEmpty()) {
                        stack.remove(stack.size() - 1);
                    }
                    clearPendingKey();
                } else if (c == ',') {
                    clearPendingKey();
                } else if (!Character.isWhitespace(c) && c != ':') {
                    // A number, true, false or null: a value
                    clearPendingKey();
                }
                i++;
            }
        }

        private static int findStringEnd(String text, int from) {
            int i = from;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
                i++;
            }
            return text.length();
        }

        private static String unescape(String value) {
            return value.replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\");
        }

        private Frame top() {
            return stack.isEmpty() ? null : stack.get(stack.size() - 1);
        }

        private String currentKey() {
            Frame top = top();
            if (top == null) {
                return null;
            }
            return top.isObject ? top.pendingKey : top.key;
        }

        private void clearPendingKey() {
            Frame top = top();
            if (top != null && top.isObject) {
                top.pendingKey = null;
            }
        }

        /**
         * @param afterEnd Index just past the closing quote, to tell a key from a value.
         */
        private void onString(String value, boolean complete, String text, int afterEnd) {
            Frame top = top();
            if (top == null) {
                return;
            }
            if (top.isObject && top.pendingKey == null) {
                // A key, if a colon follows
                int j = afterEnd;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j >= text.length() || text.charAt(j) == ':') {
                    top.pendingKey = value;
                    onKey(value);
                }
                return;
            }
            String key = top.isObject ? top.pendingKey : top.key;
            if (complete) {
                onValue(key, value);
            } else if ("summary".equals(key) || "reply".equals(key)) {
                onValue(key, value);
            }
            clearPendingKey();
        }

        private Frame enclosing(String key) {
            for (int i = stack.size() - 1; i >= 0; i--) {
                Frame frame = stack.get(i);
                if (frame.isObject && i > 0 && key.equals(stack.get(i - 1).key) && !stack.get(i - 1).isObject) {
                    return frame;
                }
            }
            return null;
        }

        private String screenLabel() {
            Frame screen = enclosing("screens");
            if (screen == null || screen.name == null) {
                return "a screen";
            }
            return activityName(screen.name);
        }

        private void onOpen(Frame frame) {
            if (frame.isObject && "admob".equals(frame.key)) {
                add("Setting up Sketchware's AdMob ads");
            }
        }

        private void onKey(String key) {
            boolean inScreen = enclosing("screens") != null && depthInScreen() == 1;
            boolean inItemLayout = enclosing("itemLayouts") != null;
            if (inScreen) {
                switch (key) {
                    case "layout" -> add("Designing the layout of " + screenLabel());
                    case "onCreate" -> add("Writing onCreate of " + screenLabel());
                    case "variables", "lists" -> add("Declaring variables of " + screenLabel());
                    default -> {
                    }
                }
            } else if (inItemLayout && "layout".equals(key)) {
                Frame item = enclosing("itemLayouts");
                add("Designing the list item layout " + (item.name == null ? "" : item.name).trim());
            } else if (stack.size() == 1) {
                switch (key) {
                    case "icons" -> add("Choosing icons");
                    case "resources" -> add("Designing shapes, colors and menus");
                    case "javaClasses" -> add("Writing helper classes");
                    case "assets" -> add("Preparing the app's data");
                    case "strings" -> add("Writing the app's texts");
                    case "translations" -> add("Translating the app");
                    case "permissions" -> add("Choosing permissions");
                    case "reply" -> add("Writing an answer");
                    default -> {
                    }
                }
            }
        }

        /**
         * @return How deep the innermost frame is below the screen object; 1 is the screen itself.
         */
        private int depthInScreen() {
            for (int i = stack.size() - 1; i > 0; i--) {
                if ("screens".equals(stack.get(i - 1).key) && !stack.get(i - 1).isObject) {
                    return stack.size() - i;
                }
            }
            return -1;
        }

        private void onValue(String key, String value) {
            Frame top = top();
            if (top == null || key == null) {
                return;
            }
            if (stack.size() == 1) {
                switch (key) {
                    case "summary" -> {
                        String preview = value.length() > MAX_SUMMARY_PREVIEW ? value.substring(0, MAX_SUMMARY_PREVIEW) + "…" : value;
                        replaceOrAdd("Plan: ", "Plan: " + preview.trim());
                    }
                    case "appName" -> add("App name: " + value);
                    default -> {
                    }
                }
                return;
            }
            String container = stack.size() >= 2 ? stack.get(stack.size() - 2).key : null;
            if (!top.isObject) {
                return;
            }
            switch (key) {
                case "name" -> {
                    top.name = value;
                    if ("screens".equals(container)) {
                        add("Creating screen " + activityName(value));
                    } else if ("methods".equals(container)) {
                        add("Writing More Block _" + value + " in " + screenLabel());
                    } else if ("events".equals(container) && top.target != null) {
                        add(eventStep(top.target, value));
                    } else if ("components".equals(container) && top.type != null) {
                        add("Adding component " + value + " (" + top.type + ") to " + screenLabel());
                    }
                }
                case "target" -> {
                    top.target = value;
                    if ("events".equals(container) && top.name != null) {
                        add(eventStep(value, top.name));
                    }
                }
                case "type" -> {
                    top.type = value;
                    if ("components".equals(container) && top.name != null) {
                        add("Adding component " + top.name + " (" + value + ") to " + screenLabel());
                    }
                }
                default -> {
                }
            }
        }

        private String eventStep(String target, String event) {
            String where = "activity".equals(target) ? "" : target + " › ";
            return "Writing event " + where + event + " in " + screenLabel();
        }

        private void add(String step) {
            if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(step)) {
                steps.add(step);
            }
        }

        private void replaceOrAdd(String prefix, String step) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).startsWith(prefix)) {
                    steps.set(i, step);
                    return;
                }
            }
            steps.add(step);
        }
    }

    /**
     * @return The Activity class of a screen, e.g. "note_editor" to "NoteEditorActivity".
     */
    @NonNull
    static String activityName(@Nullable String screen) {
        if (screen == null || screen.isEmpty()) {
            return "a screen";
        }
        StringBuilder name = new StringBuilder();
        for (String part : screen.split("_")) {
            if (!part.isEmpty()) {
                name.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return name.append("Activity").toString();
    }
}
