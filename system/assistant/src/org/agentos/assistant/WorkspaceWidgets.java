package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.*;

/** Small workspace-only primitives matching the approved settings design. */
final class WorkspaceWidgets {
    final Context context;
    final WorkspaceStyle theme;
    WorkspaceWidgets(Context context, WorkspaceStyle theme) { this.context = context; this.theme = theme; }
    int dp(int value) { return theme.dp(context, value); }
    LinearLayout column() { LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL); return column; }
    TextView text(String copy, int size, boolean emphasis) {
        TextView text = new TextView(context); text.setText(copy); text.setTextSize(size);
        text.setTextColor(emphasis ? theme.ink : theme.muted); text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(3), 1); text.setTypeface(Typeface.create(emphasis ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return text;
    }
    LinearLayout card() {
        LinearLayout card = listGroup(); card.setPadding(dp(18), dp(18), dp(18), dp(18)); return card;
    }
    LinearLayout listGroup() {
        LinearLayout group = column(); group.setBackground(theme.rounded(context, theme.raised, 19, false));
        group.setClipToOutline(true); return group;
    }
    void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); parent.addView(child, params);
    }
    void section(LinearLayout parent, String title, LinearLayout card) {
        TextView label = text(title, 12, false); label.setPadding(dp(2), 0, dp(2), 0); add(parent, label, 24); add(parent, card, 9);
    }
    void divider(LinearLayout group, int inset) {
        if (group.getChildCount() == 0) return;
        View divider = new View(context); divider.setBackgroundColor(theme.border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        params.leftMargin = dp(inset); group.addView(divider, params);
    }
    Button button(String label, boolean primary, Runnable callback) {
        Button button = new Button(context); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? theme.onAccent : theme.accent); button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(17), dp(10), dp(17), dp(10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.border), theme.rounded(context, primary ? theme.accent : theme.soft, 22, false), null));
        button.setStateListAnimator(null); button.setOnClickListener(view -> callback.run()); return button;
    }
}
