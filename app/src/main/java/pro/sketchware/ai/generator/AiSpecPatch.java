package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies a changes-only answer to an existing app.
 * <p>
 * When changing a project, the model sends only what changes: a screen with just its new
 * layout, the events it rewrote, a text it added. Everything it leaves out stays exactly as it was,
 * because it's copied from the project here, not retyped by the model. That keeps small changes
 * small, in tokens and in what actually changes.
 * <p>
 * Lists are matched by their entries' identity (a screen, method or variable by name, an event by
 * target and name, a resource by path), and a "removeX" key deletes entries. A complete app is a
 * valid patch too: every entry it holds simply replaces the one with the same identity.
 */
public final class AiSpecPatch {

    private static final String[] NAME = {"name"};
    private static final String[] PATH = {"path"};
    private static final String[] EVENT = {"target", "name"};

    private AiSpecPatch() {
    }

    /**
     * @param baseJson The app as it is, in the spec's JSON format.
     * @param answer   The model's answer, which may be wrapped in text or Markdown.
     * @return The changed app, in the spec's JSON format.
     */
    @NonNull
    public static String apply(@NonNull String baseJson, @NonNull String answer) throws AiSpecException {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiSpecException("It doesn't contain a JSON object.");
        }
        JsonObject patch;
        try {
            JsonElement parsed = JsonParser.parseString(answer.substring(start, end + 1));
            if (!parsed.isJsonObject()) {
                throw new AiSpecException("It isn't a JSON object.");
            }
            patch = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new AiSpecException("It isn't valid JSON (" + e.getMessage() + "). Escape quotes, backslashes and line breaks inside JSON strings.", e);
        }
        JsonObject app = JsonParser.parseString(baseJson).getAsJsonObject();
        // What a change says about itself is new every time, never carried over
        app.remove("summary");
        app.remove("highlights");
        app.remove("suggestions");
        app.remove("reply");
        apply(app, patch);
        // Small changes inside a part come last, so they also apply to parts given above
        if (patch.has("edits")) {
            applyEdits(app, asArray(patch.get("edits"), "edits"));
        }
        return app.toString();
    }

    private static void apply(JsonObject app, JsonObject patch) throws AiSpecException {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            switch (key) {
                case "edits" -> {
                }
                case "screens" -> mergeScreens(app, value);
                case "removeScreens" -> remove(app, "screens", value, NAME);
                case "itemLayouts", "icons", "javaClasses" -> upsert(app, key, value, NAME);
                case "resources", "assets" -> upsert(app, key, value, PATH);
                case "removeItemLayouts" -> remove(app, "itemLayouts", value, NAME);
                case "removeIcons" -> remove(app, "icons", value, NAME);
                case "removeJavaClasses" -> remove(app, "javaClasses", value, NAME);
                case "removeResources" -> remove(app, "resources", value, PATH);
                case "removeAssets" -> remove(app, "assets", value, PATH);
                case "permissions" -> addAll(app, key, value);
                case "strings" -> mergeObject(app, "strings", value);
                case "removeStrings" -> removeStrings(app, value);
                case "translations" -> mergeTranslations(app, value);
                case "removeLanguages" -> removeKeys(app, "translations", value);
                // The project's own facts are read from it, never taken from an answer
                case "projectImages", "projectResources", "projectJavaClasses", "projectAssets", "projectSounds", "projectFonts" -> {
                }
                default -> app.add(key, value);
            }
        }
    }

    /**
     * Find-and-replace edits inside one part of the app, e.g. a symbol in a layout or a line of an
     * event's code, so a small change costs a few lines instead of the whole part.
     */
    private static void applyEdits(JsonObject app, JsonArray edits) throws AiSpecException {
        for (JsonElement element : edits) {
            if (!element.isJsonObject()) {
                throw new AiSpecException("\"edits\" must hold objects like {\"in\": \"screens/main/layout\", \"find\": \"...\", \"replace\": \"...\"}.");
            }
            JsonObject edit = element.getAsJsonObject();
            String in = text(edit.get("in"));
            String find = edit.has("find") && edit.get("find").isJsonPrimitive() ? edit.get("find").getAsString() : null;
            String replace = edit.has("replace") && edit.get("replace").isJsonPrimitive() ? edit.get("replace").getAsString() : null;
            boolean all = edit.has("all") && edit.get("all").isJsonPrimitive() && edit.get("all").getAsBoolean();
            if (in == null || find == null || find.isEmpty() || replace == null) {
                throw new AiSpecException("Every edit needs \"in\", a non-empty \"find\" and \"replace\".");
            }
            Target target = target(app, in);
            String current = target.owner.has(target.field) && target.owner.get(target.field).isJsonPrimitive()
                    ? target.owner.get(target.field).getAsString() : "";
            String changed = replace(current, find, replace, all);
            if (changed == null) {
                throw new AiSpecException("The edit in \"" + in + "\" looks for text that isn't there: \"" + shorten(find)
                        + "\". Copy \"find\" exactly from the app as it is now, or give the whole part instead.");
            }
            target.owner.addProperty(target.field, changed);
        }
    }

    private record Target(JsonObject owner, String field) {
    }

    /**
     * @param path e.g. screens/main/layout, screens/main/events/save/onClick, resources/drawable/ai_bg.xml.
     */
    private static Target target(JsonObject app, String path) throws AiSpecException {
        String[] parts = path.split("/");
        String where = "The edit target \"" + path + "\"";
        try {
            switch (parts[0]) {
                case "screens" -> {
                    JsonObject screen = entry(app, "screens", NAME, parts[1]);
                    if (parts.length == 3 && (parts[2].equals("layout") || parts[2].equals("onCreate"))) {
                        return new Target(screen, parts[2]);
                    }
                    if (parts.length == 5 && parts[2].equals("events")) {
                        return new Target(entry(screen, "events", EVENT, parts[3], parts[4]), "code");
                    }
                    if (parts.length == 4 && parts[2].equals("methods")) {
                        return new Target(entry(screen, "methods", NAME, parts[3]), "code");
                    }
                }
                case "itemLayouts" -> {
                    return new Target(entry(app, "itemLayouts", NAME, parts[1]), "layout");
                }
                case "javaClasses" -> {
                    return new Target(entry(app, "javaClasses", NAME, parts[1]), "code");
                }
                case "resources", "assets" -> {
                    return new Target(entry(app, parts[0], PATH, path.substring(parts[0].length() + 1)), "content");
                }
                case "strings" -> {
                    JsonObject strings = app.has("strings") && app.get("strings").isJsonObject() ? app.getAsJsonObject("strings") : null;
                    if (strings != null && parts.length == 2 && strings.has(parts[1])) {
                        return new Target(strings, parts[1]);
                    }
                    throw new AiSpecException(where + " names a text that doesn't exist.");
                }
                default -> {
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            // Falls through to the explanation below
        }
        throw new AiSpecException(where + " is invalid. Use screens/NAME/layout, screens/NAME/onCreate, "
                + "screens/NAME/events/TARGET/EVENT, screens/NAME/methods/NAME, itemLayouts/NAME, javaClasses/NAME, "
                + "resources/PATH, assets/PATH or strings/NAME.");
    }

    private static JsonObject entry(JsonObject owner, String key, String[] identity, String... values) throws AiSpecException {
        JsonObject wanted = new JsonObject();
        for (int i = 0; i < identity.length; i++) {
            wanted.addProperty(identity[i], values[i]);
        }
        JsonObject found = find(array(owner, key), wanted, identity);
        if (found == null) {
            throw new AiSpecException("There is no " + key + " entry " + String.join("/", values) + " to edit.");
        }
        return found;
    }

    /**
     * Replaces exactly, or else ignoring differences in spacing and line breaks, which models
     * often get slightly wrong when they copy text.
     *
     * @return The changed text, or null if "find" isn't in it.
     */
    static String replace(String text, String find, String replacement, boolean all) {
        if (text.contains(find)) {
            return all ? text.replace(find, replacement) : text.replaceFirst(java.util.regex.Pattern.quote(find),
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        String[] words = find.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (String word : words) {
            if (regex.length() > 0) {
                regex.append("\\s+");
            }
            regex.append(java.util.regex.Pattern.quote(word));
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex.toString()).matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String quoted = java.util.regex.Matcher.quoteReplacement(replacement);
        return all ? matcher.replaceAll(quoted) : matcher.replaceFirst(quoted);
    }

    private static String shorten(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    private static void mergeScreens(JsonObject app, JsonElement value) throws AiSpecException {
        JsonArray screens = array(app, "screens");
        for (JsonElement element : asArray(value, "screens")) {
            if (!element.isJsonObject()) {
                throw new AiSpecException("\"screens\" must hold objects.");
            }
            JsonObject change = element.getAsJsonObject();
            JsonObject screen = find(screens, change, NAME);
            if (screen == null) {
                // A new screen comes complete
                screens.add(change);
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : change.entrySet()) {
                JsonElement part = entry.getValue();
                switch (entry.getKey()) {
                    case "events" -> upsert(screen, "events", part, EVENT);
                    case "methods", "variables", "lists", "components" -> upsert(screen, entry.getKey(), part, NAME);
                    case "removeEvents" -> remove(screen, "events", part, EVENT);
                    case "removeMethods" -> remove(screen, "methods", part, NAME);
                    case "removeVariables" -> remove(screen, "variables", part, NAME);
                    case "removeLists" -> remove(screen, "lists", part, NAME);
                    case "removeComponents" -> remove(screen, "components", part, NAME);
                    default -> screen.add(entry.getKey(), part);
                }
            }
        }
    }

    /**
     * Replaces the entries with the same identity and adds the new ones.
     */
    private static void upsert(JsonObject owner, String key, JsonElement value, String[] identity) throws AiSpecException {
        JsonArray list = array(owner, key);
        for (JsonElement element : asArray(value, key)) {
            if (!element.isJsonObject()) {
                throw new AiSpecException("\"" + key + "\" must hold objects.");
            }
            JsonObject entry = element.getAsJsonObject();
            JsonObject existing = find(list, entry, identity);
            if (existing == null) {
                list.add(entry);
            } else {
                for (String field : new HashSet<>(existing.keySet())) {
                    existing.remove(field);
                }
                for (Map.Entry<String, JsonElement> field : entry.entrySet()) {
                    existing.add(field.getKey(), field.getValue());
                }
            }
        }
    }

    /**
     * Removes entries named by identity: a name or path as a string, or an object like the entry.
     */
    private static void remove(JsonObject owner, String key, JsonElement value, String[] identity) throws AiSpecException {
        JsonArray list = array(owner, key);
        for (JsonElement element : asArray(value, "remove" + Character.toUpperCase(key.charAt(0)) + key.substring(1))) {
            JsonObject wanted;
            if (element.isJsonPrimitive() && identity.length == 1) {
                wanted = new JsonObject();
                wanted.add(identity[0], element);
            } else if (element.isJsonObject()) {
                wanted = element.getAsJsonObject();
            } else {
                continue;
            }
            JsonObject existing = find(list, wanted, identity);
            if (existing != null) {
                list.remove(existing);
            }
        }
    }

    private static void addAll(JsonObject owner, String key, JsonElement value) throws AiSpecException {
        JsonArray list = array(owner, key);
        Set<String> present = new HashSet<>();
        for (JsonElement element : list) {
            present.add(element.toString());
        }
        for (JsonElement element : asArray(value, key)) {
            if (present.add(element.toString())) {
                list.add(element);
            }
        }
    }

    private static void mergeObject(JsonObject owner, String key, JsonElement value) throws AiSpecException {
        if (!value.isJsonObject()) {
            throw new AiSpecException("\"" + key + "\" must be an object.");
        }
        JsonObject target = owner.has(key) && owner.get(key).isJsonObject() ? owner.getAsJsonObject(key) : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            target.add(entry.getKey(), entry.getValue());
        }
        owner.add(key, target);
    }

    private static void mergeTranslations(JsonObject app, JsonElement value) throws AiSpecException {
        if (!value.isJsonObject()) {
            throw new AiSpecException("\"translations\" must be an object.");
        }
        JsonObject translations = app.has("translations") && app.get("translations").isJsonObject()
                ? app.getAsJsonObject("translations") : new JsonObject();
        for (Map.Entry<String, JsonElement> language : value.getAsJsonObject().entrySet()) {
            mergeObject(translations, language.getKey(), language.getValue());
        }
        app.add("translations", translations);
    }

    private static void removeStrings(JsonObject app, JsonElement value) throws AiSpecException {
        removeKeys(app, "strings", value);
        if (app.has("translations") && app.get("translations").isJsonObject()) {
            for (Map.Entry<String, JsonElement> language : app.getAsJsonObject("translations").entrySet()) {
                if (language.getValue().isJsonObject()) {
                    for (JsonElement name : asArray(value, "removeStrings")) {
                        if (name.isJsonPrimitive()) {
                            language.getValue().getAsJsonObject().remove(name.getAsString());
                        }
                    }
                }
            }
        }
    }

    private static void removeKeys(JsonObject app, String key, JsonElement value) throws AiSpecException {
        if (!app.has(key) || !app.get(key).isJsonObject()) {
            return;
        }
        for (JsonElement name : asArray(value, "remove " + key)) {
            if (name.isJsonPrimitive()) {
                app.getAsJsonObject(key).remove(name.getAsString());
            }
        }
    }

    private static JsonObject find(JsonArray list, JsonObject wanted, String[] identity) {
        for (JsonElement element : list) {
            if (element.isJsonObject() && sameIdentity(element.getAsJsonObject(), wanted, identity)) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean sameIdentity(JsonObject first, JsonObject second, String[] identity) {
        for (String field : identity) {
            String a = text(first.get(field));
            String b = text(second.get(field));
            if (a == null || !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonElement element) {
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString().trim();
    }

    private static JsonArray array(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonArray()) {
            owner.add(key, new JsonArray());
        }
        return owner.getAsJsonArray(key);
    }

    private static JsonArray asArray(JsonElement value, String key) throws AiSpecException {
        if (value == null || value.isJsonNull()) {
            return new JsonArray();
        }
        if (!value.isJsonArray()) {
            throw new AiSpecException("\"" + key + "\" must be a list.");
        }
        return value.getAsJsonArray();
    }
}
