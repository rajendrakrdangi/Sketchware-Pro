package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists what really changed between two versions of a project, in Sketchware's terms: screens,
 * views, components, events, More Blocks, AdMob and permissions. It compares the specs
 * themselves, so a report can't claim a change the model didn't make, or miss one it did.
 */
public final class AiChangeSummary {

    private static final Pattern VIEW_ID = Pattern.compile("android:id\\s*=\\s*\"@\\+?id/([A-Za-z0-9_]+)\"");
    private static final int MAX_NAMES = 6;

    private AiChangeSummary() {
    }

    /**
     * @param before The project before the change, or null for a new project.
     */
    @NonNull
    public static List<String> describe(@Nullable AiProjectSpec before, @NonNull AiProjectSpec after) {
        List<String> lines = new ArrayList<>();
        Map<String, AiProjectSpec.Screen> oldScreens = screens(before);
        Map<String, AiProjectSpec.Screen> newScreens = screens(after);

        List<String> addedScreens = new ArrayList<>();
        List<String> removedScreens = new ArrayList<>();
        for (String name : newScreens.keySet()) {
            if (!oldScreens.containsKey(name)) {
                addedScreens.add(AiProgressNarrator.activityName(name));
            }
        }
        for (String name : oldScreens.keySet()) {
            if (!newScreens.containsKey(name)) {
                removedScreens.add(AiProgressNarrator.activityName(name));
            }
        }
        if (!addedScreens.isEmpty()) {
            lines.add((before == null ? "Screens: " : "Added screens: ") + names(addedScreens));
        }
        if (!removedScreens.isEmpty()) {
            lines.add("Removed screens: " + names(removedScreens));
        }

        for (AiProjectSpec.Screen screen : newScreens.values()) {
            AiProjectSpec.Screen old = oldScreens.get(screen.name);
            String activity = AiProgressNarrator.activityName(screen.name);
            List<String> parts = new ArrayList<>();
            compare(parts, "views", viewIds(old == null ? null : old.layout), viewIds(screen.layout), old == null);
            if (old != null && viewIds(old.layout).equals(viewIds(screen.layout))
                    && !normalize(old.layout).equals(normalize(screen.layout))) {
                // Same views, different attributes
                parts.add("layout changed");
            }
            compare(parts, "components", components(old), components(screen), old == null);
            compare(parts, "events", events(old), events(screen), old == null);
            changedCode(parts, "events", eventCode(old), eventCode(screen));
            compare(parts, "More Blocks", methods(old), methods(screen), old == null);
            changedCode(parts, "More Blocks", methodCode(old), methodCode(screen));
            compare(parts, "variables", fields(old), fields(screen), old == null);
            if (old != null && !normalize(old.onCreate).equals(normalize(screen.onCreate))) {
                parts.add("onCreate changed");
            }
            if (old != null && old.toolbar != screen.toolbar) {
                parts.add(screen.toolbar ? "toolbar shown" : "toolbar hidden");
            }
            if (old != null && old.fab != screen.fab) {
                parts.add(screen.fab ? "floating button added" : "floating button removed");
            }
            if (!parts.isEmpty()) {
                lines.add(activity + ": " + String.join("; ", parts));
            }
        }

        List<String> newIcons = new ArrayList<>();
        Set<String> oldIcons = new LinkedHashSet<>();
        if (before != null && before.projectImages != null) {
            oldIcons.addAll(before.projectImages);
        }
        if (after.icons != null) {
            for (AiProjectSpec.Icon icon : after.icons) {
                if (!oldIcons.contains(icon.name)) {
                    newIcons.add(icon.name);
                }
            }
        }
        if (!newIcons.isEmpty()) {
            lines.add("Icons added: " + names(newIcons));
        }
        Map<String, String> oldResources = new LinkedHashMap<>();
        if (before != null && before.resources != null) {
            for (AiProjectSpec.ResourceFile file : before.resources) {
                oldResources.put(file.path, file.content);
            }
        }
        List<String> addedResources = new ArrayList<>();
        List<String> changedResources = new ArrayList<>();
        if (after.resources != null) {
            for (AiProjectSpec.ResourceFile file : after.resources) {
                String old = oldResources.remove(file.path);
                if (old == null) {
                    addedResources.add(file.path);
                } else if (!normalize(old).equals(normalize(file.content))) {
                    changedResources.add(file.path);
                }
            }
        }
        if (!addedResources.isEmpty()) {
            lines.add("Resource files added: " + names(addedResources));
        }
        if (!changedResources.isEmpty()) {
            lines.add("Resource files changed: " + names(changedResources));
        }
        java.util.Set<String> oldLanguages = before == null || before.translations == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(before.translations.keySet());
        java.util.Set<String> newLanguages = after.translations == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(after.translations.keySet());
        java.util.Set<String> addedLanguages = new java.util.LinkedHashSet<>(newLanguages);
        addedLanguages.removeAll(oldLanguages);
        oldLanguages.removeAll(newLanguages);
        if (!addedLanguages.isEmpty()) {
            lines.add("Languages added: " + AiStrings.languages(addedLanguages));
        }
        if (!oldLanguages.isEmpty()) {
            lines.add("Languages removed: " + AiStrings.languages(oldLanguages));
        }
        int oldTexts = before == null || before.strings == null ? 0 : before.strings.size();
        int newTexts = after.strings == null ? 0 : after.strings.size();
        if (oldTexts != newTexts) {
            lines.add("Texts: " + oldTexts + " → " + newTexts);
        }
        Map<String, String> oldAssets = new LinkedHashMap<>();
        if (before != null && before.assets != null) {
            for (AiProjectSpec.AssetFile file : before.assets) {
                oldAssets.put(file.path, file.content);
            }
        }
        List<String> addedAssets = new ArrayList<>();
        List<String> changedAssets = new ArrayList<>();
        if (after.assets != null) {
            for (AiProjectSpec.AssetFile file : after.assets) {
                String old = oldAssets.remove(file.path);
                if (old == null) {
                    addedAssets.add(file.path);
                } else if (!normalize(old).equals(normalize(file.content))) {
                    changedAssets.add(file.path);
                }
            }
        }
        if (!addedAssets.isEmpty()) {
            lines.add("Data files added: " + names(addedAssets));
        }
        if (!changedAssets.isEmpty()) {
            lines.add("Data files changed: " + names(changedAssets));
        }
        if (!oldAssets.isEmpty()) {
            lines.add("Data files removed: " + names(new ArrayList<>(oldAssets.keySet())));
        }
        Map<String, String> oldClasses = new LinkedHashMap<>();
        if (before != null && before.javaClasses != null) {
            for (AiProjectSpec.JavaClass javaClass : before.javaClasses) {
                oldClasses.put(javaClass.name, javaClass.code);
            }
        }
        List<String> addedClasses = new ArrayList<>();
        List<String> changedClasses = new ArrayList<>();
        if (after.javaClasses != null) {
            for (AiProjectSpec.JavaClass javaClass : after.javaClasses) {
                String old = oldClasses.remove(javaClass.name);
                if (old == null) {
                    addedClasses.add(javaClass.name);
                } else if (!normalize(old).equals(normalize(javaClass.code))) {
                    changedClasses.add(javaClass.name);
                }
            }
        }
        if (!addedClasses.isEmpty()) {
            lines.add("Helper classes added: " + names(addedClasses));
        }
        if (!changedClasses.isEmpty()) {
            lines.add("Helper classes changed: " + names(changedClasses));
        }
        if (!oldClasses.isEmpty()) {
            lines.add("Helper classes removed: " + names(new ArrayList<>(oldClasses.keySet())));
        }
        if (!oldResources.isEmpty()) {
            lines.add("Resource files removed: " + names(new ArrayList<>(oldResources.keySet())));
        }

        boolean adsBefore = before != null && before.admob != null && before.admob.enabled;
        boolean adsAfter = after.admob != null && after.admob.enabled;
        if (adsAfter && !adsBefore) {
            lines.add("AdMob turned on (Library > AdMob)");
        }
        Set<String> newPermissions = new LinkedHashSet<>(after.permissions);
        if (before != null) {
            newPermissions.removeAll(before.permissions);
        }
        if (!newPermissions.isEmpty()) {
            List<String> shortNames = new ArrayList<>();
            for (String permission : newPermissions) {
                shortNames.add(permission.substring(permission.lastIndexOf('.') + 1));
            }
            lines.add("Permissions added: " + names(shortNames));
        }
        return lines;
    }

