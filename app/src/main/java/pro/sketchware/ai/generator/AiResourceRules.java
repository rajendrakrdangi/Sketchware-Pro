package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Checks the icons and resource files of an answer, and that every resource the layouts and code
 * refer to exists, so a missing drawable or color is sent back to the model to fix instead of
 * failing the build.
 */
public final class AiResourceRules {

    public static final Set<String> ICON_STYLES = Set.of("baseline", "outline", "round", "sharp", "twotone");
    public static final String DEFAULT_ICON_STYLE = "round";
    public static final String DEFAULT_ICON_COLOR = "#212121";
    /**
     * Prefix of everything the AI owns in the project's resource folder.
     */
    public static final String AI_PREFIX = "ai_";
    /**
     * Resource folders the AI may write to.
     */
    public static final Set<String> FOLDERS = Set.of("drawable", "color", "values", "menu", "anim");
    /**
     * Resources every Sketchware app has, see a.a.a.Ix and the generated colors and strings.
     */
    private static final Map<String, Set<String>> BUILT_IN = Map.of(
            "color", Set.of("colorPrimary", "colorPrimaryDark", "colorAccent", "colorControlHighlight", "colorControlNormal"),
            "string", Set.of("app_name"));

    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern ICON_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern HEX_COLOR = Pattern.compile("#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})");
    private static final Pattern PATH = Pattern.compile("(drawable|color|values|menu|anim)/(ai_[a-z0-9_]*)\\.xml");
    private static final Pattern VALUES_ENTRY = Pattern.compile("<(color|string|dimen|integer|bool|style|array|string-array|integer-array)\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"");
    /**
     * References in XML (<code>@drawable/x</code>) and in code (<code>R.drawable.x</code>).
     */
    private static final Pattern XML_REFERENCE = Pattern.compile("@(drawable|color|menu|anim|string|raw)/([A-Za-z0-9_]+)");
    private static final Pattern CODE_REFERENCE = Pattern.compile("(?<![\\w.])R\\.(drawable|color|menu|anim|string|raw)\\.([A-Za-z0-9_]+)");
    /**
     * Asset files opened in code, and fonts, which Sketchware puts in assets/fonts.
     */
    private static final Pattern ASSET_REFERENCE = Pattern.compile("(?:getAssets\\(\\)\\.open\\(\\s*\"|file:///android_asset/)([^\"]+)\"");
    private static final Pattern FONT_REFERENCE = Pattern.compile("\"fonts/([^\"/]+)\\.ttf\"");
    private static final Pattern ASSET_PATH = Pattern.compile("([a-z0-9_]+/)*ai_[a-z0-9_]+\\.(json|txt|csv|html|md|xml)");
    static final int MAX_ASSETS = 10;
    static final int MAX_ASSET_CHARS = 150_000;

    private AiResourceRules() {
    }

    /**
     * Tidies and checks the icons and resource files, then every resource reference.
     */
    public static void validate(@NonNull AiProjectSpec spec) throws AiSpecException {
        spec.icons = nonNull(spec.icons);
        spec.resources = nonNull(spec.resources);
        spec.projectImages = nonNull(spec.projectImages);
        spec.projectResources = nonNull(spec.projectResources);
        spec.assets = nonNull(spec.assets);
        spec.projectAssets = nonNull(spec.projectAssets);
        spec.projectSounds = nonNull(spec.projectSounds);
        spec.projectFonts = nonNull(spec.projectFonts);
        validateIcons(spec);
        validateResources(spec);
        validateAssets(spec);
        checkReferences(spec);
    }

    private static void validateIcons(AiProjectSpec spec) throws AiSpecException {
        Set<String> names = new LinkedHashSet<>();
        for (AiProjectSpec.Icon icon : spec.icons) {
            if (icon == null) {
                throw new AiSpecException("\"icons\" contains null.");
            }
            if (icon.name == null || !RESOURCE_NAME.matcher(icon.name.trim()).matches()) {
                throw new AiSpecException("The icon name \"" + icon.name + "\" is invalid. Use lowercase letters, digits and underscores, e.g. \"ic_add\".");
            }
            icon.name = icon.name.trim();
            if (!names.add(icon.name)) {
                throw new AiSpecException("The icon name \"" + icon.name + "\" is used twice.");
            }
            icon.icon = icon.icon == null ? "" : icon.icon.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            if (!ICON_NAME.matcher(icon.icon).matches()) {
                throw new AiSpecException("The icon \"" + icon.name + "\" needs \"icon\": a Material icon name such as \"add\" or \"home\".");
            }
            icon.style = icon.style == null || icon.style.isBlank() ? DEFAULT_ICON_STYLE : icon.style.trim().toLowerCase(Locale.ROOT);
            if (icon.style.equals("outlined")) {
                icon.style = "outline";
            } else if (icon.style.equals("rounded")) {
                icon.style = "round";
            } else if (icon.style.equals("filled")) {
                icon.style = "baseline";
            }
            if (!ICON_STYLES.contains(icon.style)) {
                throw new AiSpecException("The icon \"" + icon.name + "\" has the style \"" + icon.style + "\". Use one of " + String.join(", ", ICON_STYLES) + ".");
            }
            icon.color = icon.color == null || icon.color.isBlank() ? DEFAULT_ICON_COLOR : icon.color.trim();
            if (!HEX_COLOR.matcher(icon.color).matches()) {
                throw new AiSpecException("The icon \"" + icon.name + "\" has the color \"" + icon.color + "\". Use a hex color like #FFFFFF.");
            }
        }
    }

    private static void validateResources(AiProjectSpec spec) throws AiSpecException {
        Set<String> paths = new LinkedHashSet<>();
        for (AiProjectSpec.ResourceFile file : spec.resources) {
            if (file == null) {
                throw new AiSpecException("\"resources\" contains null.");
            }
            file.path = file.path == null ? "" : file.path.trim();
            Matcher path = PATH.matcher(file.path);
            if (!path.matches()) {
                throw new AiSpecException("The resource path \"" + file.path + "\" is invalid. Use a folder of "
                        + String.join(", ", FOLDERS) + " and a lowercase file name starting with \"" + AI_PREFIX
                        + "\", e.g. \"drawable/ai_card_background.xml\".");
            }
            if (!paths.add(file.path)) {
                throw new AiSpecException("The resource file \"" + file.path + "\" is defined twice.");
            }
            if (file.content == null || file.content.isBlank()) {
                throw new AiSpecException("The resource file \"" + file.path + "\" has no content.");
            }
            file.content = file.content.trim();
            String problem = xmlProblem(file.content);
            if (problem != null) {
                throw new AiSpecException("The resource file \"" + file.path + "\" isn't valid XML (" + problem + ").");
            }
            if ("values".equals(path.group(1))) {
                Matcher entry = VALUES_ENTRY.matcher(file.content);
                while (entry.find()) {
                    if (!entry.group(2).startsWith(AI_PREFIX)) {
                        throw new AiSpecException("In \"" + file.path + "\", the " + entry.group(1) + " \"" + entry.group(2)
                                + "\" must be named with the prefix \"" + AI_PREFIX + "\", e.g. \"" + AI_PREFIX + entry.group(2)
                                + "\", so it can't clash with resources Sketchware generates.");
                    }
                }
            }
        }
    }

    private static void validateAssets(AiProjectSpec spec) throws AiSpecException {
        if (spec.assets.size() > MAX_ASSETS) {
            throw new AiSpecException("Use at most " + MAX_ASSETS + " files in \"assets\".");
        }
        Set<String> paths = new LinkedHashSet<>();
        for (AiProjectSpec.AssetFile file : spec.assets) {
            if (file == null) {
                throw new AiSpecException("\"assets\" contains null.");
            }
            file.path = file.path == null ? "" : file.path.trim();
            if (!ASSET_PATH.matcher(file.path).matches()) {
                throw new AiSpecException("The asset path \"" + file.path + "\" is invalid. Use a lowercase file name starting with \""
                        + AI_PREFIX + "\" and ending in .json, .txt, .csv, .html, .md or .xml, e.g. \"ai_questions.json\".");
            }
            if (!paths.add(file.path)) {
                throw new AiSpecException("The asset file \"" + file.path + "\" is defined twice.");
            }
            if (file.content == null || file.content.isBlank()) {
                throw new AiSpecException("The asset file \"" + file.path + "\" has no content.");
            }
            if (file.content.length() > MAX_ASSET_CHARS) {
                throw new AiSpecException("The asset file \"" + file.path + "\" is too long; keep each under " + MAX_ASSET_CHARS + " characters.");
            }
            String problem = file.path.endsWith(".json") ? jsonProblem(file.content)
                    : file.path.endsWith(".xml") ? xmlProblem(file.content.trim()) : null;
            if (problem != null) {
                throw new AiSpecException("The asset file \"" + file.path + "\" isn't valid (" + problem + ").");
            }
        }
    }

    /**
     * Strict, like org.json in the app, so a file that parses here also parses there.
     */
    @Nullable
    private static String jsonProblem(String json) {
        try {
            com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
            reader.setLenient(false);
            new com.google.gson.Gson().getAdapter(com.google.gson.JsonElement.class).read(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                return "more than one JSON value";
            }
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            // Gson's advice is for Java code; the model only needs where the JSON is broken
            return message == null ? e.getClass().getSimpleName()
                    : message.replace("Use JsonReader.setLenient(true) to accept malformed JSON", "malformed JSON");
        }
    }

    @Nullable
    private static String xmlProblem(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            // Problems are reported through the exception; the default handler would also log them
            builder.setErrorHandler(null);
            builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null ? e.getClass().getSimpleName() : message;
        }
    }

