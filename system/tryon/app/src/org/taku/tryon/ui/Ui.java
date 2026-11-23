// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small framework-only view helpers, private to this application. */
public final class Ui {
    public static final int BACKGROUND = Color.rgb(246, 247, 243);
    public static final int INK = Color.rgb(30, 51, 44);
    public static final int MUTED = Color.rgb(105, 119, 111);
    public static final int ACCENT = Color.rgb(32, 90, 67);
    public static final int BORDER = Color.rgb(222, 229, 220);
    private final Context context;

    public Ui(Context context) { this.context = context; }

    public int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    public LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public GradientDrawable rounded(int color, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    /** Android uses clockwise corner pairs: top-left, top-right, bottom-right, bottom-left. */
    public GradientDrawable roundedCorners(int color, int border, int topLeft, int topRight, int bottomRight, int bottomLeft) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{dp(topLeft), dp(topLeft), dp(topRight), dp(topRight),
                dp(bottomRight), dp(bottomRight), dp(bottomLeft), dp(bottomLeft)});
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    public LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(rounded(Color.WHITE, BORDER, 20));
        return layout;
    }

    public TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public Button button(String value, boolean primary) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(rounded(primary ? ACCENT : Color.rgb(239, 245, 238), 0, 13));
        button.setStateListAnimator(null);
        return button;
    }

    public void space(LinearLayout parent, int height) {
        View spacer = new View(context);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(height)));
    }

    public void add(LinearLayout parent, View child) {
        parent.addView(child, new LinearLayout.LayoutParams(-1, -2));
    }

    public void enable(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .42f);
    }
}
