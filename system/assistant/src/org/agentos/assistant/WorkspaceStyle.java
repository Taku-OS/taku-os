package org.agentos.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Settings palette scoped to workspace management; never changes the chat drawer. */
final class WorkspaceStyle {
    final boolean dark;
    final int surface, raised, ink, muted, accent, border, onAccent, soft;
    WorkspaceStyle(boolean dark) {
        this.dark = dark;
        surface = Color.parseColor(dark ? "#15171C" : "#F5F6F9");
        raised = Color.parseColor(dark ? "#23262D" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#EDF0F5" : "#191C23");
        muted = Color.parseColor(dark ? "#B0B8C6" : "#596170");
        border = Color.parseColor(dark ? "#393F49" : "#E2E6ED");
        soft = Color.parseColor(dark ? "#34425D" : "#E8EEFB");
        accent = Color.parseColor(dark ? "#C3D9FF" : "#284C87");
        onAccent = Color.parseColor(dark ? "#15171C" : "#FFFFFF");
    }
    int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    GradientDrawable rounded(Context context, int color, int radius, boolean outline) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(context, radius));
        if (outline) shape.setStroke(dp(context, 1), border);
        return shape;
    }
}
