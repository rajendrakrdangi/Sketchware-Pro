package pro.sketchware.ai.generator;

import androidx.annotation.NonNull;

import com.besome.sketch.beans.BlockBean;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.kq;

/**
 * Converts generated Java code statements into native visual Sketchware {@link BlockBean}s,
 * falling back to direct source blocks ("add source directly") only for logic that has no visual
 * equivalent.
 */
public final class AiBlockGenerator {

    private static final String SOURCE_BLOCK_OPCODE = "addSourceDirectly";
    private static final String SOURCE_BLOCK_SPEC = "add source directly %s.inputOnly";
    private static final String SOURCE_BLOCK_TYPE = " ";
    private static final int SOURCE_BLOCK_COLOR = 0xff5cb722;
    private static final int BASE_BLOCK_ID = 10;
    /**
     * The type of blocks that end a chain, like "finish Activity".
     */
    private static final String TERMINATOR_TYPE = "f";

    // View manipulation patterns
    private static final Pattern PATTERN_SET_TEXT = Pattern.compile("^([a-zA-Z0-9_]+)\\.setText\\((\"[^\"]*\")\\);?$");
    private static final Pattern PATTERN_SET_VISIBLE = Pattern.compile("^([a-zA-Z0-9_]+)\\.setVisibility\\(View\\.(VISIBLE|INVISIBLE|GONE)\\);?$");
    private static final Pattern PATTERN_SET_ENABLE = Pattern.compile("^([a-zA-Z0-9_]+)\\.setEnabled\\((true|false)\\);?$");
    private static final Pattern PATTERN_SET_CHECKED = Pattern.compile("^([a-zA-Z0-9_]+)\\.setChecked\\((true|false)\\);?$");

    // Toast / Feedback patterns
    private static final Pattern PATTERN_DO_TOAST_SKETCH = Pattern.compile("^SketchwareUtil\\.showMessage\\(.*?\\s*,\\s*(\"[^\"]*\")\\);?$");
    private static final Pattern PATTERN_DO_TOAST = Pattern.compile("^Toast\\.makeText\\(.*?\\s*,\\s*(\"[^\"]*\")\\s*,\\s*Toast\\.LENGTH_\\w+\\)\\.show\\(\\);?$");
    private static final Pattern PATTERN_CLIPBOARD = Pattern.compile("^\\(\\(ClipboardManager\\).*?\\)\\.setPrimaryClip\\(ClipData\\.newPlainText\\(.*?\\s*,\\s*(\"[^\"]*\")\\)\\);?$");

    // Activity navigation & lifecycle
    // The screen is stored by its class name, e.g. NoteEditorActivity, which Sketchware writes back
    // as NoteEditorActivity.class
    private static final Pattern PATTERN_SET_SCREEN = Pattern.compile("^([a-zA-Z0-9_]+)\\.setClass\\(.*?\\s*,\\s*([a-zA-Z0-9_]+Activity)\\.class\\);?$");
    private static final Pattern PATTERN_START_ACTIVITY = Pattern.compile("^startActivity\\(([a-zA-Z0-9_]+)\\);?$");
    private static final Pattern PATTERN_PUT_EXTRA = Pattern.compile("^([a-zA-Z0-9_]+)\\.putExtra\\((\"[^\"]*\")\\s*,\\s*(\"[^\"]*\")\\);?$");
    private static final Pattern PATTERN_FINISH = Pattern.compile("^(finish|finishAffinity)\\(\\);?$");
    private static final Pattern PATTERN_SET_TITLE = Pattern.compile("^setTitle\\((\"[^\"]*\")\\);?$");

    // SharedPreferences persistence
    private static final Pattern PATTERN_SP_PUT = Pattern.compile("^([a-zA-Z0-9_]+)\\.edit\\(\\)\\.putString\\((\"[^\"]*\")\\s*,\\s*(\"[^\"]*\")\\)\\.commit\\(\\);?$");
    private static final Pattern PATTERN_SP_REMOVE = Pattern.compile("^([a-zA-Z0-9_]+)\\.edit\\(\\)\\.remove\\((\"[^\"]*\")\\)\\.commit\\(\\);?$");

