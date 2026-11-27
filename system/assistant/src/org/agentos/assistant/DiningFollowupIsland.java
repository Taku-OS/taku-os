package org.agentos.assistant;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.BooleanSupplier;

/** Card-only Island v2 presentation for the native confirmation notifications. */
final class DiningFollowupIsland {
    static final String EXTRA = "taku.island.v2";
    private static final String ISLAND = "content://com.android.systemui.island";

    private DiningFollowupIsland() {}

    static JSONObject payload(String title, String body, JSONArray actions, String primary)
            throws Exception {
        JSONObject expanded =
                new JSONObject()
                        .put("template", "basic")
                        .put("title", title)
                        .put("body", body)
                        .put("actions", actions);
        if (primary != null) expanded.put("primaryAction", primary).put("tapAction", primary);
        return new JSONObject().put("version", 2).put("timeoutS", 28_800).put("expanded", expanded);
    }

    static void expand(Context context, String tag, int id, BooleanSupplier stillCurrent) {
        expand(context.getApplicationContext(), tag, id, stillCurrent, 0);
    }

    private static void expand(
            Context context, String tag, int id, BooleanSupplier stillCurrent, int attempt) {
        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            if (!stillCurrent.getAsBoolean()) return;
                            try {
                                NotificationManager manager =
                                        context.getSystemService(NotificationManager.class);
                                for (var notification : manager.getActiveNotifications()) {
                                    if (notification.getId() != id
                                            || !tag.equals(notification.getTag())
                                            || !context.getPackageName()
                                                    .equals(notification.getPackageName()))
                                        continue;
                                    Bundle response =
                                            context.getContentResolver()
                                                    .call(
                                                            Uri.parse(ISLAND),
                                                            "expand",
                                                            notification.getKey(),
                                                            null);
                                    if (response != null
                                            && new JSONObject(response.getString("json", "{}"))
                                                    .optBoolean("ok")) return;
                                }
                            } catch (Exception unavailable) {
                                // SystemUI may be starting or has not observed the notification
                                // yet.
                            }
                            if (attempt < 20) expand(context, tag, id, stillCurrent, attempt + 1);
                        },
                        attempt == 0 ? 50 : 150);
    }
}
