package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns compiler logs into a description a model can act on. The model never saw the generated
 * Java, so line numbers mean nothing to it: every problem says which screen file and which part
 * of the app (onCreate, an event, a method) it comes from, and quotes the offending code.
 */
final class CompileErrorReport {

    /**
     * How many problems are described at most; a model fixes the first ones best anyway.
     */
    private static final int MAX_PROBLEMS = 12;
    private static final int MAX_LOG_LENGTH = 4000;

    private static final Pattern ECJ_PROBLEM = Pattern.compile(
            "^\\d+\\. ERROR in (.+?) \\(at line (\\d+)\\)\\s*$", Pattern.MULTILINE);
    private static final String ECJ_SEPARATOR = "----------";
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*(?:@\\w+\\s+)*(?:(?:public|private|protected|static|final|synchronized)\\s+)*"
                    + "(?!new\\b|return\\b|else\\b)[\\w.<>\\[\\], ]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*(?:throws [\\w., ]+)?\\{?\\s*$");
    private static final Pattern LISTENER_TARGET = Pattern.compile("(\\w+)\\.set\\w*(?:Listener|Callback|Watcher|Client)\\s*\\(");
    private static final Pattern ERROR_COUNT = Pattern.compile("doesn't compile\\. (\\d+) errors?");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/\\S*/(?:res|java)/(\\S+?\\.(?:xml|java))");

    private CompileErrorReport() {
    }

    @NonNull
    static String resources(@Nullable String log) {
        String text = log == null ? "" : ABSOLUTE_PATH.matcher(log).replaceAll("$1");
        return "Its layouts don't compile (Android resource compiler):\n" + truncate(text.trim());
    }

    @NonNull
    static String java(@Nullable String log) {
        if (log == null || log.trim().isEmpty()) {
            return "Its Java code doesn't compile.";
        }
        List<String> problems = new ArrayList<>();
        int total = 0;
        Matcher matcher = ECJ_PROBLEM.matcher(log);
        while (matcher.find()) {
            total++;
            if (problems.size() >= MAX_PROBLEMS) {
                continue;
            }
            int bodyStart = matcher.end();
            int bodyEnd = log.indexOf(ECJ_SEPARATOR, bodyStart);
            String body = log.substring(bodyStart, bodyEnd < 0 ? log.length() : bodyEnd);
            problems.add(problem(matcher.group(1), Integer.parseInt(matcher.group(2)), body));
        }
        if (problems.isEmpty()) {
            return "Its Java code doesn't compile:\n" + truncate(log.trim());
        }

        StringBuilder result = new StringBuilder("Its Java code doesn't compile. ")
                .append(total == 1 ? "1 error" : total + " errors")
                .append(total > problems.size() ? ", the first " + problems.size() + ":" : ":");
        for (int i = 0; i < problems.size(); i++) {
            result.append("\n\n").append(i + 1).append(". ").append(problems.get(i));
        }
        return result.toString();
    }

    /**
     * @return How many errors a description of {@link #java(String)} lists, or 0 if it doesn't say.
     */
    static int countErrors(@NonNull String description) {
        Matcher matcher = ERROR_COUNT.matcher(description);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String problem(String path, int line, String body) {
        // The body is the offending code, a line of carets under it, and the message
        List<String> code = new ArrayList<>();
        StringBuilder message = new StringBuilder();
        for (String bodyLine : body.split("\r?\n")) {
            String trimmed = bodyLine.trim();
            if (trimmed.isEmpty() || trimmed.matches("\\^+")) {
                continue;
            }
            if (bodyLine.startsWith("\t")) {
                code.add(trimmed);
            } else {
                if (message.length() > 0) message.append(' ');
                message.append(trimmed);
            }
        }

        StringBuilder problem = new StringBuilder(message.length() == 0 ? "Compile error" : message.toString());
        problem.append("\n   in ").append(new File(path).getName());
        String location = locate(new File(path), line);
        if (location != null) {
            problem.append(", ").append(location);
        }
        if (!code.isEmpty()) {
            problem.append("\n   code: ").append(String.join(" ", code));
        }
        return problem.toString();
    }

    /**
     * @return Where in the app the generated line comes from, e.g. "onCreate",
     * "method saveNote" or "event onClick of save_button", or null if that can't be told.
     */
    @Nullable
    static String locate(@NonNull File javaFile, int line) {
        List<String> lines;
        try {
            lines = Files.readAllLines(javaFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        for (int i = Math.min(line, lines.size()) - 1; i >= 0; i--) {
            Matcher declaration = METHOD_DECLARATION.matcher(lines.get(i));
            if (!declaration.matches()) {
                continue;
            }
            String name = declaration.group(1);
            switch (name) {
                case "if", "for", "while", "switch", "catch", "synchronized" -> {
                    continue;
                }
                case "initializeLogic" -> {
                    return "onCreate";
                }
            }
            if (name.startsWith("_")) {
                return "method " + name.substring(1);
            }
            for (int j = i - 1; j >= Math.max(0, i - 4); j--) {
                Matcher target = LISTENER_TARGET.matcher(lines.get(j));
                if (target.find()) {
                    return "event " + name + " of " + target.group(1);
                }
            }
            return name;
        }
        return null;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_LOG_LENGTH ? text : text.substring(0, MAX_LOG_LENGTH) + "\n…";
    }
}
