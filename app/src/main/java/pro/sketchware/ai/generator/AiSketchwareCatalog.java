package pro.sketchware.ai.generator;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.ComponentBean;
import com.besome.sketch.beans.ViewBean;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.Lx;
import a.a.a.mq;
import a.a.a.oq;
import mod.agus.jcoderz.beans.ViewBeans;
import mod.hilal.saif.components.ComponentsHandler;
import mod.jbk.util.LogUtil;
import pro.sketchware.utility.FileUtil;

/**
 * What this Sketchware Pro install really offers the AI: every component, including the user's
 * custom ones, every event with the variables Sketchware's generated method gives its code, the
 * Activity events, and the user's custom widgets.
 * <p>
 * Everything is read from the same definitions the editor uses ({@link oq}, {@link Lx},
 * {@link ComponentsHandler}), so the model is never told about something that doesn't exist, and
 * never misses something that does. That's what lets it use Sketchware's AdMob components or the
 * toolbar's options menu instead of rebuilding them by hand.
 */
public final class AiSketchwareCatalog {

    private static final String TAG = "AiSketchwareCatalog";
    private static final String LOGIC_MARKER = "/*__AI_LOGIC__*/";
    private static final String TARGET_PLACEHOLDER = "NAME";
    /**
     * Built-in component type IDs, see the <code>COMPONENT_TYPE_</code> constants of
     * {@link ComponentBean}. Gaps are IDs Sketchware doesn't use.
     */
    private static final int MAX_BUILT_IN_COMPONENT_ID = 31;
    /**
     * Activity events that aren't events the model can write: imports, and onCreate, which is the
     * screen's own "onCreate" code.
     */
    private static final Set<String> NOT_WRITABLE_ACTIVITY_EVENTS = Set.of("Import", "initializeLogic");
    /**
     * The spec's names for Sketchware type names that read badly.
     */
    private static final Map<String, String> SPEC_NAMES = Map.of("File", "SharedPreferences");
    /**
     * Components that only work once Firebase is set up for the project.
     */
    private static final Set<Integer> NEEDS_FIREBASE = Set.of(
            ComponentBean.COMPONENT_TYPE_FIREBASE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH,
            ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_PHONE,
            ComponentBean.COMPONENT_TYPE_FIREBASE_CLOUD_MESSAGE, ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN);
    private static final Set<Integer> ADMOB_COMPONENTS = Set.of(
            ComponentBean.COMPONENT_TYPE_INTERSTITIAL_AD, ComponentBean.COMPONENT_TYPE_REWARDED_VIDEO_AD);
    /**
     * Components whose first parameter means something, and what.
     */
    private static final Map<Integer, String> PARAMETERS = Map.of(
            ComponentBean.COMPONENT_TYPE_SHAREDPREF, "the preferences file name",
            ComponentBean.COMPONENT_TYPE_FILE_PICKER, "the MIME type to pick, e.g. \"image/*\"",
            ComponentBean.COMPONENT_TYPE_FIREBASE, "the database path",
            ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE, "the storage path");

