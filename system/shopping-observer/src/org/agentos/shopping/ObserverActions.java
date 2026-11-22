// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Ephemeral callbacks held by the active runtime; notifications cannot revive an old attempt. */
public final class ObserverActions extends BroadcastReceiver {
    private static final String ACTION = "org.agentos.shopping.NOTIFICATION_ACTION";
    private static final Map<String, Map<String, Runnable>> CALLBACKS = new HashMap<>();
    static String register(Map<String, Runnable> callbacks) {
        String key = UUID.randomUUID().toString(); CALLBACKS.put(key, callbacks); return key;
    }
    static void clear(String key) { if (key != null) CALLBACKS.remove(key); }
    static PendingIntent pending(Context context, String key, String action) {
        Intent intent = new Intent(context, ObserverActions.class).setAction(ACTION)
            .setData(new Uri.Builder().scheme("taku-observer").authority("action").appendPath(key).appendPath(action).build());
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction()) || intent.getData() == null) return;
        var parts = intent.getData().getPathSegments();
        if (parts.size() != 2) return;
        Map<String, Runnable> actions = CALLBACKS.get(parts.get(0));
        Runnable callback = actions == null ? null : actions.get(parts.get(1));
        if (callback != null) callback.run();
    }
}
