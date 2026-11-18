/* Copyright (C) 2026 Taku. Licensed under the Apache License, Version 2.0. */
package com.android.quickstep;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;
import com.android.internal.app.AssistUtils;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordered lifecycle bridge; UI activation is a separate handshake, so a late launch cannot open mic. */
public final class TakuNavHandleBridge {
    private static final Uri URI = Uri.parse("content://org.agentos.agent.home-assistant");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Context context;
    private final String id = UUID.randomUUID().toString();
    private long sequence;
    private boolean started;
    private boolean closed;
    private long lastMove;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (closed || !started) return;
            if (!isSelected(context) || locked(context)) { finish(true); return; }
            send("heartbeat", null);
            MAIN.postDelayed(this, 500);
        }
    };

    public TakuNavHandleBridge(Context context) { this.context = context.getApplicationContext(); }

    public static boolean isSelected(Context context) {
        ComponentName assistant = new AssistUtils(context).getAssistComponentForUser(UserHandle.myUserId());
        return assistant != null && "org.agentos.agent".equals(assistant.getPackageName());
    }
    public static boolean locked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isKeyguardLocked();
    }

    /** Called only after Quickstep has pilfered pointers and cancelled its navigation delegate. */
    public void begin() {
        if (closed || started || !isSelected(context) || locked(context)) return;
        started = true;
        final long time = SystemClock.uptimeMillis();
        IO.execute(() -> {
            try {
                JSONObject request = new JSONObject().put("id", id).put("action", "prepare")
                        .put("source", "system").put("time", time);
                call(request);
                MAIN.post(() -> {
                    if (closed || !isSelected(context) || locked(context)) { finish(true); return; }
                    Bundle args = new Bundle();
                    args.putInt(AssistUtils.INVOCATION_TYPE_KEY, AssistUtils.INVOCATION_TYPE_GESTURE);
                    args.putString("org.agentos.assistant.extra.TRIGGER", "nav_handle_long_press");
                    args.putString("org.agentos.assistant.extra.GESTURE_ID", id);
                    SystemUiProxy.INSTANCE.get(context).startAssistant(args);
                });
            } catch (Exception failure) { unavailable(failure); }
        });
        MAIN.postDelayed(heartbeat, 500);
    }

    public void move(MotionEvent event) {
        if (closed || !started || event.getEventTime() - lastMove < 50) return;
        lastMove = event.getEventTime();
        send("move", event);
    }

    /** The final coordinates are sent before END even when the last MOVE was coalesced. */
    public void end(MotionEvent event) {
        if (!closed && started) send("move", event);
        finish(false);
    }

    public void finish(boolean cancelled) {
        if (closed) return;
        closed = true;
        MAIN.removeCallbacks(heartbeat);
        if (started) send(cancelled ? "cancel" : "end", null);
    }

    private void send(String action, MotionEvent event) {
        final JSONObject p = new JSONObject();
        try {
            p.put("id", id).put("action", action).put("sequence", ++sequence)
                    .put("time", SystemClock.uptimeMillis());
            if (event != null) {
                android.graphics.Rect bounds = context.getSystemService(android.view.WindowManager.class)
                        .getMaximumWindowMetrics().getBounds();
                p.put("x", event.getRawX()).put("y", event.getRawY())
                        .put("width", bounds.width()).put("height", bounds.height());
            }
        } catch (Exception failure) { unavailable(failure); return; }
        IO.execute(() -> { try { call(p); } catch (Exception failure) { unavailable(failure); } });
    }

    private void call(JSONObject p) throws Exception {
        Bundle response = context.getContentResolver().call(URI, "gesture/event", p.toString(), null);
        JSONObject result = new JSONObject(response == null ? "{}" : response.getString("result", "{}"));
        if (!result.optBoolean("ok")) throw new IllegalStateException(result.optString("code", "UNAVAILABLE"));
    }

    private void unavailable(Exception failure) {
        Log.w("TakuNavHandle", "Taku voice entry unavailable", failure);
        MAIN.post(() -> {
            if (!closed) Toast.makeText(context, "Taku is unavailable. Please try again.", Toast.LENGTH_SHORT).show();
            finish(true); // No Google fallback. The peer lease also expires if IPC is broken.
        });
    }
}