    // Variable operations
    private static final Pattern PATTERN_VAR_INC = Pattern.compile("^([a-zA-Z0-9_]+)\\+\\+;?$");
    private static final Pattern PATTERN_VAR_DEC = Pattern.compile("^([a-zA-Z0-9_]+)--;?$");
    private static final Pattern PATTERN_SET_VAR_STR = Pattern.compile("^([a-zA-Z0-9_]+)\\s*=\\s*(\"[^\"]*\");?$");
    private static final Pattern PATTERN_SET_VAR_NUM = Pattern.compile("^([a-zA-Z0-9_]+)\\s*=\\s*(-?\\d+(?:\\.\\d+)?);?$");
    private static final Pattern PATTERN_SET_VAR_BOOL = Pattern.compile("^([a-zA-Z0-9_]+)\\s*=\\s*(true|false);?$");

    // List operations
    private static final Pattern PATTERN_LIST_CLEAR = Pattern.compile("^([a-zA-Z0-9_]+)\\.clear\\(\\);?$");
    private static final Pattern PATTERN_LIST_ADD_STR = Pattern.compile("^([a-zA-Z0-9_]+)\\.add\\((\"[^\"]*\")\\);?$");

    private AiBlockGenerator() {
    }

    /**
     * Converts raw Java code into a linked sequence of {@link BlockBean}s.
     */
    @NonNull
    public static ArrayList<BlockBean> generateBlocks(@NonNull String code) {
        String trimmed = code.replace("\r\n", "\n").trim();
        ArrayList<BlockBean> result = new ArrayList<>();
        if (trimmed.isEmpty()) {
            return result;
        }

        List<String> statements = splitStatements(trimmed);
        List<BlockBean> rawBlocks = new ArrayList<>();

        StringBuilder unmappedBuffer = new StringBuilder();

        for (int index = 0; index < statements.size(); index++) {
            String s = statements.get(index).trim();
            if (s.isEmpty()) {
                continue;
            }

            BlockBean block = matchNativeBlock(s);
            if (block != null && TERMINATOR_TYPE.equals(block.type) && !isLastStatement(statements, index)) {
                // Blocks like "finish Activity" end a chain, so nothing could follow them. In Java
                // the code after finish() still runs, so it stays source code to keep it.
                block = null;
            }
            if (block != null) {
                // Flush accumulated unmapped code first
                if (unmappedBuffer.length() > 0) {
                    rawBlocks.add(createSourceBlock(unmappedBuffer.toString().trim()));
                    unmappedBuffer.setLength(0);
                }
                rawBlocks.add(block);
            } else {
                // Accumulate statements for direct source block
                if (unmappedBuffer.length() > 0) {
                    unmappedBuffer.append("\n");
                }
                unmappedBuffer.append(s);
            }
        }

        if (unmappedBuffer.length() > 0) {
            rawBlocks.add(createSourceBlock(unmappedBuffer.toString().trim()));
        }

        if (rawBlocks.isEmpty()) {
            return result;
        }

        // Link blocks in sequence; a terminator can only be the last one, see above
        for (int i = 0; i < rawBlocks.size(); i++) {
            BlockBean current = rawBlocks.get(i);
            current.id = String.valueOf(BASE_BLOCK_ID + i);
            boolean isLast = (i == rawBlocks.size() - 1);
            current.nextBlock = isLast ? -1 : (BASE_BLOCK_ID + i + 1);
            result.add(current);
        }

        return result;
    }

