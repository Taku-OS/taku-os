// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Figma 124:62151 actions: 40dp, 8dp gap, equal fill width, full pill radius. */
final class TryOnResultCard extends LinearLayout {
    TryOnResultCard(Context context, String detail, Runnable close, Runnable retry, Runnable open) {
        super(context);
        setOrientation(VERTICAL); setPadding(dp(16), dp(16), dp(16), dp(16));
        setBackground(shape(0xff121212, dp(40))); setClipToOutline(true);
        FrameLayout header = new FrameLayout(context);
        View eyes = new View(context); eyes.setBackground(new ResultMark());
        eyes.setContentDescription("Taku");
        header.addView(eyes, new FrameLayout.LayoutParams(dp(18), dp(16), Gravity.LEFT | Gravity.CENTER_VERTICAL));
        ImageButton expand = new ImageButton(context);
        int resource = context.getResources().getIdentifier("tryon_island_expand", "drawable", context.getPackageName());
        expand.setImageResource(resource); expand.setPadding(dp(6), dp(6), dp(6), dp(6));
        expand.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), shape(Color.TRANSPARENT, dp(12)), null));
        expand.setContentDescription("Open try-on details"); expand.setOnClickListener(v -> open.run());
        header.addView(expand, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.RIGHT));
        addView(header, new LayoutParams(-1, dp(32)));
        LinearLayout text = new LinearLayout(context); text.setOrientation(VERTICAL);
        text.addView(label(TryOnResultWindow.TITLE, 18, 0xccffffff));
        LayoutParams detailLayout = new LayoutParams(-1, -2); detailLayout.topMargin = dp(12);
        text.addView(label(detail, 16, 0xccffffff), detailLayout);
        LayoutParams disclosureLayout = new LayoutParams(-1, -2); disclosureLayout.topMargin = dp(12);
        text.addView(label(TryOnResultWindow.DISCLOSURE, 14, 0xa3ffffff), disclosureLayout);
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(text); LayoutParams reading = new LayoutParams(-1, 0, 1f); reading.topMargin = dp(4);
        addView(scroll, reading);
        LinearLayout buttons = new LinearLayout(context); buttons.setOrientation(HORIZONTAL);
        buttons.addView(button("Close", false, close), new LayoutParams(0, -2, 1));
        LayoutParams retryLayout = new LayoutParams(0, -2, 1); retryLayout.leftMargin = dp(8);
        buttons.addView(button("Retry", true, retry), retryLayout);
        LayoutParams row = new LayoutParams(-1, -2); row.topMargin = dp(16); addView(buttons, row);
    }
    int naturalHeightDp(int width) {
        measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return Math.max(96, Math.min(340, (int)Math.ceil(getMeasuredHeight() / getResources().getDisplayMetrics().density)));
    }
    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(font()); view.setIncludeFontPadding(false); view.setLetterSpacing(-.01f);
        view.setLineHeight(Math.round((size == 18 ? 27.9f : size * 1.5f) * getResources().getDisplayMetrics().scaledDensity));
        return view;
    }
    private TextView button(String title, boolean primary, Runnable action) {
        TextView view = new TextView(getContext()) {
            @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
        };
        view.setText(title); view.setTextSize(16); view.setTypeface(font()); view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER); view.setLetterSpacing(-.01f);
        view.setMaxLines(1); view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setTextColor(primary ? 0xff1b1b1b : 0xccffffff); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setLineHeight(Math.round(24 * getResources().getDisplayMetrics().scaledDensity));
        view.setMinHeight(Math.max(dp(40), Math.round(24 * getResources().getDisplayMetrics().scaledDensity) + dp(16)));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x22000000 : 0x33ffffff),
                shape(primary ? Color.WHITE : 0x14ffffff, dp(9999)), null));
        view.setClickable(true); view.setFocusable(true); view.setOnClickListener(v -> action.run());
        return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    private Typeface font() {
        try {
            Context app = getContext().createPackageContext("org.agentos.agent", 0);
            int id = app.getResources().getIdentifier("hanken_grotesk_regular", "font", app.getPackageName());
            return app.getResources().getFont(id);
        } catch (Exception unavailable) { return Typeface.create("sans-serif", Typeface.NORMAL); }
    }
    /** Existing native Taku mark geometry, local to the result card's chrome. */
    private static final class ResultMark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            paint.setColor(Color.WHITE); float w = getBounds().width(), h = getBounds().height();
            canvas.drawRoundRect(w*.08f,h*.13f,w*.36f,h*.87f,w*.15f,w*.15f,paint);
            canvas.drawRoundRect(w*.64f,h*.13f,w*.92f,h*.87f,w*.15f,w*.15f,paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
