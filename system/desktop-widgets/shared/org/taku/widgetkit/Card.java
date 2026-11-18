package org.taku.widgetkit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.widget.RemoteViews;
import java.util.HashMap;
import java.util.Map;

/** RemoteViews rendering of the same tokens used by taku-home/design and the original widgets. */
public final class Card {
    public static final float RADIUS = 24, INSET = 12;
    public static final int INK = 0xcc000000, MUTED = 0xa3000000, LIGHT = 0x29000000;
    public static final int ORANGE = 0xffff713e, FOREGROUND = 0xff1b1b1b;
    public static final int SURFACE = 0xebffffff, SURFACE_MUTED = 0xfff1f1f3;
    public final Canvas canvas;
    public final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    public final Bitmap bitmap;
    public final float w, h;
    private final Context context;
    private final Typeface body, display;
    private static final Map<String, Bitmap> assets = new HashMap<>();

    public Card(Context c, int id) {
        context = c;
        Bundle o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id);
        w = Math.max(60, Math.min(600, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 182)));
        h = Math.max(60, Math.min(600, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 182)));
        float density = Math.min(3, c.getResources().getDisplayMetrics().density);
        bitmap = Bitmap.createBitmap(Math.round(w * density), Math.round(h * density), Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        canvas.scale(bitmap.getWidth() / w, bitmap.getHeight() / h);
        body = c.getResources().getFont(resource(c, "font", "hanken_grotesk_regular"));
        display = c.getResources().getFont(resource(c, "font", "space_grotesk_regular"));
        // Radius is a shared token, not a fraction of the widget width. All six cards match.
        paint.setColor(SURFACE);
        canvas.drawRoundRect(0, 0, w, h, RADIUS, RADIUS, paint);
        Path clip = new Path(); clip.addRoundRect(0, 0, w, h, RADIUS, RADIUS, Path.Direction.CW);
        canvas.clipPath(clip);
    }

    /** Position text by its Figma line box, using the imported fonts, line height and tracking. */
    public void text(String text, float x, float top, float size, float lineHeight, int color, boolean center, boolean heading) {
        paint.setColor(color); paint.setTextSize(size); paint.setTypeface(heading ? display : body);
        paint.setLetterSpacing(heading ? (size >= 40 ? -.03f : -.02f) : -.01f);
        paint.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = top + (lineHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent;
        canvas.drawText(text, x, baseline, paint);
    }
    public void title(String text, float x, float top, float maxWidth) {
        paint.setTypeface(body); paint.setTextSize(14); paint.setLetterSpacing(-.01f);
        String value = text;
        if (paint.measureText(value) > maxWidth) {
            while (!value.isEmpty() && paint.measureText(value + "…") > maxWidth) value = value.substring(0, value.length() - 1);
            value += "…";
        }
        text(value, x, top, 14, 19.88f, INK, false, false);
    }
    public void circle(float x, float y, float r, int color) {
        paint.setColor(color); canvas.drawCircle(x, y, r, paint);
    }
    public void asset(String name, float x, float y, float width, float height) {
        canvas.drawBitmap(asset(context, name), null, new RectF(x, y, x + width, y + height), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    }
    public static synchronized Bitmap asset(Context c, String name) {
        String key = c.getPackageName() + ":" + name;
        Bitmap image = assets.get(key);
        if (image == null) {
            image = BitmapFactory.decodeResource(c.getResources(), resource(c, "drawable", name));
            assets.put(key, image);
        }
        return image;
    }
    /** Original PixelGlyphs 9×9 dot grammar, for data-driven weather and the music placeholder. */
    public void dots(String[] rows, float x, float y, float size, int color) {
        float scale = size / 32;
        for (int row = 0; row < rows.length; row++) for (int col = 0; col < rows[row].length(); col++)
            if (rows[row].charAt(col) == 'X') circle(x + (2.8f + 3.2f * col) * scale, y + (2.8f + 3.2f * row) * scale, 1.2f * scale, color);
    }
    public void album(Bitmap art, float x, float y, float size) {
        RectF rect = new RectF(x, y, x + size, y + size);
        Path clip = new Path(); clip.addRoundRect(rect, 12, 12, Path.Direction.CW);
        int save = canvas.save(); canvas.clipPath(clip);
        paint.setColor(SURFACE_MUTED); canvas.drawRect(rect, paint);
        if (art != null) {
            int side = Math.min(art.getWidth(), art.getHeight());
            int left = (art.getWidth() - side) / 2, top = (art.getHeight() - side) / 2;
            canvas.drawBitmap(art, new Rect(left, top, left + side, top + side), rect, paint);
        } else dots(new String[]{"...XXXXXX", "...X....X", "...X....X", "...X....X", "...X....X", ".XXX..XXX", "XXXX.XXXX", ".XX...XX.", "........."}, x + (size - 32) / 2, y + (size - 32) / 2, 32, LIGHT);
        canvas.restoreToCount(save);
        paint.setColor(0x14000000); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
        canvas.drawRoundRect(x + .5f, y + .5f, x + size - .5f, y + size - .5f, 12, 12, paint);
        paint.setStyle(Paint.Style.FILL);
    }
    public static Bitmap control(Context c, String icon, boolean inverse, boolean enabled) {
        Bitmap image = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.scale(3, 3);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(inverse ? FOREGROUND : 0x0a000000); canvas.drawCircle(20, 20, 20, p);
        if ("pause".equals(icon)) {
            // Reuse the original TakuIcons.Pause geometry (20dp viewport) for the playing state.
            p.setColor(Color.WHITE);
            canvas.drawRoundRect(15.5f, 12, 18.5f, 28, 1.5f, 1.5f, p);
            canvas.drawRoundRect(21.5f, 12, 24.5f, 28, 1.5f, 1.5f, p);
        } else {
            float size = inverse ? 20 : 24, inset = (40 - size) / 2;
            if (!enabled && !inverse) p.setAlpha(41);
            canvas.drawBitmap(asset(c, icon), null, new RectF(inset, inset, inset + size, inset + size), p);
        }
        return image;
    }
    public RemoteViews views(Context c, String description, Intent click) {
        RemoteViews r = new RemoteViews(c.getPackageName(), resource(c, "layout", "widget"));
        r.setImageViewBitmap(resource(c, "id", "image"), bitmap);
        r.setContentDescription(resource(c, "id", "image"), description);
        if (click != null) r.setOnClickPendingIntent(resource(c, "id", "image"), PendingIntent.getActivity(c, 0, click, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return r;
    }
    public static int resource(Context c, String type, String name) { return c.getResources().getIdentifier(name, type, c.getPackageName()); }
}
