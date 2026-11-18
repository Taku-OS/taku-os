package org.agentos.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Semantic palette shared by every drawer state; no per-screen duplicate themes. */
final class DrawerTheme {
    final boolean dark;
    final int surface, raised, bubble, ink, muted, accent, border, onAccent;

    DrawerTheme(boolean dark) {
        this(null, dark);
    }

    /** The Chat shell consumes the supplied mobile DS; other hosts retain their palette. */
    DrawerTheme(Context context, boolean dark) {
        this.dark = dark;
        boolean supplied = context != null && !dark;
        surface = supplied ? context.getColor(R.color.taku_wall) : Color.parseColor(dark ? "#16140F" : "#FFFDFA");
        raised = supplied ? context.getColor(R.color.taku_surface) : Color.parseColor(dark ? "#22201B" : "#FFFFFF");
        bubble = supplied ? context.getColor(R.color.taku_sage) : Color.parseColor(dark ? "#3B281E" : "#FFF0E6");
        ink = supplied ? context.getColor(R.color.taku_ink) : Color.parseColor(dark ? "#FFFDFA" : "#16140F");
        muted = supplied ? context.getColor(R.color.taku_text_secondary) : Color.parseColor(dark ? "#B2ADA4" : "#76716A");
        accent = Color.parseColor(dark ? "#FF8757" : "#FF5A1F");
        border = supplied ? context.getColor(R.color.taku_border) : Color.parseColor(dark ? "#39362F" : "#EAE6DF");
        onAccent = Color.parseColor(dark ? "#16140F" : "#FFFFFF");
    }

    int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    GradientDrawable rounded(Context context, int color, int radius, boolean outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (outline) shape.setStroke(dp(context, 1), border);
        return shape;
    }
}