    /**
     * @return Whether nothing but blank statements follows the one at <code>index</code>. A
     * trailing comment counts as something, as it would become a block after the terminator.
     */
    private static boolean isLastStatement(List<String> statements, int index) {
        for (int i = index + 1; i < statements.size(); i++) {
            if (!statements.get(i).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static BlockBean matchNativeBlock(String s) {
        Matcher m;

        // 1. setText
        m = PATTERN_SET_TEXT.matcher(s);
        if (m.matches()) {
            return createBlock("setText", "%m.view setText %s", " ",
                    m.group(1), unquote(m.group(2)));
        }

        // 2. setVisible
        m = PATTERN_SET_VISIBLE.matcher(s);
        if (m.matches()) {
            return createBlock("setVisible", "%m.view setVisible %m.visible", " ",
                    m.group(1), m.group(2));
        }

        // 3. setEnable
        m = PATTERN_SET_ENABLE.matcher(s);
        if (m.matches()) {
            return createBlock("setEnable", "%m.view setEnable %b", " ",
                    m.group(1), m.group(2));
        }

        // 4. setChecked
        m = PATTERN_SET_CHECKED.matcher(s);
        if (m.matches()) {
            return createBlock("setChecked", "%m.compoundButton setChecked %b", " ",
                    m.group(1), m.group(2));
        }

        // 5. Toast
        m = PATTERN_DO_TOAST_SKETCH.matcher(s);
        if (!m.matches()) {
            m = PATTERN_DO_TOAST.matcher(s);
        }
        if (m.matches()) {
            return createBlock("doToast", "Toast %s", " ", unquote(m.group(1)));
        }

        // 6. Clipboard
        m = PATTERN_CLIPBOARD.matcher(s);
        if (m.matches()) {
            return createBlock("copyToClipboard", "copy to Clipboard %s", " ", unquote(m.group(1)));
        }

        // 7. setTitle
        m = PATTERN_SET_TITLE.matcher(s);
        if (m.matches()) {
            return createBlock("setTitle", "Activity setTitle %s", " ", unquote(m.group(1)));
        }

        // 8. finish
        m = PATTERN_FINISH.matcher(s);
        if (m.matches()) {
            return createBlock("finishActivity", "finish Activity", "f");
        }

        // 9. intentSetScreen
        m = PATTERN_SET_SCREEN.matcher(s);
        if (m.matches()) {
            return createBlock("intentSetScreen", "%m.intent setScreen %m.activity", " ",
                    m.group(1), m.group(2));
        }

        // 10. startActivity
        m = PATTERN_START_ACTIVITY.matcher(s);
        if (m.matches()) {
            return createBlock("startActivity", "startActivity %m.intent", " ", m.group(1));
        }

        // 11. intentPutExtra
        m = PATTERN_PUT_EXTRA.matcher(s);
        if (m.matches()) {
            return createBlock("intentPutExtra", "%m.intent putExtra key %s value %s", " ",
                    m.group(1), unquote(m.group(2)), unquote(m.group(3)));
        }

        // 12. SharedPreferences
        m = PATTERN_SP_PUT.matcher(s);
        if (m.matches()) {
            return createBlock("fileSetData", "%m.file setData key %s value %s", " ",
                    m.group(1), unquote(m.group(2)), unquote(m.group(3)));
        }
        m = PATTERN_SP_REMOVE.matcher(s);
        if (m.matches()) {
            return createBlock("fileRemoveData", "%m.file removeData key %s", " ",
                    m.group(1), unquote(m.group(2)));
        }

        // 13. Variables
        m = PATTERN_VAR_INC.matcher(s);
        if (m.matches()) {
            return createBlock("increaseInt", "%m.varNumber increase 1", " ", m.group(1));
        }
        m = PATTERN_VAR_DEC.matcher(s);
        if (m.matches()) {
            return createBlock("decreaseInt", "%m.varNumber decrease 1", " ", m.group(1));
        }
        m = PATTERN_SET_VAR_STR.matcher(s);
        if (m.matches()) {
            return createBlock("setVarString", "%m.varString set %s", " ", m.group(1), unquote(m.group(2)));
        }
        m = PATTERN_SET_VAR_NUM.matcher(s);
        if (m.matches()) {
            return createBlock("setVarInt", "%m.varNumber set %d", " ", m.group(1), m.group(2));
        }
        m = PATTERN_SET_VAR_BOOL.matcher(s);
        if (m.matches()) {
            return createBlock("setVarBoolean", "%m.var set %b", " ", m.group(1), m.group(2));
        }

        // 14. Lists
        m = PATTERN_LIST_CLEAR.matcher(s);
        if (m.matches()) {
            return createBlock("clearList", "%m.list clear", " ", m.group(1));
        }
        m = PATTERN_LIST_ADD_STR.matcher(s);
        if (m.matches()) {
            // Notice: In Sketchware's addListStr, params are [value, listName]
            return createBlock("addListStr", "%m.list add %s", " ", unquote(m.group(2)), m.group(1));
        }

        return null;
    }

    private static BlockBean createBlock(String opCode, String spec, String type, String... params) {
        BlockBean block = new BlockBean(String.valueOf(BASE_BLOCK_ID), spec, type, opCode);
        block.color = kq.a(opCode, type);
        for (String p : params) {
            block.parameters.add(p);
        }
        return block;
    }

    private static BlockBean createSourceBlock(String code) {
        BlockBean block = new BlockBean(String.valueOf(BASE_BLOCK_ID), SOURCE_BLOCK_SPEC, SOURCE_BLOCK_TYPE, SOURCE_BLOCK_OPCODE);
        block.color = SOURCE_BLOCK_COLOR;
        block.parameters.add(code);
        return block;
    }

    private static String unquote(String str) {
        if (str != null && str.length() >= 2 && str.startsWith("\"") && str.endsWith("\"")) {
            return str.substring(1, str.length() - 1);
        }
        return str != null ? str : "";
    }

    /**
     * Splits Java code into individual statements respecting string literals and block delimiters.
     */
    private static List<String> splitStatements(String code) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        boolean inChar = false;
        int braceDepth = 0;
        int parenDepth = 0;

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);

            if (!inStr && !inChar && isCommentStart(code, i)) {
                int end = endOfComment(code, i);
                String comment = code.substring(i, end);
                if (cur.toString().trim().isEmpty()) {
                    // A comment on its own is a piece of its own, so it's never cut in half and
                    // its text can't be mistaken for code
                    list.add(comment.trim());
                    cur.setLength(0);
                } else {
                    // Inside a statement it stays where it is; its quotes and semicolons are text
                    cur.append(comment);
                }
                i = end - 1;
                continue;
            }

            if (c == '\\' && (inStr || inChar) && i + 1 < code.length()) {
                cur.append(c).append(code.charAt(i + 1));
                i++;
                continue;
            }

            if (c == '"' && !inChar) {
                inStr = !inStr;
            } else if (c == '\'' && !inStr) {
                inChar = !inChar;
            } else if (!inStr && !inChar) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                } else if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                } else if (c == ';' && braceDepth == 0 && parenDepth == 0) {
                    cur.append(c);
                    list.add(cur.toString().trim());
                    cur.setLength(0);
                    continue;
                }
            }

            cur.append(c);

            // Block closed at root level
            if (!inStr && !inChar && c == '}' && braceDepth == 0 && parenDepth == 0) {
                list.add(cur.toString().trim());
                cur.setLength(0);
            }
        }

        if (cur.length() > 0 && !cur.toString().trim().isEmpty()) {
            list.add(cur.toString().trim());
        }

        return list;
    }

    private static boolean isCommentStart(String code, int index) {
        return code.startsWith("//", index) || code.startsWith("/*", index);
    }

    /**
     * @return The index just after the comment that starts at <code>start</code>: after the line
     * break that ends a line comment, or after the <code>*&#47;</code> of a block comment.
     */
    private static int endOfComment(String code, int start) {
        if (code.startsWith("//", start)) {
            int lineEnd = code.indexOf('\n', start);
            return lineEnd < 0 ? code.length() : lineEnd + 1;
        }
        int blockEnd = code.indexOf("*/", start + 2);
        return blockEnd < 0 ? code.length() : blockEnd + 2;
    }
}
