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
    private static final Pattern XML_REFERENCE = Pattern.compile("@(drawable|color|menu|anim|string)/([A-Za-z0-9_]+)");
    private static final Pattern CODE_REFERENCE = Pattern.compile("(?<![\\w.])R\\.(drawable|color|menu|anim|string)\\.([A-Za-z0-9_]+)");

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
        validateIcons(spec);
        validateResources(spec);
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
        for (String type : List.of("drawable", "color", "menu", "anim", "string")) {
            available.put(type, new LinkedHashSet<>(BUILT_IN.getOrDefault(type, Set.of())));
        }
        for (AiProjectSpec.Icon icon : spec.icons) {
            available.get("drawable").add(icon.name);
        }
        available.get("drawable").addAll(spec.projectImages);
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
        for (AiProjectSpec.Screen screen : spec.screens) {
            String where = "Screen \"" + screen.name + "\"";
            check(available, screen.layout, XML_REFERENCE, where + " layout");
            check(available, screen.onCreate, CODE_REFERENCE, where + " onCreate");
            for (AiProjectSpec.Event event : screen.events) {
                check(available, event.code, CODE_REFERENCE, where + " event " + event.target + "." + event.name);
            }
            for (AiProjectSpec.Method method : screen.methods) {
                check(available, method.code, CODE_REFERENCE, where + " method " + method.name);
            }
        }
        for (AiProjectSpec.ItemLayout itemLayout : spec.itemLayouts) {
            check(available, itemLayout.layout, XML_REFERENCE, "Item layout \"" + itemLayout.name + "\"");
        }
        for (AiProjectSpec.ResourceFile file : spec.resources) {
            check(available, file.content, XML_REFERENCE, "Resource file \"" + file.path + "\"");
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
