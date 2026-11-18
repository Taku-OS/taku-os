package org.agentos.assistant;

import android.text.*;
import android.text.style.ForegroundColorSpan;
import java.util.*;

/** Linear lexical coloring. Never rewrites source; unknown languages stay plain. */
final class NativeCodeHighlight {
    private static final Set<String> LANGUAGES = words("java kotlin kt javascript js typescript ts jsx tsx python py json jsonc bash sh shell zsh go rust rs c cpp css");
    private static final Set<String> KEYWORDS = words("const let var function return if else for while class interface extends implements import from export default async await new throw try catch finally public private protected static final void int boolean def with as in is not and or None True False true false null undefined package func type struct fn pub use impl mut match self val fun");
    private static Set<String> words(String value) { return new HashSet<>(Arrays.asList(value.split(" "))); }

    static CharSequence render(String code, String language, DrawerTheme theme) {
        SpannableStringBuilder out = new SpannableStringBuilder(code);
        if (code.length() > 100_000) return out;
        if (language.equals("diff") || language.equals("patch")) {
            for (int start = 0; start < code.length();) {
                int end = code.indexOf('\n', start); if (end < 0) end = code.length();
                char first = code.charAt(start);
                if (first == '+' || first == '-' || first == '@') color(out, start, end, first == '+'
                        ? (theme.dark ? 0xff9bd3a6 : 0xff287544) : first == '-'
                        ? (theme.dark ? 0xffffaaa3 : 0xffab3838) : theme.muted);
                start = end + 1;
            }
        } else if (LANGUAGES.contains(language)) {
            boolean hash = Arrays.asList("python", "py", "bash", "sh", "shell", "zsh").contains(language);
            for (int i = 0; i < code.length();) {
                int start = i; char c = code.charAt(i++);
                if (c == '"' || c == '\'' || c == '`') {
                    while (i < code.length()) {
                        char next = code.charAt(i++);
                        if (next == '\\' && i < code.length()) i++;
                        else if (next == c) break;
                    }
                    color(out, start, i, theme.dark ? 0xffa9d29c : 0xff367343);
                } else if ((hash && c == '#') || (!hash && c == '/' && i < code.length() && code.charAt(i) == '/')) {
                    while (i < code.length() && code.charAt(i) != '\n') i++;
                    color(out, start, i, theme.muted);
                } else if (!hash && c == '/' && i < code.length() && code.charAt(i) == '*') {
                    int end = code.indexOf("*/", i + 1); i = end < 0 ? code.length() : end + 2;
                    color(out, start, i, theme.muted);
                } else if (Character.isDigit(c)) {
                    while (i < code.length() && (Character.isDigit(code.charAt(i)) || code.charAt(i) == '.')) i++;
                    color(out, start, i, theme.dark ? 0xffe8b685 : 0xff97551e);
                } else if (Character.isJavaIdentifierStart(c)) {
                    while (i < code.length() && Character.isJavaIdentifierPart(code.charAt(i))) i++;
                    if (KEYWORDS.contains(code.substring(start, i))) color(out, start, i, theme.dark ? 0xffc4b0ec : 0xff7154a0);
                }
            }
        }
        return out;
    }

    private static void color(SpannableStringBuilder out, int start, int end, int color) {
        out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
