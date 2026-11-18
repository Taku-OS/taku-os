package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.widget.*;

/** Small shared primitives for Agent settings and account pages, using the drawer palette. */
final class AgentSettingsUi {
    final Context context;
    final DrawerTheme theme;

    AgentSettingsUi(Context context, DrawerTheme theme) {
        this.context = context;
        this.theme = theme;
    }

    int dp(int value) {
        return theme.dp(context, value);
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    EditText input(String hint, int type) {
        EditText field = new EditText(context);
        field.setHint(hint); field.setTextColor(theme.ink); field.setHintTextColor(theme.muted);
        field.setTextSize(14); field.setInputType(type); field.setMinHeight(dp(44));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(theme.rounded(context, theme.surface, 12, true));
        return field;
    }

    TextView text(String copy, int size, boolean emphasis) {
        TextView label = new TextView(context);
        label.setText(copy);
        label.setTextSize(size);
        label.setTextColor(emphasis ? theme.ink : theme.muted);
        label.setIncludeFontPadding(false);
        label.setLineSpacing(dp(3), 1);
        return label;
    }

    LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(theme.rounded(context, theme.raised, 18, false));
        return card;
    }

    void add(LinearLayout parent, android.view.View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    void section(LinearLayout parent, String title, LinearLayout card) {
        TextView label = text(title, 12, false);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(4), 0, 0, 0);
        add(parent, label, 18);
        add(parent, card, 8);
    }

    Button button(String label, boolean primary, Runnable callback) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? theme.onAccent : theme.ink);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(theme.border),
                        theme.rounded(
                                context, primary ? theme.accent : theme.surface, 14, !primary),
                        null));
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    LinearLayout listGroup() {
        LinearLayout group = column();
        group.setBackground(theme.rounded(context, theme.raised, 18, false));
        return group;
    }

    void listRow(LinearLayout group, String title, String summary, int icon, Runnable action) {
        listRow(group, title, summary, icon, true, action);
    }

    void listRow(LinearLayout group, String title, String summary, int icon, boolean enabled, Runnable action) {
        if (group.getChildCount() > 0) {
            android.view.View divider = new android.view.View(context);
            divider.setBackgroundColor(theme.border);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
            line.leftMargin = dp(icon == 0 ? 12 : 48); line.rightMargin = dp(12);
            group.addView(divider, line);
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(summary.isEmpty() ? 44 : 56));
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.border), null, null));
        row.setContentDescription(title);
        if (icon != 0) {
            ImageView mark = new ImageView(context);
            mark.setImageResource(icon); if (icon != R.drawable.taku_brand_mark) mark.setColorFilter(theme.accent);
            mark.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(dp(24), dp(24));
            image.rightMargin = dp(12); row.addView(mark, image);
        }
        LinearLayout labels = column();
        TextView name = text(title, 15, true);
        name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name);
        if (!summary.isEmpty()) {
            TextView copy = text(summary, 12, false);
            copy.setMaxLines(1); copy.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(labels, copy, 3);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_drawer_chevron_down);
        arrow.setRotation(-90); arrow.setColorFilter(theme.muted);
        arrow.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        row.setOnClickListener(v -> action.run());
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setAlpha(enabled ? 1f : 0.4f);
        group.addView(row);
    }

    void chooseRow(LinearLayout group, String title, boolean selected, Runnable action) {
        listRow(group, title, "", 0, action);
        LinearLayout row = (LinearLayout) group.getChildAt(group.getChildCount() - 1);
        ImageView mark = (ImageView) row.getChildAt(row.getChildCount() - 1);
        mark.setImageResource(R.drawable.ic_check_circle); mark.setRotation(0);
        mark.setColorFilter(theme.accent); mark.setVisibility(selected ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        row.setSelected(selected);
    }

    TextView heading(String text) {
        TextView title = text(text, 17, true);
        title.setTypeface(null, Typeface.BOLD);
        return title;
    }
}
