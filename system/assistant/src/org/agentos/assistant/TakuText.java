package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;

/**
 * Taku text helpers for Chinese content.
 *
 * 1. Styles: use Taku.Text.Body etc. for English UI, Taku.Text.Body.Zh etc. for Chinese text
 *    (zero letter spacing; 1.3 line height for headings).
 * 2. Mixed or unknown text (messages, notifications): call {@link #applyStyle} after setText.
 *    It keeps the view's text color, so set the color in the layout or before the call.
 * 3. Same Chinese glyphs on every phone (API 29+): {@link #body} / {@link #display} put Noto Sans SC
 *    right behind the Latin font. Without it, Chinese uses the phone's own system font.
 */
public final class TakuText {
    private TakuText() {}

    /** Chinese is the main language: one Han character counts like two Latin letters. */
    public static boolean isChinese(CharSequence text) {
        if (text == null) return false;
        int han = 0, latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN) han++;
            else if (script == Character.UnicodeScript.LATIN) latin++;
            i += Character.charCount(cp);
        }
        return han > 0 && han * 2 >= latin;
    }

    /**
     * Apply the Latin or Chinese variant of a Taku text style to a view, based on its current text.
     * Keeps the view's text color. For Chinese text it also sets the Simplified Chinese locale
     * (correct glyph shapes) and strict line breaking (Android 13+).
     */
    public static void applyStyle(TextView view, int latinStyle, int chineseStyle) {
        boolean chinese = isChinese(view.getText());
        ColorStateList color = view.getTextColors();
        view.setTextAppearance(chinese ? chineseStyle : latinStyle);
        view.setTextColor(color);
        // Set the font explicitly: some renderers drop font resources from setTextAppearance.
        view.setTypeface(view.getResources().getFont(fontFor(latinStyle)));
        view.setTextLocale(chinese ? Locale.SIMPLIFIED_CHINESE : Locale.getDefault());
        if (Build.VERSION.SDK_INT >= 33) {
            view.setLineBreakStyle(chinese ? LineBreakConfig.LINE_BREAK_STYLE_STRICT : LineBreakConfig.LINE_BREAK_STYLE_NONE);
        }
        // Line height lives on the Taku.Text.* view styles; re-apply it for the chosen language.
        if (Build.VERSION.SDK_INT >= 28) {
            Resources res = view.getResources();
            boolean heading = latinStyle == R.style.TextAppearance_Taku_Heading;
            int line = heading && chinese ? R.dimen.taku_line_heading_zh : lineFor(latinStyle);
            if (line != 0) view.setLineHeight(res.getDimensionPixelSize(line));
        }
    }

    private static int fontFor(int style) {
        if (style == R.style.TextAppearance_Taku_Heading) return R.font.space_grotesk_regular;
        if (style == R.style.TextAppearance_Taku_Context
                || style == R.style.TextAppearance_Taku_Label
                || style == R.style.TextAppearance_Taku_MetaMedium) return R.font.hanken_grotesk_medium;
        return R.font.hanken_grotesk_regular;
    }

    private static int lineFor(int style) {
        if (style == R.style.TextAppearance_Taku_Heading) return R.dimen.taku_line_heading;
        if (style == R.style.TextAppearance_Taku_Context) return R.dimen.taku_line_context;
        if (style == R.style.TextAppearance_Taku_Body) return R.dimen.taku_line_body;
        if (style == R.style.TextAppearance_Taku_Label) return R.dimen.taku_line_label;
        if (style == R.style.TextAppearance_Taku_Meta) return R.dimen.taku_line_meta;
        if (style == R.style.TextAppearance_Taku_MetaMedium) return R.dimen.taku_line_meta_medium;
        if (style == R.style.TextAppearance_Taku_Caption) return R.dimen.taku_line_caption;
        return 0;
    }

    /** Hanken Grotesk with Noto Sans SC as the first fallback (API 29+). Older phones: Hanken only. */
    public static Typeface body(Context context, boolean medium) {
        return withChinese(context,
                medium ? R.font.hanken_grotesk_medium : R.font.hanken_grotesk_regular,
                medium ? R.font.noto_sans_sc_medium : R.font.noto_sans_sc_regular);
    }

    /** Space Grotesk headings with Noto Sans SC behind them (API 29+). */
    public static Typeface display(Context context) {
        return withChinese(context, R.font.space_grotesk_regular, R.font.noto_sans_sc_regular);
    }

    private static Typeface withChinese(Context context, int latin, int chinese) {
        Resources res = context.getResources();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                FontFamily latinFamily = new FontFamily.Builder(new Font.Builder(res, latin).build()).build();
                FontFamily chineseFamily = new FontFamily.Builder(new Font.Builder(res, chinese).build()).build();
                return new Typeface.CustomFallbackBuilder(latinFamily)
                        .addCustomFallback(chineseFamily)
                        .setSystemFallback("sans-serif")
                        .build();
            } catch (IOException ignored) {
                // Fall through to the Latin font alone.
            }
        }
        return res.getFont(latin);
    }
}
