// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.common;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Publishes data/actions through main's notification/island contract; owns no camera or business state. */
public final class IslandPublisher {
    public record Action(String id, String label, PendingIntent intent) {
        public Action {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,31}") || label == null
                    || label.isBlank() || label.length() > 80 || intent == null)
                throw new IllegalArgumentException("invalid_notification_action");
        }
    }

    private final Context context;
    private final String channelId;

    public IslandPublisher(Context context, String channelId, String channelName) {
        if (context == null || channelId == null || channelId.isBlank() || channelName == null || channelName.isBlank())
            throw new IllegalArgumentException("invalid_notification_channel");
        this.context = context.getApplicationContext();
        this.channelId = channelId;
        NotificationManager manager = this.context.getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(channelId, channelName,
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    public boolean post(String id, String compact, String title, String body, boolean ongoing,
            List<Action> actions, String primaryActionId, PendingIntent open, boolean alert) {
        return post(id, compact, title, body, ongoing, actions, primaryActionId, open, alert, null);
    }

    /** The existing SystemUI rich-card host owns placement/touch; the app supplies card content. */
    public boolean postCard(String id, String title, String body, List<Action> actions,
            PendingIntent open, String service, String token, int heightDp) {
        if (service == null || !service.startsWith(context.getPackageName() + "/")
                || token == null || token.isBlank() || token.length() > 256 || heightDp < 96 || heightDp > 340)
            throw new IllegalArgumentException("invalid_island_content");
        try {
            JSONObject content = new JSONObject().put("service", service).put("token", token)
                    .put("layout", "card").put("heightDp", heightDp);
            return post(id, "Try-on stopped", title, body, false, actions, null, open, true, content);
        } catch (org.json.JSONException invalid) { return false; }
    }

    private boolean post(String id, String compact, String title, String body, boolean ongoing,
            List<Action> actions, String primaryActionId, PendingIntent open, boolean alert, JSONObject content) {
        if (id == null || id.isBlank() || id.length() > 160 || actions == null || actions.size() > 4)
            throw new IllegalArgumentException("invalid_notification");
        Set<String> identifiers = new HashSet<>();
        for (Action action : actions) {
            if (action == null || !identifiers.add(action.id()))
                throw new IllegalArgumentException("duplicate_notification_action");
            if (!context.getPackageName().equals(action.intent().getCreatorPackage()))
                throw new IllegalArgumentException("foreign_notification_action");
        }
        if (open != null && !context.getPackageName().equals(open.getCreatorPackage()))
            throw new IllegalArgumentException("foreign_notification_action");
        if (primaryActionId != null && !primaryActionId.isEmpty() && !identifiers.contains(primaryActionId))
            throw new IllegalArgumentException("missing_primary_action");
        try {
            String compactText = bounded(compact == null || compact.isBlank() ? "Try-on" : compact, 16);
            String headline = bounded(title == null || title.isBlank() ? "Try-on" : title, 80);
            // Also allow for JSON's six-byte escaping of control characters.
            String detail = bounded(body, 1000);
            JSONObject expanded = new JSONObject().put("template", "text").put("title", headline).put("body", detail);
            if (content != null) expanded.put("content", content);
            Bundle actionBundle = new Bundle(); JSONArray actionIds = new JSONArray();
            Notification.Builder builder = new Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(headline).setContentText(detail)
                    .setStyle(new Notification.BigTextStyle().bigText(detail)).setOngoing(ongoing)
                    .setOnlyAlertOnce(!alert).setVisibility(Notification.VISIBILITY_PRIVATE);
            if (open != null) builder.setContentIntent(open);
            for (Action action : actions) {
                Notification.Action nativeAction = new Notification.Action.Builder((Icon) null, action.label(), action.intent()).build();
                builder.addAction(nativeAction); actionBundle.putParcelable(action.id(), nativeAction); actionIds.put(action.id());
            }
            expanded.put("actions", actionIds);
            if (primaryActionId != null && !primaryActionId.isEmpty()) expanded.put("primaryAction", primaryActionId);
            JSONObject leading = new JSONObject().put("type", "pic").put("pic", "taku_dots");
            if (ongoing) leading.put("animated", "working");
            JSONObject payload = new JSONObject().put("version", 1).put("category", "progress")
                    .put("bigIsland", new JSONObject()
                            .put("leading", leading)
                            .put("trailing", new JSONObject().put("type", "text").put("text", compactText)))
                    .put("expanded", expanded).put("islandTimeoutS", 120).put("alert", alert);
            String wire = payload.toString();
            if (wire.getBytes(StandardCharsets.UTF_8).length > 8192) return false;
            Bundle extras = new Bundle(); extras.putString("taku.island.v1", wire);
            extras.putBundle("taku.island.actions", actionBundle);
            // Every payload pic reference must have an Icon, including compact Taku dots.
            int mark = context.getResources().getIdentifier("tryon_island_mark", "drawable", context.getPackageName());
            Bundle pictures = new Bundle(); pictures.putParcelable("taku_dots", mark == 0
                    ? Icon.createWithResource("android", android.R.drawable.ic_menu_camera)
                    : Icon.createWithResource(context, mark));
            extras.putBundle("taku.island.pics", pictures); builder.addExtras(extras);
            // SDK 36 lacks the public builder method; main's Android 17 host supports it.
            try { Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(builder, ongoing); }
            catch (ReflectiveOperationException | RuntimeException unsupported) { /* Normal notification fallback. */ }
            try { Notification.Builder.class.getMethod("setShortCriticalText", String.class).invoke(builder, compactText); }
            catch (ReflectiveOperationException | RuntimeException unsupported) { /* Normal notification fallback. */ }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return false;
            manager.notify(id, 1, builder.build());
            return true;
        } catch (org.json.JSONException | RuntimeException unavailable) {
            return false;
        }
    }

    public void dismiss(String id) {
        if (id == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(id, 1);
    }

    private static String bounded(String text, int maximum) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= maximum ? text : text.substring(0, text.offsetByCodePoints(0, maximum));
    }
}
