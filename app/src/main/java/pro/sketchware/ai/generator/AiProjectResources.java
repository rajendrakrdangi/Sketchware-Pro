package pro.sketchware.ai.generator;

import android.content.Context;

import androidx.annotation.NonNull;

import com.besome.sketch.beans.ProjectResourceBean;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.KB;
import a.a.a.kC;
import a.a.a.wq;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.SvgUtils;

/**
 * Puts the icons and resource files of an answer into a project the way Sketchware's own managers
 * do: icons from the built-in icon pack become vector drawables in the image manager (like Import
 * icon does), resource files go to the Resource manager's folder. Reads them back for edits.
 */
final class AiProjectResources {

    private static final String ICON_PACK_ASSET = "icons" + File.separator + "icon_pack.zip";
    private static final int MAX_SUGGESTIONS = 8;
    private static final Pattern VALUES_ENTRY = Pattern.compile("<(color|string|dimen|integer|bool|style)\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"");

    private final Context context;
    private final FilePathUtil paths = new FilePathUtil();

    AiProjectResources(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // ---------------------------------------------------------------------------------------
    // Icons
    // ---------------------------------------------------------------------------------------

    /**
     * @return The folder with one folder per icon, extracting the icon pack first if needed.
     */
    private File iconPack() {
        File svg = new File(wq.getExtractedIconPackStoreLocation(), "svg");
        if (!svg.isDirectory()) {
            KB.a(context, ICON_PACK_ASSET, wq.getExtractedIconPackStoreLocation());
        }
        return svg;
    }

    /**
     * Checks that every icon exists in the pack in its style, and that its name doesn't belong to
     * one of the user's own pictures. Nothing is written.
     */
    void checkIcons(@NonNull AiProjectSpec spec, @androidx.annotation.Nullable String sc_id) throws AiSpecException {
        if (spec.icons.isEmpty()) {
            return;
        }
        File pack = iconPack();
        List<ProjectResourceBean> images = sc_id == null ? List.of() : loadImages(sc_id).b;
        for (AiProjectSpec.Icon icon : spec.icons) {
            File svg = new File(new File(pack, icon.icon), icon.style + ".svg");
            if (!svg.isFile()) {
                throw new AiSpecException("The icon pack has no icon \"" + icon.icon + "\" (used for \"" + icon.name + "\"). "
                        + "Close matches: " + suggestions(pack, icon.icon) + ". Use one of those, or another Material icon name.");
            }
            for (ProjectResourceBean image : images) {
                if (icon.name.equals(image.resName) && image.resFullName != null && !image.resFullName.endsWith(".xml")) {
                    throw new AiSpecException("The icon name \"" + icon.name + "\" is already used by one of the project's own pictures. "
                            + "Use that picture as @drawable/" + icon.name + ", or give the icon another name.");
                }
            }
        }
    }

    private static String suggestions(File pack, String wanted) {
        String[] names = pack.list();
        if (names == null) {
            return "none";
        }
        List<String> matches = new ArrayList<>();
        String[] parts = wanted.split("_");
        for (String name : names) {
            for (String part : parts) {
                if (part.length() >= 3 && name.contains(part)) {
                    matches.add(name);
                    break;
                }
            }
            if (matches.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return matches.isEmpty() ? "none" : String.join(", ", matches);
    }

    /**
     * Adds the icons to the project's images as colored vector drawables, replacing an earlier icon
     * of the same name.
     */
    void writeIcons(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        if (spec.icons.isEmpty()) {
            return;
        }
        File pack = iconPack();
        kC images = loadImages(sc_id);
        String imagesDirectory = images.l();
        SvgUtils svgUtils = new SvgUtils(context);
        for (AiProjectSpec.Icon icon : spec.icons) {
            try {
                File source = new File(new File(pack, icon.icon), icon.style + ".svg");
                File svg = new File(paths.getSvgFullPath(sc_id, icon.name));
                Files.createDirectories(svg.getParentFile().toPath());
                Files.copy(source.toPath(), svg.toPath(), StandardCopyOption.REPLACE_EXISTING);
                // Writes <images>/<name>.xml, exactly like importing an icon in the image manager
                svgUtils.convert(svg.getAbsolutePath(), imagesDirectory, icon.color);
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't add the icon " + icon.name, e);
            }
            images.b.removeIf(image -> icon.name.equals(image.resName));
            images.b.add(new ProjectResourceBean(ProjectResourceBean.PROJECT_RES_TYPE_FILE, icon.name, icon.name + ".xml"));
        }
        images.x();
    }

    private static kC loadImages(String sc_id) {
        kC resources = new kC(sc_id);
        resources.s();
        if (resources.b == null) {
            resources.b = new ArrayList<>();
        }
        return resources;
    }

    // ---------------------------------------------------------------------------------------
    // Resource files
    // ---------------------------------------------------------------------------------------

    /**
     * Makes the AI's resource files exactly the spec's: new ones are written, changed ones
     * replaced, and ones the AI made earlier but the spec dropped are deleted. Files the user made
     * are never touched.
     */
    void writeResources(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File root = new File(paths.getPathResource(sc_id));
        Set<String> wanted = new LinkedHashSet<>();
        for (AiProjectSpec.ResourceFile file : spec.resources) {
            wanted.add(file.path);
        }
        for (String folder : AiResourceRules.FOLDERS) {
            File[] files = new File(root, folder).listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.getName().startsWith(AiResourceRules.AI_PREFIX) && !wanted.contains(folder + "/" + file.getName())) {
                    file.delete();
                }
            }
        }
        for (AiProjectSpec.ResourceFile file : spec.resources) {
            File target = new File(root, file.path);
            try {
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), file.content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't write the resource file " + file.path, e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reading, for edits
    // ---------------------------------------------------------------------------------------

    /**
     * Fills in what the project already has: its images, the AI's own resource files to keep or
     * change, and the names of the user's resources.
     */
    void read(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        kC resources = loadImages(sc_id);
        for (ProjectResourceBean image : resources.b) {
            if (image.resName != null) {
                spec.projectImages.add(image.resName);
            }
        }
        // Sounds (R.raw.name) and fonts (assets/fonts/name.ttf) the user added in their managers
        if (resources.c != null) {
            for (ProjectResourceBean sound : resources.c) {
                if (sound.resName != null) {
                    spec.projectSounds.add(sound.resName);
                }
            }
        }
        if (resources.d != null) {
            for (ProjectResourceBean font : resources.d) {
                if (font.resName != null) {
                    spec.projectFonts.add(font.resName);
                }
            }
        }
        File root = new File(paths.getPathResource(sc_id));
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null) {
            return;
        }
        for (File folder : folders) {
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".xml"));
            if (files == null) {
                continue;
            }
            String type = folder.getName().split("-")[0];
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - ".xml".length());
                String content;
                try {
                    content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                if (name.startsWith(AiResourceRules.AI_PREFIX) && AiResourceRules.FOLDERS.contains(folder.getName())) {
                    AiProjectSpec.ResourceFile resource = new AiProjectSpec.ResourceFile();
                    resource.path = folder.getName() + "/" + file.getName();
                    resource.content = content;
                    spec.resources.add(resource);
                } else if (type.equals("values")) {
                    Matcher entry = VALUES_ENTRY.matcher(content);
                    while (entry.find()) {
                        // The AI's own texts are read with their translations, see AiStrings
                        if (entry.group(1).equals("string") && entry.group(2).startsWith(AiResourceRules.AI_PREFIX)) {
                            continue;
                        }
                        String resource = entry.group(1) + "/" + entry.group(2);
                        if (!spec.projectResources.contains(resource)) {
                            spec.projectResources.add(resource);
                        }
                    }
                } else {
                    spec.projectResources.add(type.toLowerCase(Locale.ROOT) + "/" + name);
                }
            }
        }
    }
}
