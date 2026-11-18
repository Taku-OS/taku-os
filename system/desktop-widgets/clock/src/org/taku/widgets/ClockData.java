package org.taku.widgets;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import android.provider.AlarmClock;
import java.time.*;
import java.util.*;
import org.json.*;

public final class ClockData {
    private static volatile MediaController media;
    private static Context context;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { Widgets.update(context, MusicWidget.class); }
        @Override public void onPlaybackStateChanged(PlaybackState p) { Widgets.update(context, MusicWidget.class); }
        @Override public void onSessionDestroyed() { bind(null); }
    };
    public static void init(Context c) {
        context = c.getApplicationContext();
        MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
        try { m.addOnActiveSessionsChangedListener(ClockData::choose, null, main); choose(m.getActiveSessions(null)); }
        catch (SecurityException e) { android.util.Log.w("TakuClock", "Media control permission unavailable", e); }
    }
    private static void choose(List<MediaController> controllers) {
        MediaController next = null;
        for (MediaController m: controllers) { if (next==null) next=m; if (m.getPlaybackState()!=null && m.getPlaybackState().getState()==PlaybackState.STATE_PLAYING) { next=m; break; } }
        bind(next);
    }
    private static void bind(MediaController next) {
        if (media != null) media.unregisterCallback(callback);
        media=next;
        if (media!=null) media.registerCallback(callback,main);
        if (context!=null) Widgets.update(context, MusicWidget.class);
    }
    public static MediaController media() { return media; }
    public static JSONObject read(Context c) throws JSONException {
        ZonedDateTime now=ZonedDateTime.now();
        long seconds=Duration.between(now,now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).getSeconds();
        AlarmManager.AlarmClockInfo alarm=c.getSystemService(AlarmManager.class).getNextAlarmClock();
        JSONObject o=new JSONObject().put("epochMs",System.currentTimeMillis()).put("timezone",now.getZone().getId())
            .put("remainingSeconds",seconds).put("remainingHours",(seconds+3599)/3600).put("nextAlarmEpochMs",alarm==null?JSONObject.NULL:alarm.getTriggerTime());
        MediaController active=media;
        JSONObject music=new JSONObject().put("available",active!=null);
        if(active!=null) { MediaMetadata m=active.getMetadata(); PlaybackState p=active.getPlaybackState();
            music.put("package",active.getPackageName()).put("title",m==null?"":m.getString(MediaMetadata.METADATA_KEY_TITLE))
                .put("artist",m==null?"":m.getString(MediaMetadata.METADATA_KEY_ARTIST)).put("playing",p!=null&&p.getState()==PlaybackState.STATE_PLAYING); }
        return o.put("media",music);
    }
    public static Intent alarmIntent(Context c) {
        Intent i=new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(c.getPackageManager()) == null) i=new Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(c.getPackageManager()) == null) throw new IllegalStateException("NO_ALARM_APPLICATION");
        return i;
    }
    public static final String COMMAND_PREFIX = "org.taku.widgets.command.";
    public static Intent clockIntent(Context c) {
        Intent alarms;
        try { alarms = alarmIntent(c); }
        catch (IllegalStateException missing) { throw new IllegalStateException("NO_CLOCK_APPLICATION"); }
        ComponentName handler = alarms.resolveActivity(c.getPackageManager());
        if (handler != null && !c.getPackageName().equals(handler.getPackageName())) {
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(handler.getPackageName());
            if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        // A resolver is a valid route when several clock apps exist and none is the default.
        // Let Android show that chooser rather than treating its non-launchable package as absence.
        return alarms;
    }
    /** Unique action identity also keeps the widgets' PendingIntents separate. */
    public static Intent actionIntent(Context c, String command) {
        return new Intent(c, WidgetActionActivity.class).setAction(COMMAND_PREFIX + command);
    }
    private static void openCategory(Context c, String category, String error) {
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(c.getPackageManager()) == null) throw new IllegalStateException(error);
        c.startActivity(intent);
    }
    private static void openMusic(Context c) {
        MediaController active = media;
        if (active != null) {
            PendingIntent session = active.getSessionActivity();
            if (session != null) try {
                ActivityOptions options = ActivityOptions.makeBasic();
                // This command is dispatched from the user's foreground widget activity.
                // SDK 34+ no longer lends its visible-launch privilege by default.
                if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(
                    Build.VERSION.SDK_INT >= 36 ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                        : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                session.send(c, 0, null, null, null, null, options.toBundle());
                return;
            } catch (PendingIntent.CanceledException ignored) { }
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(active.getPackageName());
            if (launch != null) { c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
        }
        openCategory(c, Intent.CATEGORY_APP_MUSIC, "NO_MUSIC_APPLICATION");
    }
    public static boolean requiresActivity(String action) {
        return "clock".equals(action) || "alarms".equals(action) || "calendar".equals(action) || "music".equals(action)
            || ("play-pause".equals(action) && media == null);
    }
    public static void command(Context c,String action) {
        if ("clock".equals(action)) { c.startActivity(clockIntent(c)); return; }
        if ("alarms".equals(action)) { c.startActivity(alarmIntent(c)); return; }
        if ("calendar".equals(action)) { openCategory(c, Intent.CATEGORY_APP_CALENDAR, "NO_CALENDAR_APPLICATION"); return; }
        if ("music".equals(action)) { openMusic(c); return; }
        MediaController active=media;
        if (active==null && "play-pause".equals(action)) { openMusic(c); return; }
        if (!"play-pause".equals(action) && !"previous".equals(action) && !"next".equals(action))
            throw new IllegalArgumentException("INVALID_ARGUMENT: unknown widget command");
        if (active==null) throw new IllegalStateException("NO_MEDIA_SESSION");
        MediaController.TransportControls t=active.getTransportControls();
        switch(action) {
            case "play-pause": if(active.getPlaybackState()!=null&&active.getPlaybackState().getState()==PlaybackState.STATE_PLAYING)t.pause();else t.play();break;
            case "previous": t.skipToPrevious();break;
            case "next":t.skipToNext();break;
            default: throw new IllegalArgumentException("INVALID_ARGUMENT: command must be alarms, play-pause, previous or next");
        }
    }
    public static void schedule(Context c) {
        ZonedDateTime next=ZonedDateTime.now().plusMinutes(1).withSecond(0).withNano(0);
        PendingIntent p=PendingIntent.getBroadcast(c,1,new Intent(c,RefreshReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager a=c.getSystemService(AlarmManager.class);
        if(a.canScheduleExactAlarms()) a.setExact(AlarmManager.RTC,next.toInstant().toEpochMilli(),p);
        else a.set(AlarmManager.RTC,next.toInstant().toEpochMilli(),p);
    }
}
