package org.agentos.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Window;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Explicit advanced controls, never a HOME or ASSIST candidate. Keeps native state in its owner. */
public final class NativeControlsActivity extends Activity {
    private static final LinkedHashMap<String, Capture> CAPTURES = new LinkedHashMap<>();
    private static final class Capture {
        final Bitmap image;
        final long created = SystemClock.elapsedRealtime();
        Capture(Bitmap value) { image = value; }
    }
    private NativeAssistantSurface surface;

    static void open(Context context, Bundle args, Bitmap capture) {
        Intent intent = new Intent(context, NativeControlsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (args != null) intent.putExtras(args);
        String token = null;
        if (capture != null) {
            token = UUID.randomUUID().toString();
            synchronized (CAPTURES) {
                CAPTURES.entrySet().removeIf(e -> SystemClock.elapsedRealtime() - e.getValue().created > 15000);
                while (CAPTURES.size() >= 4) CAPTURES.remove(CAPTURES.keySet().iterator().next());
                CAPTURES.put(token, new Capture(capture));
            }
            intent.putExtra("nativeCapture", token);
        }
        try { context.startActivity(intent); }
        catch (RuntimeException failure) {
            synchronized (CAPTURES) { CAPTURES.remove(token); }
            throw failure;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // A consumed capture cannot be replayed after process death as an ordinary chat request.
        if (state != null && getIntent().hasExtra("jevTestToken") &&
                !JevTestTrigger.pending(getIntent().getStringExtra("jevTestToken"))) { finish(); return; }
        surface = new NativeAssistantSurface(this, new NativeAssistantSurface.Host() {
            public Window window() { return getWindow(); }
            public void hide() { finish(); }
            public void launch(Intent intent) { startActivity(intent); }
        });
        Bundle args = getIntent().getExtras() == null ? new Bundle() : new Bundle(getIntent().getExtras());
        String token = args.getString("nativeCapture");
        args.remove("nativeCapture");
        surface.onPrepareShow(args, 0);
        setContentView(surface.onCreateContentView());
        if (token != null) {
            Capture capture;
            synchronized (CAPTURES) { capture = CAPTURES.remove(token); }
            if (capture != null && SystemClock.elapsedRealtime() - capture.created <= 15000)
                surface.onHandleScreenshot(capture.image);
        }
        surface.onShow(args, 0);
    }
    @Override public void onBackPressed() { if (surface != null) surface.onBackPressed(); else finish(); }
    @Override public void onStart() { super.onStart(); if (surface != null) surface.setHostVisible(true); }
    @Override public void onStop() { if (surface != null) surface.setHostVisible(false); super.onStop(); }
    @Override public void onDestroy() {
        if (surface != null) { surface.onHide(); surface.onDestroy(); }
        super.onDestroy();
    }
}
