package pro.sketchware.ai.generator;

import java.util.Map;

/**
 * Maps the type names a model may use (see {@link AiProjectPrompt}) to Sketchware's own.
 * Components and events come from {@link AiSketchwareCatalog} instead, which reads them from
 * Sketchware itself.
 */
final class AiSpecTypes {

    /**
     * Variable type name to Sketchware's variable type ID, see <code>a.a.a.mq#c(int)</code>.
     */
    static final Map<String, Integer> VARIABLES = Map.of(
            "boolean", 0,
            "number", 1,
            "String", 2,
            "Map", 3);

    /**
     * List type name to Sketchware's list type ID, see <code>a.a.a.mq#b(int)</code>.
     */
    static final Map<String, Integer> LISTS = Map.of(
            "ListNumber", 1,
            "ListString", 2,
            "ListMap", 3);

    /**
     * Method parameter type name to its More Block spec placeholder.
     */
    static final Map<String, String> METHOD_PARAMETERS = Map.of(
            "String", "%s",
            "number", "%d",
            "boolean", "%b");

    private AiSpecTypes() {
    }
}
