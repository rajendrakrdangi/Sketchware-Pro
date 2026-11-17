package pro.sketchware.ai.generator;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * The app description a model answers with, as documented to it in {@link AiProjectPrompt}.
 * <p>
 * Instances come out of {@link AiResponseParser}, which guarantees that no list is null, that all
 * names are valid identifiers, and that the first screen is the launcher screen.
 */
public class AiProjectSpec {

    /**
     * Set instead of everything else when the message wasn't a request for an app, e.g. a
     * greeting: the model's answer to show in the chat.
     */
    @SerializedName("reply")
    public String reply;

    @SerializedName("appName")
    public String appName;

    @SerializedName("projectName")
    public String projectName;

    @SerializedName("packageName")
    public String packageName;

    @SerializedName("colors")
    public Colors colors;

    @SerializedName("permissions")
    public List<String> permissions = new ArrayList<>();

    @SerializedName("screens")
    public List<Screen> screens = new ArrayList<>();

    @SerializedName("itemLayouts")
    public List<ItemLayout> itemLayouts = new ArrayList<>();

    /**
     * Sketchware's own AdMob setup, or null to leave the project's AdMob settings alone.
     */
    public Admob admob;

    /**
     * The model's own short account of what it built or changed, shown to the user.
     */
    public String summary;

    /**
     * What the user can now see and do in the app, in everyday words, for the report.
     */
    public List<String> highlights = new ArrayList<>();

    /**
     * Icons from Sketchware's built-in icon pack, added to the project's images as vector
     * drawables and used as <code>@drawable/name</code> or <code>R.drawable.name</code>.
     */
    public List<Icon> icons = new ArrayList<>();

    /**
     * Android resource files the AI owns, e.g. rounded backgrounds, colors and menus. Their file
     * names start with "ai_"; the user's own resource files are never touched.
     */
    public List<ResourceFile> resources = new ArrayList<>();

    /**
     * Read-only: the names of the images the project already has, which layouts and code may use.
     */
    public List<String> projectImages = new ArrayList<>();

    /**
     * Read-only: the user's own resources, as "type/name", e.g. "color/brand_blue".
     */
    public List<String> projectResources = new ArrayList<>();

    /**
     * Helper classes the AI owns in the project's Java folder, e.g. a data model or a store.
     */
    public List<JavaClass> javaClasses = new ArrayList<>();

    /**
     * Text data files the AI owns in the app's assets, e.g. starter questions as JSON. Their file
     * names start with "ai_"; the user's own assets are never touched.
     */
    public List<AssetFile> assets = new ArrayList<>();

    /**
     * Read-only: the project's own asset files, as paths inside assets, e.g. "data/words.txt".
     */
    public List<String> projectAssets = new ArrayList<>();

    /**
     * Read-only: the sounds of the project's sound manager, used as <code>R.raw.name</code>.
     */
    public List<String> projectSounds = new ArrayList<>();

    /**
     * Read-only: the fonts of the project's font manager, used from
     * <code>"fonts/name.ttf"</code> in the assets.
     */
    public List<String> projectFonts = new ArrayList<>();

    /**
     * The app's texts in its default language, by name ("ai_..."), for apps in more than one
     * language: layouts use <code>@string/name</code> and code <code>getString(R.string.name)</code>.
     */
    public java.util.Map<String, String> strings = new java.util.LinkedHashMap<>();

    /**
     * The same texts in other languages, by language code, e.g. "hi" or "pt-rBR". The phone's
     * language picks which one the app shows.
     */
    public java.util.Map<String, java.util.Map<String, String>> translations = new java.util.LinkedHashMap<>();

    public static class AssetFile {
        /**
         * Path inside assets, e.g. "ai_questions.json".
         */
        public String path;
        public String content;
    }

    /**
     * Read-only: the names of the project's own Java classes in its Java folder.
     */
    public List<String> projectJavaClasses = new ArrayList<>();

    public static class JavaClass {
        /**
         * The class name, e.g. "QuizStore".
         */
        public String name;
        /**
         * The whole class, without a package line.
         */
        public String code;
    }

    public static class Icon {
        /**
         * The drawable's name in the project, e.g. "ic_add".
         */
        public String name;
        /**
         * The icon pack's name for it, e.g. "add".
         */
        public String icon;
        /**
         * baseline, outline, round, sharp or twotone.
         */
        public String style;
        /**
         * A hex color like #FFFFFF.
         */
        public String color;
    }

    public static class ResourceFile {
        /**
         * Folder and file, e.g. "drawable/ai_card_bg.xml".
         */
        public String path;
        public String content;
    }

    public static class Admob {
        public boolean enabled;
        public List<String> testDevices = new ArrayList<>();
    }

    /**
     * @return Whether this is an answer in words rather than an app.
     */
    public boolean isReply() {
        return reply != null && !reply.trim().isEmpty() && (screens == null || screens.isEmpty());
    }

    public static class Colors {
        @SerializedName("colorPrimary")
        public String colorPrimary;

        @SerializedName("colorPrimaryDark")
        public String colorPrimaryDark;

        @SerializedName("colorAccent")
        public String colorAccent;
    }

    /**
     * One Activity: its layout, its fields and the code of its events.
     */
    public static class Screen {
        @SerializedName("name")
        public String name;

        @SerializedName("toolbar")
        public boolean toolbar;

        @SerializedName("fab")
        public boolean fab;

        @SerializedName("layout")
        public String layout;

        @SerializedName("variables")
        public List<Field> variables = new ArrayList<>();

        @SerializedName("lists")
        public List<Field> lists = new ArrayList<>();

        @SerializedName("components")
        public List<Component> components = new ArrayList<>();

        @SerializedName("onCreate")
        public String onCreate;

        @SerializedName("events")
        public List<Event> events = new ArrayList<>();

        @SerializedName("methods")
        public List<Method> methods = new ArrayList<>();
    }

    /**
     * A typed name: a variable, a list, or a method parameter.
     */
    public static class Field {
        @SerializedName("type")
        public String type;

        @SerializedName("name")
        public String name;
    }

    public static class Component {
        @SerializedName("type")
        public String type;

        @SerializedName("name")
        public String name;

        @SerializedName("param")
        public String param;
    }

    public static class Event {
        /**
         * A view ID, {@link AiProjectPrompt#TARGET_FAB} or {@link AiProjectPrompt#TARGET_ACTIVITY}.
         */
        @SerializedName("target")
        public String target;

        @SerializedName("name")
        public String name;

        @SerializedName("code")
        public String code;
    }

    public static class Method {
        @SerializedName("name")
        public String name;

        @SerializedName("params")
        public List<Field> params = new ArrayList<>();

        @SerializedName("code")
        public String code;
    }

    /**
     * The layout of one row of a list.
     */
    public static class ItemLayout {
        @SerializedName("name")
        public String name;

        @SerializedName("layout")
        public String layout;
    }
}
