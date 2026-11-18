package org.agentos.assistant;

import android.app.Activity;
import android.content.*;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.commonmark.node.*;
import org.commonmark.ext.gfm.tables.TableBlock;
import java.io.*;
import java.util.*;

/** Deterministic parser + real Android rendering checks, isolated from user conversations. */
public final class MarkdownRendererActivity extends Activity {
    private int checks;
    private NativeMarkdownView markdown;
    private TextView stable;
    private HorizontalScrollView codeScroll;
    private ClipData previousClipboard;
    private boolean clipboardCaptured;
    private int focusAttempts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String code = "const greeting = \"你好 **保持源码** `code`\"; // 注释\n" + "const longLine = \"" + "0123456789".repeat(20) + "\";\n";
    private final String base = "## Markdown 输出\n\n中文正文支持 **加粗**、*斜体*、~~删除线~~ 和 `内联代码`。\n\n"
            + "> 引用保留层次，正文保持连贯。\n\n- [x] 已完成解析\n- [ ] 等待验收\n  - 中文嵌套列表\n\n"
            + "```javascript\n" + code + "```\n\n"
            + "| 项目 | 状态 | 验证环境 | 负责人 | 备注 |\n|:---|:---:|---:|---|---|\n"
            + "| 中文流式 | **通过** | Android 36 | Taku | 已保留增量 |\n| 代码复制 | 通过 | MacBook | Taku | 保留全部原文 |\n\n"
            + "[项目文档](https://github.com/Taku-OS/taku-os) · [工作区文件](/workspace/README.md)\n\n";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            parserChecks();
            boolean dark = "dark".equals(getIntent().getStringExtra("theme"));
            DrawerTheme theme = new DrawerTheme(dark);
            getWindow().setStatusBarColor(theme.surface); getWindow().setNavigationBarColor(theme.surface);
            ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(theme.surface);
            scroll.setPadding(32, 28, 32, 28);
            markdown = new NativeMarkdownView(this, theme); scroll.addView(markdown); setContentView(scroll);
            markdown.bind(base + "逐字输出：中");
            stable = (TextView) findText(markdown, "中文正文支持 加粗、斜体、删除线 和 内联代码。");
            check(stable != null, "native formatted paragraph");
            Selection.setSelection((Spannable) stable.getText(), 0, 4);
            handler.postDelayed(this::viewChecks, 350);
        } catch (Throwable error) { report(error); }
    }

    private void parserChecks() {
        Spanned spans = (Spanned) NativeMarkdown.render("# 标题\n\n**粗体** *斜体* ~~删除~~ `**不是粗体**`", 0);
        check(spans.toString().contains("**不是粗体**"), "inline code literal protected");
        TextPaint strong = paintAt(spans, "粗体");
        check(strong.getTypeface().getWeight() == 500 && !strong.isFakeBoldText(),
                "Chinese emphasis uses medium weight without synthetic bold");
        TextPaint chineseEmphasis = paintAt(spans, "斜体");
        check(!chineseEmphasis.getTypeface().isItalic() && chineseEmphasis.getTextSkewX() == 0,
                "Chinese emphasis is not rendered in italic");
        Spanned latin = (Spanned) NativeMarkdown.render("*italic*", 0);
        TextPaint latinEmphasis = paintAt(latin, "italic");
        check(latinEmphasis.getTypeface().isItalic() || latinEmphasis.getTextSkewX() != 0,
                "Latin Markdown emphasis is preserved");
        check(spans.getSpans(0, spans.length(), StrikethroughSpan.class).length == 1, "GFM strikethrough");
        check(spans.getSpans(0, spans.length(), TypefaceSpan.class).length == 1, "inline code typography");
        check(NativeMarkdown.render("\\*字面量\\* &amp; &#20013;", 0).toString().equals("*字面量* & 中"), "escapes and entities");
        check(NativeMarkdown.render("0. 零\n1. 一", 0).toString().equals("0. 零\n1. 一"), "ordered list starts at zero");
        check(NativeMarkdown.render("3. 三\n4. 四\n   - 子项", 0).toString().contains("4. 四\n• 子项"), "nested numbered list");
        check(NativeMarkdown.render("- [x] 完成\n- [ ] 待办", 0).toString().equals("☑ 完成\n☐ 待办"), "read-only task markers stay inline");
        Spanned links = (Spanned) NativeMarkdown.render("[链接][ref]\n\n[ref]: https://example.com\n", 0);
        check(links.toString().equals("链接"), "reference definitions hidden");
        check(links.getSpans(0, links.length(), URLSpan.class)[0].getURL().equals("https://example.com"), "reference destination resolved");
        Spanned auto = (Spanned) NativeMarkdown.render("https://example.com and a@example.com", 0);
        check(auto.getSpans(0, auto.length(), URLSpan.class).length == 2, "GFM URL and email autolinks");
        check(NativeMarkdownLinks.external("https://example.com"), "HTTPS dispatch");
        check(!NativeMarkdownLinks.external("javascript:alert(1)"), "non-web scheme never launched");
        check(!NativeMarkdownLinks.external("/workspace/file.md"), "host file remains path");
        for (String fence : new String[]{"```", "~~~~"}) {
            Node fenced = NativeMarkdown.parse(fence + "js\n**代码** `x` [link](url)\n" + fence).getFirstChild();
            check(fenced instanceof FencedCodeBlock, "standard fence " + fence);
            check(NativeMarkdown.code(fenced).equals("**代码** `x` [link](url)\n"), "code remains exact " + fence);
        }
        Node incomplete = NativeMarkdown.parse("```python\nprint('中文')").getFirstChild();
        check(incomplete instanceof FencedCodeBlock, "unclosed streaming fence");
        check(NativeMarkdown.code(incomplete).equals("print('中文')\n"), "streaming code retained");
        check(NativeMarkdown.parse("    indented()").getFirstChild() instanceof IndentedCodeBlock, "indented code");
        check(NativeMarkdown.parse("| A | B |\n|:---|---:|\n| 中文 | `值` |").getFirstChild() instanceof TableBlock, "GFM table");
        check(NativeMarkdown.render("<script>literal</script>", 0).toString().contains("<script>literal</script>"), "raw HTML remains literal");
        check(NativeMarkdown.render("价格 $100 与 $200", 0).toString().equals("价格 $100 与 $200"), "currency untouched");
        for (String language : new String[]{"javascript", "python", "json", "diff", "unknown", "mermaid", "latex"}) {
            String raw = "**literal** `x`\n+ 中文\n- old\n";
            check(NativeCodeHighlight.render(raw, language, new DrawerTheme(false)).toString().equals(raw), "source preserving highlight " + language);
        }
        String longString = "const x = \"" + "中".repeat(50_000) + "\"";
        check(NativeCodeHighlight.render(longString, "js", new DrawerTheme(false)).toString().equals(longString), "large literal highlighter stays linear");
    }

    private TextPaint paintAt(Spanned text, String value) {
        int start = text.toString().indexOf(value);
        check(start >= 0, "typography fixture retains its exact text");
        TextPaint paint = new TextPaint();
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        for (MetricAffectingSpan span : text.getSpans(start, start + value.length(), MetricAffectingSpan.class))
            span.updateMeasureState(paint);
        return paint;
    }

    private void viewChecks() {
        if (!hasWindowFocus() && focusAttempts++ < 50) { handler.postDelayed(this::viewChecks, 100); return; }
        try {
            check(hasWindowFocus(), "fixture owns foreground focus");
            previousClipboard = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            clipboardCaptured = true;
            check(markdown.getWidth() > 0 && markdown.getHeight() > 0, "real layout measured");
            TextView source = (TextView) findText(markdown, code);
            check(source != null, "fenced code preserves source");
            codeScroll = (HorizontalScrollView) source.getParent();
            codeScroll.scrollTo(180, 0);
            check(codeScroll.getScrollX() > 0, "long code horizontally scrolls");
            description(markdown, getString(R.string.chat_copy_code)).performClick();
            check(((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString().equals(code), "code copy exact including symbols and newline");
            HorizontalScrollView table = (HorizontalScrollView) description(markdown, "表格，可横向滚动");
            check(table.getChildAt(0).getWidth() > table.getWidth(), "wide table exceeds viewport");
            table.scrollTo(120, 0); check(table.getScrollX() > 0, "table horizontally scrolls");
            String[] frames = {"中", "中文", "中文\uD83D", "中文😊", "中文😊，**加粗", "中文😊，**加粗**", "中文😊，**加粗**\n\n新段落"};
            for (String frame : frames) {
                markdown.bind(base + "逐字输出：" + NativeTurnPresentation.visibleText(frame, true));
                check(findText(markdown, stable.getText().toString()) == stable, "completed paragraph identity");
                check(stable.getSelectionStart() == 0 && stable.getSelectionEnd() == 4, "completed paragraph selection survives");
                check(findText(markdown, code) == source, "code view identity across deltas");
                check(codeScroll.getScrollX() == 180, "horizontal scroll survives deltas");
                check(!allText(markdown).contains("\uFFFD"), "Unicode boundary intact");
            }
            NativeMarkdownView reference = new NativeMarkdownView(this, new DrawerTheme(false));
            reference.bind("[动态][ref]"); TextView before = (TextView) reference.getChildAt(0);
            reference.bind("[动态][ref]\n\n[ref]: https://example.com");
            check(reference.getChildAt(0) == before, "late reference updates same view");
            check(((Spanned) before.getText()).getSpans(0, before.length(), URLSpan.class).length == 1, "late reference resolved without frozen cache");
            reference.bind("~~~text\n**原样** `code`"); View beforeCode = reference.getChildAt(0);
            reference.bind("~~~text\n**原样** `code`\n~~~\n\n收尾");
            check(reference.getChildAt(0) == beforeCode, "closing a streaming fence retains block");
            check(findText(reference, "**原样** `code`\n") != null, "closing fence never reformats code");
            // Decode/load actual image pixels off the UI thread, with visible failure fallback.
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffff5a1f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
            String url = "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
            NativeMarkdownImage loaded = new NativeMarkdownImage(this, new DrawerTheme(false));
            NativeMarkdownImage failed = new NativeMarkdownImage(this, new DrawerTheme(false));
            markdown.addView(loaded); markdown.addView(failed);
            loaded.bind(url, "图片解码验收"); failed.bind("/host/missing.png", "缺失图片");
            handler.postDelayed(() -> imageChecks(loaded, failed, 0), 100);
        } catch (Throwable error) { report(error); }
    }

    private void imageChecks(NativeMarkdownImage loaded, NativeMarkdownImage failed, int attempt) {
        try {
            if (attempt < 40 && (loaded.getChildAt(0).getVisibility() != View.VISIBLE
                    || findText(failed, "缺失图片 · 预览不可用，点按查看地址") == null)) {
                handler.postDelayed(() -> imageChecks(loaded, failed, attempt + 1), 100); return;
            }
            check(loaded.getChildAt(0).getVisibility() == View.VISIBLE, "image asynchronously decoded and rendered");
            check(findText(failed, "缺失图片 · 预览不可用，点按查看地址") != null, "unavailable image retains visible fallback");
            report(null);
        } catch (Throwable error) { report(error); }
    }

    private void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private View findText(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(value)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findText(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private String allText(View view) {
        String out = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out += allText(((ViewGroup) view).getChildAt(i));
        return out;
    }
    private void report(Throwable error) {
        if (clipboardCaptured && previousClipboard != null) ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(previousClipboard);
        else if (clipboardCaptured) ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).clearPrimaryClip();
        try (FileOutputStream out = openFileOutput("markdown-renderer.json", MODE_PRIVATE)) {
            JSONObject result = new JSONObject().put("passed", error == null).put("assertions", checks).put("fixtureOnly", true);
            if (error != null) result.put("error", error.toString());
            out.write(result.toString(2).getBytes("UTF-8"));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