    /**
     * @return Every resource that may be referred to, by type: declared ones, the project's own,
     * and the ones every Sketchware app has.
     */
    @NonNull
    static Map<String, Set<String>> available(@NonNull AiProjectSpec spec) {
        Map<String, Set<String>> available = new LinkedHashMap<>();
        for (String type : List.of("drawable", "color", "menu", "anim", "string", "raw")) {
            available.put(type, new LinkedHashSet<>(BUILT_IN.getOrDefault(type, Set.of())));
        }
        for (AiProjectSpec.Icon icon : spec.icons) {
            available.get("drawable").add(icon.name);
        }
        available.get("drawable").addAll(spec.projectImages);
        available.get("raw").addAll(spec.projectSounds);
        for (String resource : spec.projectResources) {
            int slash = resource.indexOf('/');
            if (slash > 0 && available.containsKey(resource.substring(0, slash))) {
                available.get(resource.substring(0, slash)).add(resource.substring(slash + 1));
            }
        }
        for (AiProjectSpec.ResourceFile file : spec.resources) {
            Matcher path = PATH.matcher(file.path);
            if (!path.matches()) {
                continue;
            }
            String folder = path.group(1);
            String name = path.group(2);
            switch (folder) {
                case "values" -> {
                    Matcher entry = VALUES_ENTRY.matcher(file.content);
                    while (entry.find()) {
                        String type = entry.group(1);
                        if (available.containsKey(type)) {
                            available.get(type).add(entry.group(2));
                        }
                    }
                }
                // A color state list in color/ is used as @color/ too
                case "color" -> available.get("color").add(name);
                default -> available.get(folder).add(name);
            }
        }
        return available;
    }

