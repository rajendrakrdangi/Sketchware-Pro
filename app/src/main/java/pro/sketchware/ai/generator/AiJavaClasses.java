package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.ProjectFileBean;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import pro.sketchware.utility.FilePathUtil;

/**
 * Helper classes the AI adds to a project, e.g. a data model or a class that stores quiz results,
 * kept in the project's Java folder (<code>files/java</code>, what "Java manager" shows). Sketchware
 * compiles them together with the screens, and the compile check checks them too.
 * <p>
 * Each one starts with {@link #MARKER}, so the AI only ever changes or removes classes it wrote;
 * the user's own Java files are never touched.
 */
public final class AiJavaClasses {

    static final String MARKER = "// Created by RKD AI. RKD AI may change this file when it updates the app.";
    static final int MAX_CLASSES = 8;

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Z][A-Za-z0-9_]*");
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;\\s*\\n?");
    /**
     * Classes Sketchware itself adds to every app.
     */
    private static final Set<String> RESERVED = Set.of("SketchwareUtil", "FileUtil", "RequestNetwork",
            "RequestNetworkController", "DebugActivity", "SketchApplication", "SketchLogger", "BuildConfig", "R");

    private AiJavaClasses() {
    }

    /**
     * Tidies and checks the helper classes of an answer.
     */
    static void validate(@NonNull AiProjectSpec spec) throws AiSpecException {
        List<AiProjectSpec.JavaClass> classes = new ArrayList<>();
        if (spec.javaClasses != null) {
            for (AiProjectSpec.JavaClass javaClass : spec.javaClasses) {
                if (javaClass != null) {
                    classes.add(javaClass);
                }
            }
        }
        spec.javaClasses = classes;
        if (spec.projectJavaClasses == null) {
            spec.projectJavaClasses = new ArrayList<>();
        }
        if (classes.size() > MAX_CLASSES) {
            throw new AiSpecException("Use at most " + MAX_CLASSES + " helper classes in \"javaClasses\"; put small helpers in the screens' methods instead.");
        }
        Set<String> screens = new LinkedHashSet<>();
        for (AiProjectSpec.Screen screen : spec.screens) {
            if (screen.name != null) {
                screens.add(ProjectFileBean.getActivityName(screen.name));
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (AiProjectSpec.JavaClass javaClass : classes) {
            javaClass.name = javaClass.name == null ? "" : javaClass.name.trim();
            if (javaClass.name.endsWith(".java")) {
                javaClass.name = javaClass.name.substring(0, javaClass.name.length() - ".java".length());
            }
            if (!CLASS_NAME.matcher(javaClass.name).matches()) {
                throw new AiSpecException("The helper class name \"" + javaClass.name + "\" is invalid. Use a Java class name like \"QuizStore\".");
            }
            if (!names.add(javaClass.name)) {
                throw new AiSpecException("The helper class \"" + javaClass.name + "\" is defined twice.");
            }
            if (screens.contains(javaClass.name)) {
                throw new AiSpecException("The helper class \"" + javaClass.name + "\" has the name of a screen's activity, which would replace that screen. "
                        + "Put screen code in the screen, and give helper classes other names.");
            }
            if (RESERVED.contains(javaClass.name) || spec.projectJavaClasses.contains(javaClass.name)) {
                throw new AiSpecException("The helper class name \"" + javaClass.name + "\" is already taken"
                        + (RESERVED.contains(javaClass.name) ? " by a class Sketchware adds to every app" : " by one of the project's own Java files")
                        + ". Choose another name.");
            }
            if (javaClass.code == null || javaClass.code.isBlank()) {
                throw new AiSpecException("The helper class \"" + javaClass.name + "\" has no code.");
            }
            // The package is always the app's own, so it's added when the file is written
            javaClass.code = PACKAGE_LINE.matcher(javaClass.code.trim()).replaceFirst("").trim();
            if (!Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(javaClass.name) + "\\b").matcher(javaClass.code).find()) {
                throw new AiSpecException("The code of the helper class \"" + javaClass.name + "\" must declare \"class " + javaClass.name
                        + "\" (or an interface or enum of that name).");
            }
        }
    }

    /**
     * Writes the helper classes and removes the ones the AI wrote before that aren't wanted any more.
     */
    static void write(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File folder = new File(new FilePathUtil().getPathJava(sc_id));
        Set<String> wanted = new LinkedHashSet<>();
        for (AiProjectSpec.JavaClass javaClass : spec.javaClasses) {
            wanted.add(javaClass.name + ".java");
        }
        File[] existing = folder.listFiles((dir, name) -> name.endsWith(".java"));
        if (existing != null) {
            for (File file : existing) {
                if (!wanted.contains(file.getName()) && isOwn(file)) {
                    file.delete();
                }
            }
        }
        if (spec.javaClasses.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(folder.toPath());
            for (AiProjectSpec.JavaClass javaClass : spec.javaClasses) {
                String content = MARKER + "\npackage " + spec.packageName + ";\n\n" + javaClass.code + "\n";
                Files.write(new File(folder, javaClass.name + ".java").toPath(), content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't write the helper classes", e);
        }
    }

    /**
     * Fills in the AI's own helper classes, to keep or change, and the names of the user's.
     */
    static void read(@NonNull String sc_id, @NonNull AiProjectSpec spec) {
        File[] files = new File(new FilePathUtil().getPathJava(sc_id)).listFiles((dir, name) -> name.endsWith(".java"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - ".java".length());
            String content = readText(file);
            if (content != null && content.startsWith(MARKER)) {
                AiProjectSpec.JavaClass javaClass = new AiProjectSpec.JavaClass();
                javaClass.name = name;
                javaClass.code = PACKAGE_LINE.matcher(content.substring(MARKER.length()).trim()).replaceFirst("").trim();
                spec.javaClasses.add(javaClass);
            } else {
                spec.projectJavaClasses.add(name);
            }
        }
    }

    private static boolean isOwn(File file) {
        String content = readText(file);
        return content != null && content.startsWith(MARKER);
    }

    @Nullable
    private static String readText(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
