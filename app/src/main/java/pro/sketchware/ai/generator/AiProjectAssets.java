package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import pro.sketchware.utility.FilePathUtil;

/**
 * Text data files the AI adds to a project's assets (what "Assets manager" shows), e.g. a quiz's
 * starter questions as JSON that the app reads with <code>getAssets().open("ai_questions.json")</code>.
 * <p>
 * The AI owns only files whose names start with "ai_"; the user's own assets are listed for it to
 * use but never changed.
 */
final class AiProjectAssets {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("json", "txt", "csv", "html", "md", "xml");
    private static final int MAX_LISTED = 100;

    private AiProjectAssets() {
    }

    /**
     * Makes the AI's asset files exactly the spec's.
     */
    static void write(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File root = new File(new FilePathUtil().getPathAssets(sc_id));
        Set<String> wanted = new LinkedHashSet<>();
        for (AiProjectSpec.AssetFile file : spec.assets) {
            wanted.add(file.path);
        }
        for (String path : list(root)) {
            if (isOwn(path) && !wanted.contains(path)) {
                new File(root, path).delete();
            }
        }
        for (AiProjectSpec.AssetFile file : spec.assets) {
            File target = new File(root, file.path);
            try {
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), file.content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't write the asset file " + file.path, e);
            }
        }
    }

    /**
     * Fills in the AI's own asset files, to keep or change, and the paths of the user's.
     */
    static void read(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File root = new File(new FilePathUtil().getPathAssets(sc_id));
        for (String path : list(root)) {
            if (isOwn(path)) {
                try {
                    String content = new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8);
                    if (content.length() <= AiResourceRules.MAX_ASSET_CHARS) {
                        AiProjectSpec.AssetFile file = new AiProjectSpec.AssetFile();
                        file.path = path;
                        file.content = content;
                        spec.assets.add(file);
                        continue;
                    }
                } catch (IOException ignored) {
                }
            }
            if (spec.projectAssets.size() < MAX_LISTED) {
                spec.projectAssets.add(path);
            }
        }
    }

    private static boolean isOwn(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return name.startsWith(AiResourceRules.AI_PREFIX) && dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * @return Every file under the assets folder, as a path with "/".
     */
    private static List<String> list(File root) {
        List<String> paths = new ArrayList<>();
        collect(root, "", paths);
        return paths;
    }

    private static void collect(File folder, String prefix, List<String> paths) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String path = prefix + file.getName();
            if (file.isDirectory()) {
                collect(file, path + "/", paths);
            } else {
                paths.add(path);
            }
        }
    }
}
