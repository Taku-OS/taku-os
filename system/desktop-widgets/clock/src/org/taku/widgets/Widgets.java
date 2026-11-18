package org.taku.widgets;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.*;
import android.view.View;
import android.widget.RemoteViews;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.taku.widgetkit.Card;

public final class Widgets {
    public static final Class<?>[] TYPES = {ClockWidget.class, AlarmWidget.class, DateWidget.class, DayWidget.class, MusicWidget.class};
    public static void updateAll(Context c) { for (Class<?> type : TYPES) update(c, type); ClockData.schedule(c); }
    public static void update(Context c, Class<?> type) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, type))) render(c, m, id, type.getSimpleName());
    }
    static void render(Context c, AppWidgetManager manager, int id, String kind) {
        Card card = new Card(c, id);
        float w = card.w, h = card.h;
        ZonedDateTime now = ZonedDateTime.now();
        Intent click = switch (kind) {
            case "AlarmWidget" -> ClockData.actionIntent(c, "alarms");
            case "DateWidget" -> ClockData.actionIntent(c, "calendar");
            case "MusicWidget" -> ClockData.actionIntent(c, "music");
            default -> ClockData.actionIntent(c, "clock");
        };
        String description = kind;
        if (kind.equals("ClockWidget")) {
            // Original 182dp Figma clock face, with the SVG's 3.964dp overscan preserved.
            float k = Math.min(w, h) / 182, left = (w - 182 * k) / 2, top = (h - 182 * k) / 2;
            card.asset("clock_center", left + 24 * k, top + 24 * k, 134 * k, 134 * k);
            card.asset("clock_ticks", left - 3.964f * k, top - 3.964f * k, 189.928f * k, 189.928f * k);
            card.text(now.format(DateTimeFormatter.ofPattern("HH")), left + 53.5f * k, top + 37 * k, 48 * k, 50.4f * k, Card.INK, false, true);
            card.text(now.format(DateTimeFormatter.ofPattern("mm")), left + 69.5f * k, top + 95 * k, 48 * k, 50.4f * k, Card.INK, false, true);
            description = "Clock " + now.format(DateTimeFormatter.ofPattern("HH:mm"));
        } else if (kind.equals("DateWidget")) {
            float top = (h - 55.48f) / 2;
            card.text(now.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).toUpperCase(Locale.ENGLISH), w / 2, top, 14, 19.88f, Card.INK, true, false);
            card.text(Integer.toString(now.getDayOfMonth()), w / 2, top + 21.88f, 32, 33.6f, Card.INK, true, true);
            description = now.toLocalDate().toString();
        } else if (kind.equals("AlarmWidget")) {
            AlarmManager.AlarmClockInfo alarm = c.getSystemService(AlarmManager.class).getNextAlarmClock();
            String time = alarm == null ? "--:--" : Instant.ofEpochMilli(alarm.getTriggerTime()).atZone(now.getZone()).format(DateTimeFormatter.ofPattern("HH:mm"));
            // Default 182×83: 12dp padding, Heading/2xl, 40×24 track and 16dp knob.
            boolean compact = w < 160;
            float size = compact ? 24 : 32;
            float centerY = compact ? h / 2 + 18 : h / 2;
            float trackLeft = compact ? (w - 40) / 2 : w - 52;
            card.text(time, compact ? w / 2 : 12, compact ? h / 2 - 30 : (h - 33.6f) / 2,
                size, size * 1.05f, Card.INK, compact, true);
            card.paint.setColor(alarm == null ? 0x14000000 : Card.FOREGROUND);
            card.canvas.drawRoundRect(trackLeft, centerY - 12, trackLeft + 40, centerY + 12, 12, 12, card.paint);
            card.circle(trackLeft + (alarm == null ? 12 : 28), centerY, 8, android.graphics.Color.WHITE);
            description = alarm == null ? "No alarm set" : "Next alarm " + time;
        } else if (kind.equals("DayWidget")) {
            long seconds = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).getSeconds();
            long hours = (seconds + 3599) / 3600;
            float pitch = Math.min(18, Math.min((w - 48) / 6, (h - 80) / 4)), r = pitch / 3;
            float startX = w / 2 - 2.5f * pitch, startY = (h - 150) / 2 + 36;
            for (int i = 0; i < 24; i++) card.circle(startX + (i % 6) * pitch, startY + (i / 6) * pitch, r,
                i < now.getHour() ? Card.LIGHT : i == now.getHour() ? Card.ORANGE : Card.FOREGROUND);
            description = hours + " " + (hours == 1 ? "hour" : "hours") + " left today";
            card.text(hours + " " + (hours == 1 ? "hour" : "hours") + " left", w / 2, h - 54, 20, 21, Card.INK, true, true);
        } else if (kind.equals("MusicWidget")) {
            MediaController media = ClockData.media();
            MediaMetadata meta = media == null ? null : media.getMetadata();
            String title = meta == null ? "Not playing" : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null || title.isEmpty()) title = "Unknown track";
            String artist = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist != null && !artist.isEmpty()) title += " - " + artist;
            float art = Math.min(80, Math.min(w - 40, h - 100));
            float k = art / 80;
            card.asset("vinyl", 12 + 40 * k, 12 + 12 * k, 56 * k, 56 * k);
            Bitmap bitmap = meta == null ? null : meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bitmap == null && meta != null) bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            card.album(bitmap, 12, 12, art);
            card.title(title, 12, 12 + art + 8, w - 24);
            description = "Music: " + title;
        }
        RemoteViews views = card.views(c, description, click);
        if (kind.equals("MusicWidget")) {
            views.setViewVisibility(R.id.controls, View.VISIBLE);
            MediaController media = ClockData.media();
            PlaybackState state = media == null ? null : media.getPlaybackState();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            long available = state == null ? 0 : state.getActions();
            int[] ids = {R.id.previous, R.id.play, R.id.next};
            String[] actions = {"previous", "play-pause", "next"};
            String[] icons = {"skip_back", playing ? "pause" : "play", "skip_forward"};
            boolean[] enabled = {(available & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0, true,
                (available & PlaybackState.ACTION_SKIP_TO_NEXT) != 0};
            for (int i = 0; i < ids.length; i++) {
                views.setImageViewBitmap(ids[i], Card.control(c, icons[i], i == 1, enabled[i]));
                views.setBoolean(ids[i], "setEnabled", enabled[i]);
                // An activity PendingIntent lets Play open the music app even if playback
                // stopped since this RemoteViews was rendered. Background broadcasts cannot.
                views.setOnClickPendingIntent(ids[i], i == 1
                    ? PendingIntent.getActivity(c, i + 10, ClockData.actionIntent(c, actions[i]), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.getBroadcast(c, i + 10, new Intent(c, RefreshReceiver.class).setAction(actions[i]), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            views.setContentDescription(R.id.play, playing ? "Pause" : "Play");
        }
        manager.updateAppWidget(id, views);
    }
}
