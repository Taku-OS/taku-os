// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Presentation-only controls. Callbacks and all camera/model ownership stay in ShoppingPreviewWindow. */
final class ShoppingPreviewChrome {
    static final int BACKGROUND = 0xff141714, TEXT = 0xffe5eadf, MUTED = 0xff88927f;
    private static final int MINI_BACKGROUND = 0xfffcfcf9, MINI_TEXT = 0xff1c201c, MINI_MUTED = 0xff636963;
    final FrameLayout header, hintRow;
    final LinearLayout toolbar;
    final TextView title, statusChip, hint, liveStatus;
    final Button minimize, close, shoppingClose, compactClose, compare, fullscreen, resizeTarget;
    private final View brand, divider, dragGrip;
    private final Context context;
    private final float density;
    private boolean live, character;
    private String characterStatus = "Connecting";
    private final GradientDrawable movingBackplate = new GradientDrawable();

    ShoppingPreviewChrome(Context context) {
        this.context = context; density = context.getResources().getDisplayMetrics().density;
        header = new FrameLayout(context);
        brand = new View(context); brand.setBackground(new Mark(TEXT));
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(brand, new FrameLayout.LayoutParams(dp(18), dp(18)));
        title = label("Live try-on", 14, TEXT); title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new FrameLayout.LayoutParams(-2, dp(44)));
        minimize = button("Minimize", "Minimize try-on. The session keeps running.", Symbol.UP, 12);
        header.addView(minimize, new FrameLayout.LayoutParams(dp(88), dp(44), Gravity.RIGHT | Gravity.TOP));
        close = button("", "Stop and close try-on", Symbol.CLOSE, 14);
        close.setPadding(dp(14), 0, dp(14), 0); close.setTooltipText("Stop try-on");
        header.addView(close, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.RIGHT | Gravity.TOP));
        dragGrip = new View(context);
        dragGrip.setBackground(shape(0xffaeb4ae, 999, 0));
        dragGrip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(dragGrip, new FrameLayout.LayoutParams(dp(24), dp(4), Gravity.CENTER));
        dragGrip.setVisibility(View.GONE);

        statusChip = label("Preparing", 11, TEXT); statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusChip.setBackground(shape(0xd61b2119, 999, 0x20ffffff));
        statusChip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        toolbar = new LinearLayout(context); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackground(shape(0x0cffffff, 17, 0x12ffffff));
        toolbar.setClipToOutline(true);
        compare = button("Hold to compare", "Hold to compare with your original camera view", Symbol.COMPARE, 12);
        toolbar.addView(compare, new LinearLayout.LayoutParams(0, -1, 1.27f));
        liveStatus = label("Connecting", 10, MINI_MUTED); liveStatus.setGravity(Gravity.CENTER_VERTICAL);
        liveStatus.setCompoundDrawablePadding(dp(4));
        toolbar.addView(liveStatus, new LinearLayout.LayoutParams(0, -1, 1));
        divider = new View(context); divider.setBackgroundColor(0x22ffffff);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(1), dp(18));
        line.gravity = Gravity.CENTER_VERTICAL; toolbar.addView(divider, line);
        fullscreen = button("Full screen", "Expand try-on to full screen", Symbol.EXPAND, 12);
        toolbar.addView(fullscreen, new LinearLayout.LayoutParams(0, -1, 1));
        shoppingClose=button("", "Stop and close try-on", Symbol.CLOSE, 14);
        compactClose=button("", "Stop and close try-on", Symbol.CLOSE, 14);
        for(Button exit:new Button[]{shoppingClose,compactClose}) {
            exit.setCompoundDrawables(null,null,null,null);iconLabel(exit,"",Symbol.CLOSE,Color.WHITE,16);
            exit.setPadding(0,0,0,0);exit.setTooltipText(null);
        }
        // Cancel lives on the visible dot node in compact mode, with a full touch
        // target and semantics; only the expanded video shows the explicit X.
        compactClose.setText(""); compactClose.setCompoundDrawables(null,null,null,null);
        compactClose.setTooltipText("Stop try-on");
        toolbar.addView(shoppingClose,new LinearLayout.LayoutParams(dp(44),-1));

        hintRow = new FrameLayout(context);
        hint = label("Drag header · Pinch to resize", 9, MUTED); hint.setGravity(Gravity.CENTER);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hintRow.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        resizeTarget = button("", "Drag the window corner to resize, or pinch with two fingers", null, 10);
        resizeTarget.setMinHeight(0); resizeTarget.setMinimumHeight(0); resizeTarget.setMinWidth(0); resizeTarget.setMinimumWidth(0);
        resizeTarget.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        resizeTarget.setTooltipText("Drag corner to resize");
    }

    void layout(ShoppingIslandGeometry.Layout layout, boolean full) {
        shoppingLayout(layout, full);
    }

    private void shoppingLayout(ShoppingIslandGeometry.Layout layout, boolean full) {
        header.setVisibility(View.GONE); hintRow.setVisibility(View.GONE);
        compare.setVisibility(View.GONE); liveStatus.setVisibility(View.GONE); divider.setVisibility(View.GONE);
        var controls = character ? ShoppingPreviewLayout.characterControls(layout, density)
                : ShoppingPreviewLayout.shoppingControls(layout, density);
        int target = controls.height();
        place(toolbar, controls.width(), target, controls.x(), controls.y());
        toolbar.setBackground(character ? shape(0x22ffffff,17,0x12ffffff) : null);
        toolbar.setClipToOutline(character);
        if(character) {
            brand.setVisibility(View.GONE); title.setVisibility(View.GONE);
            minimize.setVisibility(View.GONE); close.setVisibility(View.GONE);
            header.setVisibility(full?View.GONE:View.VISIBLE); dragGrip.setVisibility(full?View.GONE:View.VISIBLE);
            int edge=Math.min(dp(12),Math.max(0,controls.x()));
            place(header,Math.max(1,controls.x()-edge),target,edge,controls.y());
            FrameLayout.LayoutParams grip=(FrameLayout.LayoutParams)dragGrip.getLayoutParams();
            grip.width=Math.min(dp(24),Math.max(1,controls.x()-edge));grip.height=Math.min(dp(4),target);
            dragGrip.setLayoutParams(grip);
            place(resizeTarget,Math.min(dp(44),layout.window().width()),Math.min(dp(44),layout.window().height()),
                    Math.max(0,layout.window().width()-dp(44)),Math.max(0,layout.window().height()-dp(44)));
        }
        LinearLayout.LayoutParams corner = (LinearLayout.LayoutParams) fullscreen.getLayoutParams();
        corner.width = target; corner.height = -1; corner.weight = 0; fullscreen.setLayoutParams(corner);
        shoppingClose.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams exit=(LinearLayout.LayoutParams)shoppingClose.getLayoutParams();exit.width=target;exit.height=-1;shoppingClose.setLayoutParams(exit);
        fullscreen.setPadding(0,0,0,0);
        fullscreen.setBackground(ripple(true));
        Icon icon = new Icon(full ? Symbol.CONTRACT : Symbol.DIAGONAL, Color.WHITE);
        icon.setBounds(0,0,dp(17),dp(17));
        SpannableString glyph = new SpannableString("\ufffc");
        glyph.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullscreen.setCompoundDrawables(null,null,null,null); fullscreen.setText(glyph);
        fullscreen.setContentDescription(full ? character ? "Return to the floating cosplay window" : "Return to the floating try-on window"
                : character ? "Expand cosplay to full screen" : "Expand try-on to full screen");
        fullscreen.setTooltipText(null);
    }

    void characterPreview() {
        character = true; title.setText("Live cosplay");
        for (Button exit : new Button[]{close, shoppingClose, compactClose}) {
            exit.setContentDescription("Stop and close cosplay"); exit.setTooltipText("Stop cosplay");
        }
        updateLiveStatus();
    }

    /** A camera preview is not a model result. Only the displayed decoded return can become Live. */
    void characterState(boolean cameraReady, boolean active, boolean resultDisplayed) {
        live = resultDisplayed && active;
        characterStatus = !cameraReady ? "Opening camera" : live ? "Live" : active ? "Applying" : "Connecting";
        updateLiveStatus();
    }

    void live(boolean value) { if (live != value) { live = value; updateLiveStatus(); } }
    private void updateLiveStatus() {
        liveStatus.setText(character ? characterStatus : live ? "Live" : "Connecting");
        liveStatus.setTextColor(live ? MINI_TEXT : MINI_MUTED);
        liveStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(new Dot(live ? 0xff3e8a64 : 0xff969c95), null, null, null);
        liveStatus.setContentDescription(character ? (live ? "Live cosplay video" : characterStatus + " cosplay")
                : live ? "Live try-on video" : "Connecting to try-on");
    }

    void comparing(boolean value) {
        iconLabel(compare, value ? "Release to return" : "Hold to compare", Symbol.COMPARE, TEXT, 16);
        compare.setPressed(value);
        compare.setContentDescription(value ? "Release to return to try-on" : "Hold to compare with your original camera view");
    }
    Drawable backplate(boolean full) { return shape(0xff080c0b, full ? 0 : 22, 0); }
    Drawable motionBackplate(float fullFraction, float radiusPx, float ink) {
        movingBackplate.setColor(0xff080c0b); movingBackplate.setCornerRadius(Math.max(0,radiusPx));
        movingBackplate.setStroke(dp(1), 0xff080c0b); return movingBackplate;
    }

    View compactMark() {
        View view = new View(context); view.setBackground(new Mark(TEXT));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view;
    }
    private void place(View view, int width, int height, int x, int y) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
        p.width = width; p.height = height; p.gravity = Gravity.TOP | Gravity.LEFT;
        p.leftMargin = x; p.topMargin = y; view.setLayoutParams(p);
    }
    private TextView label(String value, int size, int color) {
        TextView text = new TextView(context); ShoppingIslandStyle.text(text, size); text.setText(value);
        text.setTextColor(color); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END); return text;
    }
    private Button button(String text, String description, Symbol symbol, int size) {
        Button b = new Button(context); ShoppingIslandStyle.text(b, size); b.setText(text); b.setTextColor(TEXT);
        b.setAllCaps(false); b.setStateListAnimator(null); b.setGravity(Gravity.CENTER); b.setMaxLines(1);
        b.setEllipsize(TextUtils.TruncateAt.END); b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44));
        b.setContentDescription(description); b.setCompoundDrawablePadding(dp(6));
        if (symbol != null) {
            if (text.isEmpty()) b.setCompoundDrawablesRelativeWithIntrinsicBounds(new Icon(symbol), null, null, null);
            else iconLabel(b, text, symbol, TEXT, 16);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), shape(0, 12, 0), null)); return b;
    }
    private Drawable ripple(boolean dark) { return new RippleDrawable(ColorStateList.valueOf(dark ? 0x22ffffff : 0x16000000), shape(0, 8, 0), null); }
    /** Center icon and label together; a compound drawable otherwise hugs a weighted button's left edge. */
    private void iconLabel(Button button, String label, Symbol symbol, int color, int size) {
        Icon icon = new Icon(symbol, color); icon.setBounds(0, 0, dp(size), dp(size));
        SpannableString content = new SpannableString("\ufffc " + label);
        content.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        button.setText(content);
    }
    private Drawable shape(int color, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke); return background;
    }
    private int dp(int value) { return Math.max(1, Math.round(value * density)); }
    private enum Symbol { UP, CLOSE, COMPARE, EXPAND, WINDOW, DIAGONAL, CONTRACT }
    private final class Mark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        Mark(int color) { this.color = color; }
        @Override public void draw(Canvas c) {
            paint.setColor(color); float w = getBounds().width(), h = getBounds().height();
            c.drawRoundRect(w*.08f,h*.13f,w*.36f,h*.87f,w*.15f,w*.15f,paint);
            c.drawRoundRect(w*.64f,h*.13f,w*.92f,h*.87f,w*.15f,w*.15f,paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private final class Icon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final Symbol symbol;
        Icon(Symbol symbol) { this(symbol, TEXT); }
        Icon(Symbol symbol, int color) { this.symbol = symbol; p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
        @Override public int getIntrinsicWidth() { return dp(16); }
        @Override public int getIntrinsicHeight() { return dp(16); }
        private void line(Canvas c, float a, float b, float x, float y) { c.drawLine(a,b,x,y,p); }
        @Override public void draw(Canvas c) {
            c.save(); c.translate(getBounds().left,getBounds().top); c.scale(getBounds().width()/24f,getBounds().height()/24f); p.setStrokeWidth(1.7f);
            switch (symbol) {
                case DIAGONAL -> { line(c,5,19,10,14); line(c,5,14,5,19); line(c,5,19,10,19); line(c,14,10,19,5); line(c,14,5,19,5); line(c,19,5,19,10); }
                case CONTRACT -> { line(c,4,20,10,14); line(c,5,14,10,14); line(c,10,14,10,19); line(c,14,10,20,4); line(c,14,5,14,10); line(c,14,10,19,10); }
                case UP -> { line(c,6,14,12,8); line(c,12,8,18,14); }
                case CLOSE -> { line(c,6,6,18,18); line(c,18,6,6,18); }
                case COMPARE -> { c.drawRoundRect(4,5,20,19,3,3,p); line(c,12,3,12,21); line(c,7,12,9,12); line(c,15,12,17,12); }
                case WINDOW -> { c.drawRoundRect(4,4,20,20,3,3,p); line(c,4,9,20,9); }
                case EXPAND -> { line(c,9,4,4,4); line(c,4,4,4,9); line(c,15,4,20,4); line(c,20,4,20,9); line(c,4,15,4,20); line(c,4,20,9,20); line(c,20,15,20,20); line(c,20,20,15,20); }
            }
            c.restore();
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private final class Dot extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dot(int color) { paint.setColor(color); }
        @Override public int getIntrinsicWidth() { return dp(6); }
        @Override public int getIntrinsicHeight() { return dp(6); }
        @Override public void draw(Canvas canvas) { canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(), getBounds().width() / 2f, paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
