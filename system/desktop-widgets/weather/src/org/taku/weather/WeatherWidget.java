package org.taku.weather;

import android.appwidget.*;
import android.content.*;
import org.json.*;
import org.taku.widgetkit.Card;

public final class WeatherWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) render(c, id); WeatherData.schedule(c); WeatherData.refresh(c); }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle b) { render(c, id); }
    public static void update(Context c) { for (int id : AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, WeatherWidget.class))) render(c, id); }
    private static void render(Context c, int id) {
        try {
            JSONObject data = WeatherData.read(c);
            Card card = new Card(c, id);
            String city = data.getString("city"), description;
            float x = (card.w - 40) / 2, y = (card.h - 40) / 2;
            if (data.getBoolean("available")) {
                JSONObject current = data.getJSONObject("current");
                int code = current.getInt("weather_code");
                description = city + " " + Math.round(current.getDouble("temperature_2m")) + "° " + WeatherData.condition(code);
                if (code == 0 || code == 1) card.asset("sun", x, y, 40, 40);
                else if (code == 2) partlyCloudy(card, x, y);
                else card.dots(glyph(code), x, y, 40, Card.FOREGROUND);
                if (data.getBoolean("stale")) {
                    description += ", last update (stale)";
                }
            } else {
                String state = data.getString("error").isEmpty() ? "Weather" : "Offline";
                // Muted weather placeholder; status stays in accessibility/Agent data only.
                card.dots(glyph(3), x, y, 40, Card.MUTED);
                description = city + " weather " + state;
            }
            AppWidgetManager.getInstance(c).updateAppWidget(id, card.views(c, description, new Intent(c, MainActivity.class)));
        } catch (Exception e) { android.util.Log.e("TakuWeather", "widget render", e); }
    }
    /** Keep the original sun asset, with a foreground dot cloud inside the same 40dp slot. */
    private static void partlyCloudy(Card card, float x, float y) {
        String[] cloud = glyph(3);
        // Leave a small gap around the cloud without repainting the translucent card.
        android.graphics.Path cloudMask = new android.graphics.Path();
        for (int row = 0; row < cloud.length; row++) for (int col = 0; col < cloud[row].length(); col++)
            if (cloud[row].charAt(col) == 'X')
                cloudMask.addCircle(x + 8 + 2.8f + 3.2f * col, y + 12 + 2.8f + 3.2f * row, 2.4f, android.graphics.Path.Direction.CW);
        int save = card.canvas.save();
        card.canvas.clipOutPath(cloudMask);
        card.asset("sun", x, y, 26, 26);
        card.canvas.restoreToCount(save);
        card.dots(cloud, x + 8, y + 12, 32, Card.FOREGROUND);
    }
    /** Live weather symbols follow the existing PixelGlyphs dot grammar; never show a sample sun. */
    private static String[] glyph(int code) {
        if (code == 3) return new String[]{".........", "...XXX...", "..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", "XXXXXXXXX", ".XXXXXXX.", ".........", "........."};
        if (code <= 48) return new String[]{".........", ".XXXXXXX.", ".........", "XXXXXXXXX", ".........", ".XXXXXXX.", ".........", "XXXXXXXXX", "........."};
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new String[]{"....X....", ".X..X..X.", "..X.X.X..", "...XXX...", "XXXXXXXXX", "...XXX...", "..X.X.X..", ".X..X..X.", "....X...."};
        if (code >= 95) return new String[]{"...XXX...", "..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", ".XXXXXXX.", "....XX...", "...XXX...", "....X....", "...X....."};
        return new String[]{"...XXX...", "..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", ".XXXXXXX.", ".........", "..X.X.X..", ".X.X.X...", "........."};
    }
}