    private static void compare(List<String> parts, String what, Set<String> before, Set<String> after, boolean isNew) {
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        if (!added.isEmpty()) {
            parts.add((isNew ? "" : "added ") + what + " " + names(new ArrayList<>(added)));
        }
        if (!removed.isEmpty()) {
            parts.add("removed " + what + " " + names(new ArrayList<>(removed)));
        }
    }

    private static void changedCode(List<String> parts, String what, Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            String old = before.get(entry.getKey());
            if (old != null && !normalize(old).equals(normalize(entry.getValue()))) {
                changed.add(entry.getKey());
            }
        }
        if (!changed.isEmpty()) {
            parts.add("changed " + what + " " + names(changed));
        }
    }

    private static Map<String, AiProjectSpec.Screen> screens(@Nullable AiProjectSpec spec) {
        Map<String, AiProjectSpec.Screen> screens = new LinkedHashMap<>();
        if (spec != null) {
            for (AiProjectSpec.Screen screen : spec.screens) {
                screens.put(screen.name, screen);
            }
        }
        return screens;
    }

    private static Set<String> viewIds(@Nullable String layout) {
        Set<String> ids = new LinkedHashSet<>();
        if (layout != null) {
            Matcher matcher = VIEW_ID.matcher(layout);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    private static Set<String> components(@Nullable AiProjectSpec.Screen screen) {
        Set<String> names = new LinkedHashSet<>();
        if (screen != null) {
            for (AiProjectSpec.Component component : screen.components) {
                names.add(component.name + " (" + component.type + ")");
            }
        }
        return names;
    }

    private static String eventKey(AiProjectSpec.Event event) {
        return "activity".equals(event.target) ? event.name : event.target + " › " + event.name;
    }

    private static Set<String> events(@Nullable AiProjectSpec.Screen screen) {
        return eventCode(screen).keySet();
    }

    private static Map<String, String> eventCode(@Nullable AiProjectSpec.Screen screen) {
        Map<String, String> code = new LinkedHashMap<>();
        if (screen != null) {
            for (AiProjectSpec.Event event : screen.events) {
                code.put(eventKey(event), event.code);
            }
        }
        return code;
    }

    private static Set<String> methods(@Nullable AiProjectSpec.Screen screen) {
        return methodCode(screen).keySet();
    }

    private static Map<String, String> methodCode(@Nullable AiProjectSpec.Screen screen) {
        Map<String, String> code = new LinkedHashMap<>();
        if (screen != null) {
            for (AiProjectSpec.Method method : screen.methods) {
                code.put("_" + method.name, method.code);
            }
        }
        return code;
    }

    private static Set<String> fields(@Nullable AiProjectSpec.Screen screen) {
        Set<String> names = new LinkedHashSet<>();
        if (screen != null) {
            for (AiProjectSpec.Field field : screen.variables) {
                names.add(field.name);
            }
            for (AiProjectSpec.Field field : screen.lists) {
                names.add(field.name);
            }
        }
        return names;
    }

    private static String normalize(@Nullable String code) {
        return Objects.toString(code, "").replaceAll("\\s+", " ").trim();
    }

    private static String names(List<String> names) {
        if (names.size() <= MAX_NAMES) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_NAMES)) + " and " + (names.size() - MAX_NAMES) + " more";
    }
}
