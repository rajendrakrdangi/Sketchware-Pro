package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pro.sketchware.utility.FilePathUtil;

/**
 * The app's texts and their translations. They live in the project's own strings.xml files
 * (<code>values/strings.xml</code>, <code>values-hi/strings.xml</code>...), the ones the Resource
 * editor shows, so the design editor shows the real text and the user can edit the translations.
 * <p>
 * The AI only adds, changes and removes its own entries, the ones named "ai_...": everything else in
 * those files, the user's strings and comments included, stays exactly as it is.
 */
public final class AiStrings {

    static final int MAX_STRINGS = 400;
    private static final Pattern NAME = Pattern.compile("ai_[a-z0-9_]+");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(-r[A-Z]{2})?");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXoce%]");
    private static final Pattern AI_ENTRY = Pattern.compile("[ \\t]*<string\\s+name\\s*=\\s*\"(ai_[^\"]*)\"[^>]*?(?:/>|>(.*?)</string>)[ \\t]*\\r?\\n?", Pattern.DOTALL);
    private static final Pattern RESOURCES_END = Pattern.compile("</resources>\\s*$");
    private static final Pattern ANY_ENTRY = Pattern.compile("<(?!/|!--)[a-zA-Z]");
    private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(
            Map.entry("hi", "Hindi"), Map.entry("en", "English"), Map.entry("es", "Spanish"), Map.entry("fr", "French"),
            Map.entry("de", "German"), Map.entry("ar", "Arabic"), Map.entry("bn", "Bengali"), Map.entry("ur", "Urdu"),
            Map.entry("pt", "Portuguese"), Map.entry("ru", "Russian"), Map.entry("ja", "Japanese"), Map.entry("zh", "Chinese"),
            Map.entry("ta", "Tamil"), Map.entry("te", "Telugu"), Map.entry("mr", "Marathi"), Map.entry("gu", "Gujarati"),
            Map.entry("pa", "Punjabi"), Map.entry("ne", "Nepali"), Map.entry("id", "Indonesian"), Map.entry("tr", "Turkish"),
            Map.entry("it", "Italian"), Map.entry("ko", "Korean"), Map.entry("kn", "Kannada"), Map.entry("ml", "Malayalam"));

    private AiStrings() {
    }

    /**
     * Tidies and checks the texts and translations of an answer.
     */
    static void validate(@NonNull AiProjectSpec spec) throws AiSpecException {
        Map<String, String> strings = new LinkedHashMap<>();
        if (spec.strings != null) {
            for (Map.Entry<String, String> entry : spec.strings.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                if (!NAME.matcher(name).matches()) {
                    throw new AiSpecException("The text name \"" + name + "\" is invalid. Text names start with \"ai_\" and use lowercase "
                            + "letters, digits and underscores, e.g. \"ai_welcome_title\".");
                }
                if (entry.getValue() == null) {
                    throw new AiSpecException("The text \"" + name + "\" has no value.");
                }
                strings.put(name, entry.getValue());
            }
        }
        if (strings.size() > MAX_STRINGS) {
            throw new AiSpecException("Use at most " + MAX_STRINGS + " texts in \"strings\".");
        }
        spec.strings = strings;

        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        if (spec.translations != null) {
            for (Map.Entry<String, Map<String, String>> language : spec.translations.entrySet()) {
                String locale = normalizeLocale(language.getKey());
                if (locale == null) {
                    throw new AiSpecException("The language \"" + language.getKey() + "\" in \"translations\" is invalid. Use a language code "
                            + "like \"hi\", \"es\" or \"pt-BR\".");
                }
                if (translations.containsKey(locale)) {
                    throw new AiSpecException("The language \"" + locale + "\" is translated twice.");
                }
                Map<String, String> texts = language.getValue() == null ? Map.of() : language.getValue();
                Map<String, String> cleaned = new LinkedHashMap<>();
                List<String> missing = new ArrayList<>();
                for (String name : strings.keySet()) {
                    String text = texts.get(name);
                    if (text == null) {
                        missing.add(name);
                        continue;
                    }
                    if (!placeholders(text).equals(placeholders(strings.get(name)))) {
                        throw new AiSpecException("The " + locale + " translation of \"" + name + "\" must have the same placeholders "
                                + placeholders(strings.get(name)) + " as the text, but has " + placeholders(text) + ".");
                    }
                    cleaned.put(name, text);
                }
                for (String name : texts.keySet()) {
                    if (!strings.containsKey(name)) {
                        throw new AiSpecException("The " + locale + " translation has \"" + name + "\", which isn't in \"strings\". "
                                + "Every translated text needs its default text in \"strings\".");
                    }
                }
                if (!missing.isEmpty()) {
                    throw new AiSpecException("The " + locale + " translation is missing " + missing.size() + " texts: "
                            + String.join(", ", missing.subList(0, Math.min(15, missing.size())))
                            + (missing.size() > 15 ? "..." : "") + ". Translate every text in \"strings\".");
                }
                translations.put(locale, cleaned);
            }
        }
        if (!translations.isEmpty() && strings.isEmpty()) {
            throw new AiSpecException("\"translations\" needs the texts in \"strings\", used in the layouts as @string/ai_… and in "
                    + "code as getString(R.string.ai_…).");
        }
        spec.translations = translations;
    }

    /**
     * @return The Android folder qualifier for a language code, e.g. "pt-rBR" for "pt-BR", or null.
     */
    @Nullable
    static String normalizeLocale(@Nullable String code) {
        if (code == null) {
            return null;
        }
        String locale = code.trim().replace('_', '-');
        if (locale.startsWith("values-")) {
            locale = locale.substring("values-".length());
        }
        String[] parts = locale.split("-");
        if (parts.length == 1) {
            locale = parts[0].toLowerCase(Locale.ROOT);
        } else if (parts.length == 2) {
            String region = parts[1].startsWith("r") && parts[1].length() == 3 ? parts[1].substring(1) : parts[1];
            locale = parts[0].toLowerCase(Locale.ROOT) + "-r" + region.toUpperCase(Locale.ROOT);
        } else {
            return null;
        }
        return LOCALE.matcher(locale).matches() ? locale : null;
    }

    /**
     * @return "Hindi" for "hi", "Portuguese (BR)" for "pt-rBR".
     */
    @NonNull
    public static String languageName(@NonNull String locale) {
        String[] parts = locale.split("-r");
        String name = LANGUAGE_NAMES.getOrDefault(parts[0], parts[0]);
        return parts.length > 1 ? name + " (" + parts[1] + ")" : name;
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!matcher.group().equals("%%")) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Makes the AI's entries in every strings.xml exactly the spec's.
     */
    static void write(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File root = new File(new FilePathUtil().getPathResource(sc_id));
        Map<String, Map<String, String>> wanted = new LinkedHashMap<>();
        wanted.put("values", spec.strings);
        for (Map.Entry<String, Map<String, String>> translation : spec.translations.entrySet()) {
            wanted.put("values-" + translation.getKey(), translation.getValue());
        }
        // Languages dropped since the last change lose the AI's entries too
        File[] folders = root.listFiles((dir, name) -> name.equals("values") || name.startsWith("values-"));
        if (folders != null) {
            for (File folder : folders) {
                wanted.putIfAbsent(folder.getName(), Map.of());
            }
        }
        for (Map.Entry<String, Map<String, String>> folder : wanted.entrySet()) {
            File file = new File(new File(root, folder.getKey()), "strings.xml");
            try {
                writeFile(file, folder.getValue());
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't write " + folder.getKey() + "/strings.xml", e);
            }
        }
    }

    private static void writeFile(File file, Map<String, String> entries) throws IOException {
        String existing = file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        if (existing == null && entries.isEmpty()) {
            return;
        }
        String kept = existing == null ? "<resources>\n</resources>\n" : AI_ENTRY.matcher(existing).replaceAll("");
        StringBuilder added = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            added.append("    ").append(entryXml(entry.getKey(), entry.getValue())).append('\n');
        }
        Matcher end = RESOURCES_END.matcher(kept);
        if (!end.find()) {
            throw new IOException(file + " has no </resources>");
        }
        String content = kept.substring(0, end.start()) + added + "</resources>\n";
        String inside = content.substring(content.indexOf('>') + 1, content.lastIndexOf("</resources>"));
        if (existing != null && entries.isEmpty() && !ANY_ENTRY.matcher(inside.replaceAll("(?s)<!--.*?-->", "")).find()
                && !file.getParentFile().getName().equals("values")) {
            // A language file that only held the AI's texts goes when they do
            file.delete();
            File folder = file.getParentFile();
            String[] left = folder.list();
            if (left != null && left.length == 0) {
                folder.delete();
            }
            return;
        }
        if (content.equals(existing)) {
            return;
        }
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return One string resource, escaped the way Android's resource compiler reads it.
     */
    static String entryXml(String name, String text) {
        boolean positional = text.matches("(?s).*%\\d+\\$.*");
        boolean hasPercent = text.contains("%");
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\'' -> escaped.append("\\'");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '@', '?' -> escaped.append(i == 0 ? "\\" : "").append(c);
                default -> escaped.append(c);
            }
        }
        String attributes = hasPercent && !positional ? " formatted=\"false\"" : "";
        return "<string name=\"" + name + "\"" + attributes + ">" + escaped + "</string>";
    }

    // ---------------------------------------------------------------------------------------
    // Reading, for edits
    // ---------------------------------------------------------------------------------------

    /**
     * Fills in the AI's texts and translations from the project's strings.xml files.
     */
    static void read(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File root = new File(new FilePathUtil().getPathResource(sc_id));
        Map<String, String> strings = readFile(new File(root, "values/strings.xml"));
        spec.strings = strings;
        spec.translations = new LinkedHashMap<>();
        File[] folders = root.listFiles((dir, name) -> name.startsWith("values-"));
        if (folders == null || strings.isEmpty()) {
            return;
        }
        for (File folder : folders) {
            String locale = normalizeLocale(folder.getName());
            Map<String, String> texts = readFile(new File(folder, "strings.xml"));
            if (locale != null && !texts.isEmpty()) {
                // Only texts that still exist, so a half-edited file can't block the next change
                texts.keySet().retainAll(strings.keySet());
                spec.translations.put(locale, texts);
            }
        }
    }

    private static Map<String, String> readFile(File file) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!file.isFile()) {
            return entries;
        }
        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return entries;
        }
        Matcher entry = AI_ENTRY.matcher(content);
        while (entry.find()) {
            entries.put(entry.group(1), unescape(entry.group(2) == null ? "" : entry.group(2)));
        }
        return entries;
    }

    /**
     * Turns a string resource's XML back into its text.
     */
    static String unescape(String xml) {
        String text = xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#10;", "\n").replace("&amp;", "&");
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * @return The languages of an app, as names, e.g. "Hindi, Spanish".
     */
    @NonNull
    static String languages(@NonNull Set<String> locales) {
        Set<String> names = new LinkedHashSet<>();
        for (String locale : locales) {
            names.add(languageName(locale));
        }
        return String.join(", ", names);
    }
}
