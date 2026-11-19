package org.agentos.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;
import java.io.IOException;
import org.json.JSONObject;

/** Explicit development test trigger. Never injects, intercepts or remaps KEYCODE_POWER. */
public final class JevTestTrigger extends BroadcastReceiver {
    static final class Authorization {
        final String token, captureId;
        final boolean manual;
        final long capturedAt;
        Authorization(boolean manual) {
            token = java.util.UUID.randomUUID().toString();
            captureId = java.util.UUID.randomUUID().toString();
            this.manual = manual;
            capturedAt = SystemClock.elapsedRealtime();
        }
    }
    private static Authorization pending;

    @Override public void onReceive(Context context, Intent intent) {
        boolean manual="org.agentos.assistant.MANUAL_SCREEN_SUGGESTION".equals(intent.getAction());
        if (!manual && !"org.agentos.assistant.TEST_JEV_SIMULATED_POWER".equals(intent.getAction())) return;
        try { request(context, manual); }
        catch (Exception failure) {
            Toast.makeText(context, "Screen capture could not start (" + failure.getMessage() + ")", Toast.LENGTH_LONG).show();
        }
    }

    static JSONObject request(Context context, boolean manual) throws Exception {
        String userError = ComposerState.userError(context, android.os.Process.myUid());
        if (userError != null) throw new IOException(userError);
        if (!manual && !context.getSharedPreferences("agent-settings", 0).getBoolean("jevEnabled", false))
            throw new IOException("JEV_DISABLED");
        Authorization authorization;
        synchronized (JevTestTrigger.class) {
            if (pending != null && SystemClock.elapsedRealtime() - pending.capturedAt < 10000)
                throw new IOException("CAPTURE_BUSY");
            authorization = new Authorization(manual);
            pending = authorization;
        }
        try {
            JSONObject snapshot = AssistantCaptureBackend.begin(context, authorization.captureId, manual, authorization.capturedAt);
            JSONObject request = new JSONObject().put("token", authorization.token).put("manual", manual);
            Bundle response = context.getContentResolver().call(
                    android.net.Uri.parse("content://org.agentos.agent.home-assistant"),
                    "system/capture", request.toString(), null);
            JSONObject result = new JSONObject(response == null ? "{}" : response.getString("result", "{}"));
            if (!result.optBoolean("ok")) throw new IOException("CAPTURE_UNAVAILABLE");
            return snapshot;
        } catch (Exception failure) {
            synchronized (JevTestTrigger.class) { if (pending == authorization) pending = null; }
            AssistantCaptureBackend.unavailable(authorization.captureId);
            throw failure;
        }
    }

    static synchronized boolean pending(String token) {
        return pending != null && pending.token.equals(token)
                && SystemClock.elapsedRealtime() - pending.capturedAt < 10000;
    }

    static synchronized Authorization consume(String token) throws IOException {
        if (!pending(token)) throw new IOException("CAPTURE_EXPIRED");
        Authorization authorization = pending;
        pending = null;
        return authorization;
    }
}