    private static void checkReferences(AiProjectSpec spec) throws AiSpecException {
        Map<String, Set<String>> available = available(spec);
        Set<String> assets = new LinkedHashSet<>(spec.projectAssets);
        for (AiProjectSpec.AssetFile file : spec.assets) {
            assets.add(file.path);
        }
        for (AiProjectSpec.Screen screen : spec.screens) {
            String where = "Screen \"" + screen.name + "\"";
            check(available, screen.layout, XML_REFERENCE, where + " layout");
            checkCode(available, assets, spec.projectFonts, screen.onCreate, where + " onCreate");
            for (AiProjectSpec.Event event : screen.events) {
                checkCode(available, assets, spec.projectFonts, event.code, where + " event " + event.target + "." + event.name);
            }
            for (AiProjectSpec.Method method : screen.methods) {
                checkCode(available, assets, spec.projectFonts, method.code, where + " method " + method.name);
            }
        }
        if (spec.javaClasses != null) {
            for (AiProjectSpec.JavaClass javaClass : spec.javaClasses) {
                if (javaClass != null) {
                    checkCode(available, assets, spec.projectFonts, javaClass.code, "Helper class \"" + javaClass.name + "\"");
                }
            }
        }
        for (AiProjectSpec.ItemLayout itemLayout : spec.itemLayouts) {
            check(available, itemLayout.layout, XML_REFERENCE, "Item layout \"" + itemLayout.name + "\"");
        }
        for (AiProjectSpec.ResourceFile file : spec.resources) {
            check(available, file.content, XML_REFERENCE, "Resource file \"" + file.path + "\"");
        }
    }