    /**
     * The highest view type ID, see {@link ViewBeans}.
     */
    private static final int MAX_VIEW_TYPE = ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW;
    /**
     * The XML tag Sketchware's palette gives each widget that isn't a plain android.widget class,
     * from the <code>convert</code> of its palette icon (dev.aldi.sayuti.editor.view.palette and
     * com.besome.sketch.editor.view.palette). Sketchware detects the libraries these widgets need
     * from exactly these tags.
     */
    private static final Map<Integer, String> WIDGET_TAGS = Map.ofEntries(
            Map.entry(ViewBean.VIEW_TYPE_WIDGET_ADVIEW, "com.google.android.gms.ads.AdView"),
            Map.entry(ViewBean.VIEW_TYPE_WIDGET_MAPVIEW, "com.google.android.gms.maps.MapView"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_TABLAYOUT, "com.google.android.material.tabs.TabLayout"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER, "androidx.viewpager.widget.ViewPager"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_BOTTOMNAVIGATIONVIEW, "com.google.android.material.bottomnavigation.BottomNavigationView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_BADGEVIEW, "com.allenliu.badgeview.BadgeView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_PATTERNLOCKVIEW, "com.andrognito.patternlockview.PatternLockView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_WAVESIDEBAR, "com.sayuti.lib.WaveSideBar"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_CARDVIEW, "androidx.cardview.widget.CardView"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT, "com.google.android.material.appbar.CollapsingToolbarLayout"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT, "com.google.android.material.textfield.TextInputLayout"),
            Map.entry(ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT, "androidx.swiperefreshlayout.widget.SwipeRefreshLayout"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON, "com.google.android.material.button.MaterialButton"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_SIGNINBUTTON, "com.google.android.gms.common.SignInButton"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW, "de.hdodenhof.circleimageview.CircleImageView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_LOTTIEANIMATIONVIEW, "com.airbnb.lottie.LottieAnimationView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_YOUTUBEPLAYERVIEW, "com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_OTPVIEW, "affan.ahmad.otp.OTPView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_CODEVIEW, "br.tiagohm.codeview.CodeView"),
            Map.entry(ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW, "androidx.recyclerview.widget.RecyclerView"));
    /**
     * Widgets that only work after the user sets something up, see
     * {@link AiProjectBuilder}, which rejects them otherwise.
     */
    private static final Map<Integer, String> WIDGET_REQUIREMENTS = Map.of(
            ViewBean.VIEW_TYPE_WIDGET_MAPVIEW, "Needs Google Maps turned on with an API key in the project's Library settings; only use it if the user asks for a map",
            ViewBeans.VIEW_TYPE_WIDGET_SIGNINBUTTON, "Needs Firebase with Google login (FirebaseGoogleLogin component) set up; only use it if the user asks for Google sign-in",
            ViewBeans.VIEW_TYPE_WIDGET_BADGEVIEW, "Its library isn't bundled with Sketchware Pro; don't use it");
    /**
     * How a widget's custom rows work, for those Sketchware generates an adapter for.
     */
    private static final Map<Integer, String> ADAPTERS = Map.of(
            ViewBean.VIEW_TYPE_WIDGET_LISTVIEW, "Custom rows: tools:listitem=\"@layout/item\"; fill with NAME.setAdapter(new NameAdapter(listMap)); and onBindCustomView",
            ViewBeans.VIEW_TYPE_WIDGET_GRIDVIEW, "Custom cells: tools:listitem=\"@layout/item\" plus android:numColumns; fill with NAME.setAdapter(new NameAdapter(listMap)); and onBindCustomView",
            ViewBean.VIEW_TYPE_WIDGET_SPINNER, "Custom rows: tools:listitem=\"@layout/item\"; fill with NAME.setAdapter(new NameAdapter(listMap)); and onBindCustomView",
            ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW, "Custom rows: tools:listitem=\"@layout/item\"; in onCreate call NAME.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this)); (or new androidx.recyclerview.widget.GridLayoutManager(this, 2)) and NAME.setAdapter(new NameAdapter(listMap)); refresh with NAME.getAdapter().notifyDataSetChanged(); onBindCustomView fills each row",
            ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER, "Pages: tools:listitem=\"@layout/page\"; fill with NAME.setAdapter(new NameAdapter(getApplicationContext(), listMap)); one page per list entry, onBindCustomView fills each page");
    /**
     * Short how-to for widgets whose use isn't obvious from their events.
     */
    private static final Map<Integer, String> WIDGET_USAGE = Map.of(
            ViewBeans.VIEW_TYPE_LAYOUT_BOTTOMNAVIGATIONVIEW, "Give it a menu resource with icons, app:menu=\"@menu/ai_bottom_nav\" (items with android:id=\"@+id/nav_home\" and android:icon=\"@drawable/ic_home\"), or add items in onCreate: NAME.getMenu().add(0, 1, 0, \"Home\").setIcon(R.drawable.ic_home); handle onNavigationItemSelected",
            ViewBeans.VIEW_TYPE_LAYOUT_TABLAYOUT, "Add tabs with NAME.addTab(NAME.newTab().setText(\"One\")); or link a ViewPager with NAME.setupWithViewPager(pager)",
            ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT, "Wrap exactly one scrolling child; stop the spinner with NAME.setRefreshing(false)",
            ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT, "Put exactly one EditText inside it; set the hint on the TextInputLayout",
            ViewBeans.VIEW_TYPE_LAYOUT_RADIOGROUP, "Put RadioButtons inside it so only one can be checked",
            ViewBeans.VIEW_TYPE_WIDGET_LOTTIEANIMATIONVIEW, "Play an animation from a URL with NAME.setAnimationFromUrl(\"https://...json\"); NAME.playAnimation();",
            ViewBeans.VIEW_TYPE_WIDGET_VIDEOVIEW, "Play with NAME.setVideoURI(android.net.Uri.parse(url)); NAME.start();");

    private static final String CUSTOM_WIDGETS_FILE = "/.sketchware/resources/widgets/widgets.json";

    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?:public|protected)\\s+[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\{");
    private static final Pattern FINAL_DECLARATION = Pattern.compile("final\\s+([\\w<>,. ?\\[\\]]+?)\\s+(_\\w+)\\s*=");

    private final Map<String, ComponentInfo> components = new LinkedHashMap<>();
    private final List<EventInfo> activityEvents = new ArrayList<>();
    private final List<CustomWidget> customWidgets = new ArrayList<>();
    private final Map<Integer, WidgetInfo> widgets = new LinkedHashMap<>();

    private AiSketchwareCatalog() {
    }

    /**
     * Reads the catalog fresh, so components or widgets the user just added are included.
     */
    @NonNull
    public static AiSketchwareCatalog load() {
        AiSketchwareCatalog catalog = new AiSketchwareCatalog();
        catalog.readComponents();
        catalog.readActivityEvents();
        catalog.readCustomWidgets();
        catalog.readWidgets();
        return catalog;
    }

    private void readComponents() {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= MAX_BUILT_IN_COMPONENT_ID; id++) {
            ids.add(id);
        }
        ArrayList<ComponentBean> custom = new ArrayList<>();
        try {
            ComponentsHandler.add(custom);
        } catch (RuntimeException e) {
            LogUtil.e(TAG, "Couldn't read custom components", e);
        }
        Set<Integer> customIds = new LinkedHashSet<>();
        for (ComponentBean bean : custom) {
            if (!ids.contains(bean.type)) {
                ids.add(bean.type);
                customIds.add(bean.type);
            }
        }

        for (int id : ids) {
            String typeName;
            try {
                typeName = ComponentBean.getComponentTypeName(id);
            } catch (RuntimeException e) {
                continue;
            }
            if (typeName == null || typeName.isEmpty() || !typeName.matches("[A-Za-z]\\w*")) {
                continue;
            }
            String specName = SPEC_NAMES.getOrDefault(typeName, typeName);
            if (components.containsKey(specName)) {
                continue;
            }
            ComponentInfo info = new ComponentInfo(specName, typeName, id, customIds.contains(id));
            try {
                // The field Sketchware declares for it, e.g. "private MediaPlayer NAME;", made the
                // same way the Activity's code generator makes it
                String field = Lx.a(mq.a(id), TARGET_PLACEHOLDER, Lx.AccessModifier.PRIVATE, "", "", "").trim();
                int lineEnd = field.indexOf('\n');
                info.field = (lineEnd < 0 ? field : field.substring(0, lineEnd)).trim();
            } catch (RuntimeException e) {
                info.field = "";
            }
            try {
                for (String event : oq.getComponentEventsForClass(new ComponentBean(id).getClassInfo())) {
                    info.events.add(describeEvent(TARGET_PLACEHOLDER, event));
                }
            } catch (RuntimeException e) {
                LogUtil.e(TAG, "Couldn't read the events of component " + typeName, e);
            }
            components.put(specName, info);
        }
    }

    private void readActivityEvents() {
        for (String event : oq.getAllActivityEvents()) {
            if (!NOT_WRITABLE_ACTIVITY_EVENTS.contains(event)) {
                activityEvents.add(describeEvent("", event));
            }
        }
    }

    /**
     * Every view type of Sketchware's palette, with the XML tag Sketchware itself writes for it
     * and the events the logic editor offers for it.
     */
    private void readWidgets() {
        for (int type = 0; type <= MAX_VIEW_TYPE; type++) {
            if (type == ViewBean.VIEW_TYPE_WIDGET_FAB) {
                // Not a layout view: the screen option "fab"
                continue;
            }
            String name = ViewBean.getViewTypeName(type);
            if (name == null || name.isEmpty()) {
                continue;
            }
            String tag = WIDGET_TAGS.getOrDefault(type, "HScrollView".equals(name) ? "HorizontalScrollView" : name);
            WidgetInfo info = new WidgetInfo(type, displayName(name), tag);
            try {
                ViewBean bean = new ViewBean(TARGET_PLACEHOLDER, type);
                for (String event : oq.getEventsForClass(bean.getClassInfo())) {
                    String trimmed = event.trim();
                    if (!trimmed.isEmpty() && info.event(trimmed) == null) {
                        info.events.add(describeEvent(TARGET_PLACEHOLDER, trimmed));
                    }
                }
            } catch (RuntimeException e) {
                LogUtil.e(TAG, "Couldn't read the events of widget " + name, e);
            }
            widgets.put(type, info);
        }
    }

    private static String displayName(String typeName) {
        return switch (typeName) {
            case "HScrollView" -> "HorizontalScrollView";
            case "YoutubePlayerView" -> "YouTubePlayerView";
            default -> typeName;
        };
    }

    private void readCustomWidgets() {
        String path = FileUtil.getExternalStorageDir() + CUSTOM_WIDGETS_FILE;
        if (!new File(path).isFile()) {
            return;
        }
        try {
            List<Map<String, Object>> widgets = new Gson().fromJson(FileUtil.readFile(path),
                    new TypeToken<List<Map<String, Object>>>() {
                    }.getType());
            if (widgets == null) {
                return;
            }
            for (Map<String, Object> widget : widgets) {
                Object className = widget.get("Class");
                Object title = widget.get("title");
                if (className instanceof String && !((String) className).trim().isEmpty()) {
                    Object inject = widget.get("inject");
                    customWidgets.add(new CustomWidget(title instanceof String ? (String) title : (String) className,
                            ((String) className).trim(), inject instanceof String ? (String) inject : ""));
                }
            }
        } catch (RuntimeException e) {
            LogUtil.e(TAG, "Couldn't read custom widgets", e);
        }
    }

    /**
     * Works out from Sketchware's own template what an event's code can use, e.g.
     * <code>int _errorCode, String _errorMessage</code>, and whether the template already returns.
     */
    @NonNull
    static EventInfo describeEvent(@NonNull String target, @NonNull String event) {
        String code;
        try {
            code = Lx.getEventCode(target, event, LOGIC_MARKER);
        } catch (RuntimeException e) {
            return new EventInfo(event, "", false, "");
        }
        int marker = code == null ? -1 : code.indexOf(LOGIC_MARKER);
        if (marker < 0) {
            return new EventInfo(event, "", false, "");
        }
        String before = code.substring(0, marker);
        String after = code.substring(marker + LOGIC_MARKER.length());

        List<String> variables = new ArrayList<>();
        int bodyStart = 0;
        Matcher declaration = METHOD_DECLARATION.matcher(before);
        while (declaration.find()) {
            bodyStart = declaration.end();
            variables.clear();
            for (String parameter : declaration.group(2).split(",")) {
                String trimmed = parameter.trim().replaceAll("\\s+", " ");
                if (!trimmed.isEmpty()) {
                    variables.add(trimmed);
                }
            }
        }
        String body = before.substring(bodyStart);
        Matcher finals = FINAL_DECLARATION.matcher(body);
        while (finals.find()) {
            variables.add(finals.group(1).trim() + " " + finals.group(2));
        }
        // What the template already does before the code, e.g. storing a loaded ad
        StringBuilder alreadyDone = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            String statement = line.trim();
            if (!statement.isEmpty() && !statement.startsWith("final ") && !statement.startsWith("super.")
                    && !statement.startsWith("@") && !statement.equals("{") && !statement.equals("}")) {
                if (alreadyDone.length() > 0) {
                    alreadyDone.append(' ');
                }
                alreadyDone.append(statement);
            }
        }
        boolean returnsAfter = Pattern.compile("\\breturn\\b").matcher(after).find();
        return new EventInfo(event, String.join(", ", variables), returnsAfter, alreadyDone.toString());
    }

