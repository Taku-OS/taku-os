// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.view.Display;
import android.view.SurfaceControlViewHost;

/** Signature-bound implementation of the existing island Messenger v1 content contract. */
public final class TryOnResultContentService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceControlViewHost host;
    private IBinder client;
    private IBinder.DeathRecipient death;
    private int generation;
    private long epoch;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (!systemUi(message.sendingUid)) return;
            if (message.what == 2) {
                if (message.arg1 == generation && message.replyTo != null && client != null
                        && client.equals(message.replyTo.getBinder())) release();
                return;
            }
            if (message.what != 1 || message.replyTo == null) return;
            release(); generation = message.arg1;
            int request = generation; Messenger target = message.replyTo;
            long attachment = ++epoch;
            try {
                Bundle data = message.getData(); String token = data.getString("token");
                TryOnResultWindow card = TryOnResultWindow.current(token);
                int width = data.getInt("width"), height = data.getInt("height");
                if (card == null || data.getInt("version") != 1 || data.getBinder("hostToken") == null
                        || width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 4_194_304)
                    throw new IllegalArgumentException("invalid_island_attach");
                Display display = getSystemService(DisplayManager.class).getDisplay(data.getInt("display"));
                if (display == null) throw new IllegalArgumentException("display_unavailable");
                Context visual = createDisplayContext(display);
                host = new SurfaceControlViewHost(visual, display, data.getBinder("hostToken"));
                TryOnResultCard view = new TryOnResultCard(visual, card.detail,
                        () -> TryOnResultWindow.act(TryOnResultContentService.this, token, "close"),
                        () -> TryOnResultWindow.act(TryOnResultContentService.this, token, "retry"),
                        () -> TryOnResultWindow.act(TryOnResultContentService.this, token, "open"));
                client = target.getBinder(); death = () -> main.post(() -> { if (epoch == attachment) release(); });
                client.linkToDeath(death, 0);
                host.setView(view, width, height);
                view.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                    if (host == null || epoch != attachment || generation != request || TryOnResultWindow.current(token) == null) return;
                    SurfaceControlViewHost.SurfacePackage surface = host.getSurfacePackage();
                    if (surface == null) return;
                    try {
                        Message ready = Message.obtain(null, 3); ready.arg1 = request;
                        Bundle reply = new Bundle(); reply.putParcelable("surface", surface); ready.setData(reply); target.send(ready);
                    } catch (Exception unavailable) { release(); } finally { surface.release(); }
                }));
                view.invalidate();
            } catch (Exception unavailable) {
                release();
                try { Message failed = Message.obtain(null, 4); failed.arg1 = request; target.send(failed); }
                catch (Exception ignored) { /* SystemUI keeps its native notification fallback. */ }
            }
        }
    });
    private boolean systemUi(int uid) {
        try {
            PackageManager pm = getPackageManager();
            return uid == pm.getApplicationInfo("com.android.systemui", 0).uid
                    && pm.checkSignatures("android", "com.android.systemui") == PackageManager.SIGNATURE_MATCH;
        } catch (Exception unavailable) { return false; }
    }
    private void release() {
        ++epoch;
        if (client != null && death != null) { try { client.unlinkToDeath(death, 0); } catch (RuntimeException ignored) {} }
        client = null; death = null;
        if (host != null) host.release(); host = null;
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { release(); return false; }
    @Override public void onDestroy() { release(); super.onDestroy(); }
}
