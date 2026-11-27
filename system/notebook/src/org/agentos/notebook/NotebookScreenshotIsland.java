package org.agentos.notebook;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookScreenshotIsland {
    static final Uri URI = Uri.parse("content://com.android.systemui.island");
    static final int LIFETIME_SECONDS = 13;
    private static final String CHANNEL = "notebook-screenshot-actions";
    private static final String FALLBACK_CHANNEL = "notebook-screenshot-notifications";
    private static final int NOTIFICATION_ID = 320;
    private static final int RECEIPT_NOTIFICATION_ID = 321;

    record Action(String id, String label) {}

    private final Context context;
    private final NotificationManager notifications;

    NotebookScreenshotIsland(Context context) {
        this.context = context;
        notifications = context.getSystemService(NotificationManager.class);
        boolean chinese = "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                chinese ? "截图操作" : "Screenshot actions", NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(FALLBACK_CHANNEL,
                chinese ? "截图保存" : "Save screenshots", NotificationManager.IMPORTANCE_HIGH));
    }

    static boolean canNotify(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        var channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static Intent settingsIntent(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        String action = !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL) == null
                ? Settings.ACTION_APP_NOTIFICATION_SETTINGS : Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS;
        return new Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
    }

    String hostStatus() {
        JSONObject capabilities = call("capabilities", null);
        if (!capabilities.optBoolean("ok")) return "systemui_unavailable";
        if (!capabilities.optBoolean("presentationAttached")) return "presentation_unavailable";
        JSONObject access = call("canShowIsland", context.getPackageName());
        if (!access.optBoolean("ok")) return "systemui_unavailable";
        if (!access.optBoolean("enabled")) return "island_disabled";
        if (!access.optBoolean("customAllowed")) return "publisher_not_allowed";
        return "available";
    }

    JSONObject state() {
        return call("state", null);
    }

    boolean expand(String key, String tag, boolean reopenBelow) {
        if (!matches(key, tag)) return false;
        if (reopenBelow) {
            JSONObject current = state().optJSONObject("presentation");
            JSONObject card = current == null ? null : current.optJSONObject("card");
            if (card != null && key.equals(card.optString("key")) && !card.optBoolean("cover")) {
                call("collapse", null);
            }
        }
        return call("expand", key).optBoolean("ok");
    }

    private JSONObject call(String method, String argument) {
        try {
            Bundle result = context.getContentResolver().call(URI, method, argument, null);
            String json = result == null ? null : result.getString("json");
            if (json != null) return new JSONObject(json);
        } catch (RuntimeException | JSONException unavailable) {
            // A stock or starting SystemUI need not expose this provider.
        }
        return new JSONObject();
    }

    boolean matches(String key, String tag) {
        // The UID already includes the Android user. Match the rest of StatusBarNotification.key.
        String suffix = "|" + tag + "|" + Process.myUid();
        return key.endsWith("|" + context.getPackageName() + "|" + NOTIFICATION_ID + suffix)
                || key.endsWith("|" + context.getPackageName() + "|" + RECEIPT_NOTIFICATION_ID + suffix);
    }

    void post(String tag, String title, String body, List<Action> actions,
              PendingIntent open, long timeoutMs) throws JSONException {
        boolean receipt = actions.isEmpty() && open != null;
        String compactTitle = actions.isEmpty() && !receipt ? "Reading image" : title;
        JSONArray actionIds = new JSONArray(actions.stream().map(Action::id).toList());
        JSONObject expanded = new JSONObject().put("template", "basic")
                .put("title", truncate(title, 80)).put("actions", actionIds)
                .put("backgroundColor", "#101014");
        if (!body.isBlank()) expanded.put("body", truncate(body, 500));
        // Use SystemUI's public native card. The assistant template belongs to Taku Agent.
        JSONObject payload = new JSONObject().put("version", 2).put("expanded", expanded)
                .put("cardPosition", "cover")
                .put("tap", "expand").put("alert", false).put("timeoutS", LIFETIME_SECONDS);
        if (actions.isEmpty() && !receipt) {
            payload.put("compact", new JSONObject().put("b", new JSONObject()
                    .put("type", "text").put("text", truncate(compactTitle, 16))));
        }
        String channel = "available".equals(hostStatus()) ? CHANNEL : FALLBACK_CHANNEL;
        Notification.Builder notification = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_taku_bookmark).setCategory(Notification.CATEGORY_PROGRESS)
                .setContentTitle(title).setContentText(body).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_SECRET).setLocalOnly(true).setShowWhen(false)
                .setOnlyAlertOnce(true).setOngoing(true).setTimeoutAfter(timeoutMs);
        Bundle actionBundle = new Bundle();
        for (Action option : actions) {
            Notification.Action action = new Notification.Action.Builder((Icon) null, option.label(),
                    actionIntent(tag, option.id())).build();
            actionBundle.putParcelable(option.id(), action);
            notification.addAction(action);
        }
        Bundle extras = new Bundle();
        extras.putString("taku.island.v2", payload.toString());
        extras.putBundle("taku.island.actions", actionBundle);
        if (receipt) notifications.cancel(tag, NOTIFICATION_ID);
        notifications.notify(tag, receipt ? RECEIPT_NOTIFICATION_ID : NOTIFICATION_ID,
                notification.addExtras(extras).build());
    }

    PendingIntent actionIntent(String tag, String action) {
        if ("save".equals(action)) {
            Intent intent = new Intent(context, NotebookScreenshotSaveReceiver.class)
                    .setAction(action).setIdentifier(tag);
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        }
        Intent intent = new Intent(context, NotebookScreenshotActionActivity.class)
                .setAction(action).setIdentifier(tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return activityIntent(intent);
    }

    PendingIntent receiptIntent(String tag, String entryId) {
        return activityIntent(NotebookLibraryActivity.detailIntent(context, entryId).setIdentifier(tag));
    }

    private PendingIntent activityIntent(Intent intent) {
        var options = ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE, options.toBundle());
    }

    void cancel(String tag) {
        notifications.cancel(tag, NOTIFICATION_ID);
        notifications.cancel(tag, RECEIPT_NOTIFICATION_ID);
    }

    static String truncate(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit ? text
                : text.substring(0, text.offsetByCodePoints(0, limit - 1)) + "…";
    }
}