    private static void checkCode(Map<String, Set<String>> available, Set<String> assets, List<String> fonts,
                                  @Nullable String code, String where) throws AiSpecException {
        check(available, code, CODE_REFERENCE, where);
        if (code == null) {
            return;
        }
        Matcher font = FONT_REFERENCE.matcher(code);
        while (font.find()) {
            if (!fonts.contains(font.group(1))) {
                throw new AiSpecException(where + " uses the font \"fonts/" + font.group(1) + ".ttf\", which the project doesn't have. "
                        + "Fonts can't be added by you; use one in \"projectFonts\" (" + (fonts.isEmpty() ? "none" : String.join(", ", fonts))
                        + ") or android.graphics.Typeface.DEFAULT_BOLD, Typeface.SERIF or Typeface.MONOSPACE.");
            }
        }
        Matcher asset = ASSET_REFERENCE.matcher(code);
        while (asset.find()) {
            String path = asset.group(1);
            boolean isFont = path.startsWith("fonts/") && path.endsWith(".ttf")
                    && fonts.contains(path.substring("fonts/".length(), path.length() - ".ttf".length()));
            if (!isFont && !assets.contains(path)) {
                throw new AiSpecException(where + " opens the asset \"" + path + "\", which doesn't exist. Declare it in \"assets\" "
                        + "(e.g. \"ai_data.json\") or use one in \"projectAssets\" (" + (assets.isEmpty() ? "none" : String.join(", ", assets)) + ").");
            }
        }
    }

    private static void check(Map<String, Set<String>> available, @Nullable String text, Pattern pattern, String where) throws AiSpecException {
        if (text == null) {
            return;
        }
        Matcher reference = pattern.matcher(text);
        while (reference.find()) {
            String type = reference.group(1);
            String name = reference.group(2);
            Set<String> names = available.get(type);
            if (names != null && !names.contains(name)) {
                String hint = switch (type) {
                    case "drawable" -> "Declare it in \"icons\" (an icon from the icon pack) or as \"resources\" file drawable/ai_….xml";
                    case "color" -> "Declare it in a values resource file, e.g. values/ai_colors.xml with <color name=\"ai_…\">, or write the hex color";
                    case "string" -> "Write the text inline instead";
                    case "raw" -> "Sounds can't be added by you; use one in \"projectSounds\", or android.media.ToneGenerator for a simple beep";
                    default -> "Declare it as a \"resources\" file " + type + "/ai_….xml";
                };
                throw new AiSpecException(where + " uses the " + type + " \"" + name + "\", which doesn't exist. " + hint
                        + ". Available " + type + "s: " + (names.isEmpty() ? "none" : String.join(", ", names)) + ".");
            }
        }
    }

    private static <T> List<T> nonNull(@Nullable List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
