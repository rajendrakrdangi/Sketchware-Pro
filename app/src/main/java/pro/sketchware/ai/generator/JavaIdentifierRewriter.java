package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, conservative rewrites of Java code that models regularly get wrong about how Sketchware
 * names things. Code inside string and character literals and comments is never touched.
 */
final class JavaIdentifierRewriter {

    private JavaIdentifierRewriter() {
    }

    /**
     * Sketchware declares a More Block's parameters with an underscore, so a parameter
     * <code>title</code> is <code>_title</code> in its body. Bare references to a parameter get
     * the underscore, unless the code declares a local variable of that name itself.
     */
    @NonNull
    static String prefixParameterReferences(@NonNull String code, @NonNull Collection<String> parameterNames) {
        String result = code;
        for (String name : parameterNames) {
            if (declaresLocal(code, name)) {
                continue;
            }
            // Not a member (".title"), not part of a longer name, and not a method call
            Pattern reference = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\b(?!\\s*\\()");
            result = replaceOutsideLiterals(result, reference, "_" + name);
        }
        return result;
    }

    /**
     * @return Whether the code declares a variable of this name, e.g. "String title =",
     * "for (String title :" or "catch (Exception title)".
     */
    static boolean declaresLocal(@NonNull String code, @NonNull String name) {
        Pattern declaration = Pattern.compile(
                "(?<![\\w.])[A-Za-z_][\\w.]*(?:<[^;(){}]*>)?(?:\\[\\])*\\s+" + Pattern.quote(name) + "\\s*(?:=|;|:|,|\\))");
        return find(code, declaration);
    }

    /**
     * @return Whether the pattern occurs in the code outside literals and comments.
     */
    static boolean find(@NonNull String code, @NonNull Pattern pattern) {
        boolean[] found = {false};
        forEachCodeSegment(code, segment -> {
            if (pattern.matcher(segment).find()) {
                found[0] = true;
            }
            return segment;
        });
        return found[0];
    }

    private static String replaceOutsideLiterals(String code, Pattern pattern, String replacement) {
        return forEachCodeSegment(code, segment -> pattern.matcher(segment).replaceAll(Matcher.quoteReplacement(replacement)));
    }

    /**
     * Applies the transformation to every stretch of code between literals and comments, and
     * puts the literals and comments back unchanged.
     */
    private static String forEachCodeSegment(String code, SegmentTransformation transformation) {
        StringBuilder result = new StringBuilder(code.length() + 16);
        int segmentStart = 0;
        int i = 0;
        while (i < code.length()) {
            int end = endOfLiteralOrComment(code, i);
            if (end > i) {
                result.append(transformation.apply(code.substring(segmentStart, i)));
                result.append(code, i, end);
                i = end;
                segmentStart = end;
            } else {
                i++;
            }
        }
        result.append(transformation.apply(code.substring(segmentStart)));
        return result.toString();
    }

    /**
     * @return The index after the literal or comment starting at <code>start</code>, or
     * <code>start</code> itself if none starts there.
     */
    private static int endOfLiteralOrComment(String code, int start) {
        char c = code.charAt(start);
        if (c == '"' || c == '\'') {
            int i = start + 1;
            while (i < code.length()) {
                char current = code.charAt(i);
                if (current == '\\') {
                    i += 2;
                } else if (current == c || current == '\n') {
                    return Math.min(i + 1, code.length());
                } else {
                    i++;
                }
            }
            return code.length();
        }
        if (code.startsWith("//", start)) {
            int lineEnd = code.indexOf('\n', start);
            return lineEnd < 0 ? code.length() : lineEnd;
        }
        if (code.startsWith("/*", start)) {
            int blockEnd = code.indexOf("*/", start + 2);
            return blockEnd < 0 ? code.length() : blockEnd + 2;
        }
        return start;
    }

    private interface SegmentTransformation {
        String apply(String segment);
    }
}
