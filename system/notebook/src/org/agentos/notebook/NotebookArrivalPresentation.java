package org.agentos.notebook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;

final class NotebookArrivalPresentation {
    static final String OPEN_POST = "org.agentos.notebook.action.OPEN_ARRIVAL_POST";
    static final String OPEN_SAVED_ITEM = "org.agentos.notebook.action.OPEN_ARRIVAL_SAVED_ITEM";
    static final String OPEN_DIRECTIONS = "org.agentos.notebook.action.OPEN_ARRIVAL_DIRECTIONS";
    static final String ARRIVAL_SCHEME = "notebook-arrival";
    static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "notebook-arrival-suggestions";
    private static final long TTL_MS = 120_000;

    private NotebookArrivalPresentation() {}

    static boolean post(Context context, String eventId, NotebookPlaces.Place place, NotebookStore.Entry entry) {
        boolean chinese = isChinese(context);
        String channelLabel = chinese ? "收藏到达提醒" : "Saved item reminders";
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, channelLabel, NotificationManager.IMPORTANCE_DEFAULT));
        if (!notifications.areNotificationsEnabled()
                || notifications.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        boolean hasPost = NotebookSourceLink.resolve(entry.sourceUrl) != null;
        String title = chinese ? place.name() + "就在附近" : place.name() + " is nearby";
        String details = chinese ? "你曾把这条帖子存到「" + entry.category + "」。"
                : "You saved this in " + entry.category + ".";
        String actionType = hasPost ? OPEN_POST : OPEN_SAVED_ITEM;
        String actionLabel = hasPost ? (chinese ? "查看原帖" : "View post")
                : (chinese ? "查看收藏" : "View saved item");

        long expiresAt = SystemClock.elapsedRealtime() + TTL_MS;
        PendingIntent open = actionIntent(context, eventId, entry.id, place.id(), actionType, expiresAt);
        PendingIntent directions = actionIntent(context, eventId, entry.id, place.id(), OPEN_DIRECTIONS, expiresAt);
        Notification publicVersion = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(channelLabel)
                .build();

        // SystemUI shows these standard fields after authentication, and supplies the public version
        // when its privacy policy requires redaction.
        Notification.Builder notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(title)
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentIntent(open)
                .setTimeoutAfter(TTL_MS)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .addAction(authenticatedAction(actionLabel, open))
                .addAction(authenticatedAction(chinese ? "去这里" : "Go there", directions));
        try {
            Bitmap thumbnail = new NotebookStore(context).previewImage(entry.id, 288);
            if (thumbnail != null) notification.setLargeIcon(thumbnail);
        } catch (IOException unavailable) {
            // Keep the reminder usable when its saved image is missing.
        }
        try {
            notifications.notify(eventId, NOTIFICATION_ID, notification.build());
            return true;
        } catch (SecurityException denied) {
            return false;
        }
    }

    private static Notification.Action authenticatedAction(String label, PendingIntent intent) {
        return new Notification.Action.Builder(null, label, intent).setAuthenticationRequired(true).build();
    }

    private static PendingIntent actionIntent(Context context, String eventId, String entryId, String placeId,
                                              String action, long expiresAt) {
        Intent intent = new Intent(context, NotebookArrivalActionActivity.class).setAction(action)
                .setData(Uri.fromParts(ARRIVAL_SCHEME, eventId, null))
                .putExtra("entryId", entryId).putExtra("placeId", placeId).putExtra("expiresAt", expiresAt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean isChinese(Context context) {
        return "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }
}