    /**
     * @return Spec component type name to Sketchware's type name, e.g. "SharedPreferences" to
     * "File".
     */
    @NonNull
    public Map<String, String> componentTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        for (ComponentInfo info : components.values()) {
            types.put(info.specName, info.typeName);
        }
        return types;
    }

    @Nullable
    public ComponentInfo component(@Nullable String specName) {
        return specName == null ? null : components.get(specName);
    }

    @Nullable
    public ComponentInfo componentByTypeId(int typeId) {
        for (ComponentInfo info : components.values()) {
            if (info.typeId == typeId) {
                return info;
            }
        }
        return null;
    }

    /**
     * @return The widget of a view type, or null for types Sketchware doesn't have.
     */
    @Nullable
    public WidgetInfo widget(int viewType) {
        return widgets.get(viewType);
    }

    /**
     * @param tag An XML tag as written in a layout, e.g. "androidx.recyclerview.widget.RecyclerView"
     *            or "RecyclerView".
     */
    @Nullable
    public WidgetInfo widgetByTag(@NonNull String tag) {
        String simple = tag.substring(tag.lastIndexOf('.') + 1);
        for (WidgetInfo widget : widgets.values()) {
            String widgetSimple = widget.tag.substring(widget.tag.lastIndexOf('.') + 1);
            if (widget.tag.equals(tag) || widgetSimple.equals(simple) || widget.name.equals(simple)) {
                return widget;
            }
        }
        return null;
    }

    /**
     * @return What a widget needs that only the user can set up, or null if it just works.
     */
    @Nullable
    public static String widgetRequirement(int viewType) {
        return WIDGET_REQUIREMENTS.get(viewType);
    }

    @NonNull
    public Set<String> activityEventNames() {
        Set<String> names = new LinkedHashSet<>();
        for (EventInfo event : activityEvents) {
            names.add(event.name);
        }
        return names;
    }

    @Nullable
    public EventInfo activityEvent(@NonNull String name) {
        for (EventInfo event : activityEvents) {
            if (event.name.equals(name)) {
                return event;
            }
        }
        return null;
    }

    /**
     * @return Whether the type is one of Sketchware's AdMob components.
     */
    public static boolean isAdmobComponent(int typeId) {
        return ADMOB_COMPONENTS.contains(typeId);
    }

    public static boolean needsFirebase(int typeId) {
        return NEEDS_FIREBASE.contains(typeId);
    }

    /**
     * @return A short list of what can be built, for planning, where the full catalog would only
     * cost tokens.
     */
    @NonNull
    public String capabilitySummary() {
        List<String> widgetNames = new ArrayList<>();
        for (WidgetInfo widget : widgets.values()) {
            if (!WIDGET_REQUIREMENTS.containsKey(widget.type)) {
                widgetNames.add(widget.name);
            }
        }
        List<String> componentNames = new ArrayList<>();
        for (ComponentInfo component : components.values()) {
            if (!NEEDS_FIREBASE.contains(component.typeId)) {
                componentNames.add(component.specName);
            }
        }
        return "Widgets: " + String.join(", ", widgetNames) + ", floating action button, toolbar with a three-dots menu.\n"
                + "Components: " + String.join(", ", componentNames) + ".\n"
                + "Also: about 2,200 Material icons in 5 styles and any color, rounded and gradient backgrounds, "
                + "ripples, custom colors and menus, several screens, lists of data, data saved on the phone, AdMob ads. "
                + "Firebase and Google Maps only if the user sets them up.";
    }

    /**
     * @return The part of the system prompt that lists everything above.
     */
    @NonNull
    public String promptSection() {
        StringBuilder out = new StringBuilder();
        out.append("# Use what Sketchware Pro already has (most important rule)\n")
                .append("Before writing any code, check this catalog. Sketchware Pro already has the components, widgets, ")
                .append("events and settings below; ALWAYS use them instead of writing your own replacement. Never ")
                .append("re-implement something listed here with hand-written code, custom layouts or other libraries. ")
                .append("Everything listed here is real; anything not listed doesn't exist in this install.\n\n");

        out.append("## Components (\"components\" of a screen)\n")
                .append("Each becomes a ready field named after the component. Events of a component use the ")
                .append("component's name as \"target\". In the variables below, ").append(TARGET_PLACEHOLDER)
                .append(" stands for the component's name.\n");
        for (ComponentInfo info : components.values()) {
            out.append("- ").append(info.specName);
            if (info.custom) {
                out.append(" (custom component of this install)");
            }
            if (!info.field.isEmpty()) {
                out.append(". Field: ").append(info.field);
            }
            String parameter = PARAMETERS.get(info.typeId);
            if (parameter != null) {
                out.append(". \"param\": ").append(parameter);
            }
            if (NEEDS_FIREBASE.contains(info.typeId)) {
                out.append(". Needs Firebase set up in the project's Library settings; only use it if the user asks for Firebase");
            }
            if (!info.events.isEmpty()) {
                out.append(". Events: ");
                appendEvents(out, info.events);
            }
            out.append('\n');
        }

        out.append("\n## Activity events (target \"activity\")\n");
        appendEvents(out, activityEvents);
        out.append('\n');

        out.append("""

                ## Toolbar options menu (three dots)
                - The toolbar (screen option "toolbar": true) is Android's real action bar. For menu items, \
                a three-dots overflow menu or toolbar icons, use the activity events onCreateOptionsMenu and \
                onOptionsItemSelected. NEVER draw a fake toolbar, menu button or popup layout yourself.
                - onCreateOptionsMenu: menu.add(0, 1, 0, "Settings"); one line per item, with a unique int ID. \
                Show an item as an icon or text on the bar with \
                menu.add(0, 2, 0, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); \
                the rest go into the three-dots menu automatically.
                - onOptionsItemSelected: compare the int _id with your IDs: if (_id == 1) { ... }
                - Both events already end with their own return statement; never write return in them.
                - For a long-press menu on a view use onCreateContextMenu / onContextItemSelected the same way.

                ## AdMob ads (banner, interstitial, rewarded)
                Sketchware Pro has AdMob built in. Use it exactly like this, never write your own ad setup:
                - Turn AdMob on with "admob": {"enabled": true} at the top level of the JSON. Sketchware then \
                adds the ads library, the internet permissions, the AdActivity, MobileAds.initialize and the \
                registered test devices by itself; never write MobileAds.initialize or RequestConfiguration code. \
                Optional: "testDevices": ["device id"] adds test devices to the project's AdMob settings.
                - Ad unit IDs come from the project's AdMob settings. Debug builds automatically use Google's \
                test ad units. Never hard-code ad unit or App IDs in code or layouts.
                - Banner: the AdView widget in the layout, \
                <com.google.android.gms.ads.AdView android:id="@+id/banner_ad" android:layout_width="wrap_content" \
                android:layout_height="wrap_content" app:adSize="BANNER" /> (adSize BANNER, LARGE_BANNER, \
                MEDIUM_RECTANGLE, FULL_BANNER, LEADERBOARD or SMART_BANNER; no adUnitId attribute). Load it in \
                onCreate with banner_ad.loadAd(new com.google.android.gms.ads.AdRequest.Builder().build()); \
                Its events: onBannerAdLoaded, onBannerAdFailedToLoad, onBannerAdOpened, onBannerAdClicked, onBannerAdClosed.
                - Interstitial: an InterstitialAd component, e.g. named "interstitial". Load it with \
                com.google.android.gms.ads.interstitial.InterstitialAd.load(MainActivity.this, _ad_unit_id, \
                new com.google.android.gms.ads.AdRequest.Builder().build(), _interstitial_interstitial_ad_load_callback); \
                (Sketchware declares _ad_unit_id and the _<name>_interstitial_ad_load_callback field). Show it with \
                if (interstitial != null) { interstitial.show(MainActivity.this); } \
                onInterstitialAdLoaded already stores the ad in the field; load the next one in \
                onAdDismissedFullScreenContent.
                - Rewarded: a RewardedVideoAd component, e.g. named "rewarded". Load it with \
                com.google.android.gms.ads.rewarded.RewardedAd.load(MainActivity.this, _reward_ad_unit_id, \
                new com.google.android.gms.ads.AdRequest.Builder().build(), _rewarded_rewarded_ad_load_callback); \
                show it with if (rewarded != null) { rewarded.show(MainActivity.this, _rewarded_on_user_earned_reward_listener); } \
                and give the reward in its onUserEarnedReward event.
                - Use the screen's own class name instead of MainActivity.
                """);

        out.append("\n## Widgets (XML tags for layouts)\n")
                .append("These are all the views Sketchware Pro has. Write each with exactly the tag given; every one ")
                .append("becomes a real widget of Sketchware's view editor and a ready field named after its android:id. ")
                .append("Use the most fitting one: RecyclerView or GridView for lists and grids, ViewPager with TabLayout ")
                .append("for tabs, BottomNavigationView for bottom tabs, SwipeRefreshLayout for pull-to-refresh, ")
                .append("TextInputLayout around an EditText for material text fields, MaterialButton, CardView, and so on. ")
                .append("Events use the view's ID as \"target\"; NAME stands for that ID.\n");
        for (WidgetInfo widget : widgets.values()) {
            out.append("- ").append(widget.name).append(": <").append(widget.tag).append(">");
            String requirement = WIDGET_REQUIREMENTS.get(widget.type);
            if (requirement != null) {
                out.append(". ").append(requirement);
            }
            String adapter = ADAPTERS.get(widget.type);
            if (adapter != null) {
                out.append(". ").append(adapter);
            }
            String usage = WIDGET_USAGE.get(widget.type);
            if (usage != null) {
                out.append(". ").append(usage);
            }
            if (!widget.events.isEmpty()) {
                out.append(". Events: ");
                appendEvents(out, widget.events);
            }
            out.append('\n');
        }
        out.append("- Floating action button: not a layout view; use the screen option \"fab\" and target \"_fab\".\n");

        if (!customWidgets.isEmpty()) {
            out.append("\n## Custom widgets of this install\n")
                    .append("Use them in layouts by their class as the XML tag when the user's request fits them:\n");
            for (CustomWidget widget : customWidgets) {
                out.append("- ").append(widget.title).append(": <").append(widget.className).append(" />");
                if (!widget.inject.trim().isEmpty()) {
                    out.append(" with ").append(widget.inject.replaceAll("\\s+", " ").trim());
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void appendEvents(StringBuilder out, List<EventInfo> events) {
        for (int i = 0; i < events.size(); i++) {
            EventInfo event = events.get(i);
            if (i > 0) {
                out.append("; ");
            }
            out.append(event.name);
            List<String> notes = new ArrayList<>();
            if (!event.variables.isEmpty()) {
                notes.add("uses " + event.variables);
            }
            if (event.returnsAfter) {
                notes.add("returns by itself");
            }
            if (!event.alreadyDone.isEmpty() && event.alreadyDone.length() <= 160) {
                notes.add("already runs " + event.alreadyDone);
            }
            if (!notes.isEmpty()) {
                out.append(" (").append(String.join(", ", notes)).append(')');
            }
        }
    }

    public static final class ComponentInfo {
        public final String specName;
        public final String typeName;
        public final int typeId;
        public final boolean custom;
        public final List<EventInfo> events = new ArrayList<>();
        /**
         * The field Sketchware declares, e.g. "private MediaPlayer NAME;", or empty if unknown.
         */
        String field = "";

        ComponentInfo(String specName, String typeName, int typeId, boolean custom) {
            this.specName = specName;
            this.typeName = typeName;
            this.typeId = typeId;
            this.custom = custom;
        }

        @NonNull
        public List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (EventInfo event : events) {
                names.add(event.name);
            }
            return names;
        }

        @Nullable
        public EventInfo event(@NonNull String name) {
            for (EventInfo event : events) {
                if (event.name.equals(name)) {
                    return event;
                }
            }
            return null;
        }
    }

    public static final class EventInfo {
        public final String name;
        /**
         * What the event's code can use, e.g. "int _errorCode, String _errorMessage".
         */
        public final String variables;
        /**
         * Whether Sketchware's template returns after the event's code, so the code must not.
         */
        public final boolean returnsAfter;
        /**
         * Statements the template runs before the event's code.
         */
        public final String alreadyDone;

        EventInfo(String name, String variables, boolean returnsAfter, String alreadyDone) {
            this.name = name;
            this.variables = variables;
            this.returnsAfter = returnsAfter;
            this.alreadyDone = alreadyDone;
        }
    }

    public static final class WidgetInfo {
        public final int type;
        public final String name;
        /**
         * The XML tag, e.g. "androidx.recyclerview.widget.RecyclerView".
         */
        public final String tag;
        public final List<EventInfo> events = new ArrayList<>();

        WidgetInfo(int type, String name, String tag) {
            this.type = type;
            this.name = name;
            this.tag = tag;
        }

        @Nullable
        public EventInfo event(@NonNull String name) {
            for (EventInfo event : events) {
                if (event.name.equals(name)) {
                    return event;
                }
            }
            return null;
        }
    }

    static final class CustomWidget {
        final String title;
        final String className;
        final String inject;

        CustomWidget(String title, String className, String inject) {
            this.title = title;
            this.className = className;
            this.inject = inject;
        }
    }
}
