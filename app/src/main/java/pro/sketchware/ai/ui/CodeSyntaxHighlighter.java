package pro.sketchware.ai.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import androidx.annotation.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pro.sketchware.utility.ThemeUtils;

/**
 * Provides syntax highlighting for code files and formats Markdown with rich code blocks in chat.
 */
public final class CodeSyntaxHighlighter {

    // Java Syntax Patterns
    private static final Pattern JAVA_KEYWORDS = Pattern.compile(
            "\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|"
                    + "enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|"
                    + "native|new|package|private|protected|public|return|short|static|strictfp|super|switch|"
                    + "synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null)\\b");

    private static final Pattern JAVA_STRINGS = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"");
    private static final Pattern JAVA_COMMENTS = Pattern.compile("(//.*?$|/\\*.*?\\*/)", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern JAVA_NUMBERS = Pattern.compile("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?f?d?l?)\\b");
    private static final Pattern JAVA_ANNOTATIONS = Pattern.compile("@[a-zA-Z0-9_]+");

    // XML Syntax Patterns
    private static final Pattern XML_TAGS = Pattern.compile("</?[a-zA-Z0-9_\\.:]+|/?>");
    private static final Pattern XML_ATTRS = Pattern.compile("\\b[a-zA-Z0-9_:]+(?=\\=)");
    private static final Pattern XML_VALUES = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern XML_COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    // Markdown Fenced Code: ```lang\ncode```
    private static final Pattern MARKDOWN_CODE_BLOCK = Pattern.compile("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```");
    /**
     * Inline Markdown, earliest first: `code` (1), **bold** (2), __bold__ (3), *italic* (4).
     * Italic needs non-space next to its stars, so "a * b" and list stars stay as they are.
     */
    private static final Pattern MARKDOWN_INLINE = Pattern.compile(
            "`([^`\\n]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern MARKDOWN_BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    /**
     * Whether a text has any Markdown worth rendering.
     */
    private static final Pattern MARKDOWN_ANY = Pattern.compile("`|\\*|__|(?m)^\\s{0,3}#{1,6}\\s|(?m)^\\s*[-+]\\s");

    private CodeSyntaxHighlighter() {
    }

    /**
     * Highlights full source code (e.g. for {@link pro.sketchware.ai.generator.GeneratedFile}).
     */
    @NonNull
    public static CharSequence highlight(@NonNull Context context, @NonNull String code, @NonNull String fileName) {
        SpannableString spannable = new SpannableString(code);
        boolean dark = ThemeUtils.isDarkThemeEnabled(context);

        int colorKeyword = dark ? 0xffbb86fc : 0xff6200ee;
        int colorString = dark ? 0xff81c784 : 0xff2e7d32;
        int colorComment = dark ? 0xff888888 : 0xff757575;
        int colorNumber = dark ? 0xff4fc3f7 : 0xff0288d1;
        int colorAttr = dark ? 0xffffb74d : 0xffe65100;
        int colorTag = dark ? 0xffbb86fc : 0xff6200ee;

        if (fileName.endsWith(".xml")) {
            // XML
            applyPattern(spannable, XML_TAGS, new ForegroundColorSpan(colorTag), new StyleSpan(Typeface.BOLD));
            applyPattern(spannable, XML_ATTRS, new ForegroundColorSpan(colorAttr));
            applyPattern(spannable, XML_VALUES, new ForegroundColorSpan(colorString));
            applyPattern(spannable, XML_COMMENTS, new ForegroundColorSpan(colorComment), new StyleSpan(Typeface.ITALIC));
        } else {
            // Java and others
            applyPattern(spannable, JAVA_KEYWORDS, new ForegroundColorSpan(colorKeyword), new StyleSpan(Typeface.BOLD));
            applyPattern(spannable, JAVA_NUMBERS, new ForegroundColorSpan(colorNumber));
            applyPattern(spannable, JAVA_STRINGS, new ForegroundColorSpan(colorString));
            applyPattern(spannable, JAVA_ANNOTATIONS, new ForegroundColorSpan(colorAttr));
            applyPattern(spannable, JAVA_COMMENTS, new ForegroundColorSpan(colorComment), new StyleSpan(Typeface.ITALIC));
        }

        return spannable;
    }

    /**
     * Formats chat messages containing Markdown: styles code blocks and inline code with monospace
     * font, subtle background chips, and syntax coloring.
     */
    @NonNull
    public static CharSequence formatChatText(@NonNull Context context, @NonNull String text) {
        if (!MARKDOWN_ANY.matcher(text).find()) {
            return text;
        }

        boolean dark = ThemeUtils.isDarkThemeEnabled(context);
        int bgCode = dark ? 0x22ffffff : 0x15000000;
        int bgBlock = dark ? 0x33ffffff : 0x1a000000;

        SpannableStringBuilder builder = new SpannableStringBuilder();
        Matcher matcher = MARKDOWN_CODE_BLOCK.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            // Append text before code block
            String before = text.substring(lastEnd, matcher.start());
            builder.append(formatInlineMarkdown(before, bgCode));

            String lang = matcher.group(1);
            String code = matcher.group(2);

            int blockStart = builder.length();
            if (code != null) {
                // Trim trailing newline
                String cleanCode = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
                builder.append("\n").append(highlight(context, cleanCode, lang != null && lang.contains("xml") ? "file.xml" : "file.java")).append("\n");
                int blockEnd = builder.length();

                builder.setSpan(new BackgroundColorSpan(bgBlock), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new TypefaceSpan("monospace"), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new RelativeSizeSpan(0.92f), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            builder.append(formatInlineMarkdown(text.substring(lastEnd), bgCode));
        }

        return builder;
    }

    /**
     * Renders the Markdown of chat text outside code blocks, removing the markers: headings
     * (<code>## Title</code>), bullets (<code>- item</code>), <code>**bold**</code>,
     * <code>*italic*</code> and <code>`code`</code>.
     */
    private static CharSequence formatInlineMarkdown(String text, int bgCode) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            Matcher heading = MARKDOWN_HEADING.matcher(line);
            Matcher bullet = MARKDOWN_BULLET.matcher(line);
            if (heading.matches()) {
                int start = out.length();
                appendInline(out, heading.group(2), bgCode);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (heading.group(1).length() <= 2) {
                    out.setSpan(new RelativeSizeSpan(1.12f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (bullet.matches()) {
                out.append(bullet.group(1)).append("• ");
                appendInline(out, bullet.group(2), bgCode);
            } else {
                appendInline(out, line, bgCode);
            }
        }
        return out;
    }

    /**
     * Appends one line, turning <code>`code`</code>, <code>**bold**</code>, <code>__bold__</code>
     * and <code>*italic*</code> into styles without their markers.
     */
    private static void appendInline(SpannableStringBuilder out, String line, int bgCode) {
        Matcher matcher = MARKDOWN_INLINE.matcher(line);
        int last = 0;
        while (matcher.find()) {
            out.append(line, last, matcher.start());
            int start = out.length();
            if (matcher.group(1) != null) {
                out.append(matcher.group(1));
                out.setSpan(new BackgroundColorSpan(bgCode), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(2) != null || matcher.group(3) != null) {
                // Bold may itself contain `code` or *italic*
                appendInline(out, matcher.group(2) != null ? matcher.group(2) : matcher.group(3), bgCode);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                appendInline(out, matcher.group(4), bgCode);
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = matcher.end();
        }
        out.append(line, last, line.length());
    }

    private static void applyPattern(Spannable spannable, Pattern pattern, Object... spans) {
        Matcher matcher = pattern.matcher(spannable.toString());
        while (matcher.find()) {
            for (Object span : spans) {
                Object clone = cloneSpan(span);
                spannable.setSpan(clone, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static Object cloneSpan(Object span) {
        if (span instanceof ForegroundColorSpan color) {
            return new ForegroundColorSpan(color.getForegroundColor());
        }
        if (span instanceof StyleSpan style) {
            return new StyleSpan(style.getStyle());
        }
        if (span instanceof BackgroundColorSpan bg) {
            return new BackgroundColorSpan(bg.getBackgroundColor());
        }
        return span;
    }
}
