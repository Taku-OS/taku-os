package org.agentos.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

/** Explicit development test trigger. Never injects, intercepts or remaps KEYCODE_POWER. */
public final class JevTestTrigger extends BroadcastReceiver {
    private static volatile String pendingToken;
    private static volatile long expiresAt;

    @Override public void onReceive(Context context, Intent intent) {
        boolean manual="org.agentos.assistant.MANUAL_SCREEN_SUGGESTION".equals(intent.getAction());
        if (!manual && !"org.agentos.assistant.TEST_JEV_SIMULATED_POWER".equals(intent.getAction())) return;
        if (!manual && !context.getSharedPreferences("agent-settings", 0).getBoolean("jevEnabled", false)) {
            Toast.makeText(context, "Enable Jev in Agent settings first", Toast.LENGTH_SHORT).show();
            return;
        }
        if ((pendingToken != null && SystemClock.elapsedRealtime() < expiresAt)
                || !AssistantSession.prepareJevTestCapture()) {
            Toast.makeText(context, "Close the assistant first, then simulate the trigger again", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingToken = java.util.UUID.randomUUID().toString();
        expiresAt = SystemClock.elapsedRealtime() + 10000;
        try {
            org.json.JSONObject request = new org.json.JSONObject().put("token", pendingToken).put("manual", manual);
            Bundle response = context.getContentResolver().call(
                    android.net.Uri.parse("content://org.agentos.agent.home-assistant"),
                    "system/capture", request.toString(), null);
            org.json.JSONObject result = new org.json.JSONObject(response == null ? "{}" : response.getString("result", "{}"));
            if (!result.optBoolean("ok")) throw new IllegalStateException(result.optString("code", "UNAVAILABLE"));
        } catch (Exception failure) {
            pendingToken = null;
            android.util.Log.w("TakuCapture", "New system assistant unavailable", failure);
            Toast.makeText(context, "Taku system assistant is temporarily unavailable. Retry later", Toast.LENGTH_SHORT).show();
        }
    }

    static boolean pending(String token) {
        return token != null && token.equals(pendingToken) && SystemClock.elapsedRealtime() < expiresAt;
    }

    static boolean consume(Bundle args) {
        String token = args == null ? null : args.getString("jevTestToken");
        boolean valid = token != null && token.equals(pendingToken)
                && SystemClock.elapsedRealtime() < expiresAt;
        if (valid) pendingToken = null;
        return valid;
    }
}
