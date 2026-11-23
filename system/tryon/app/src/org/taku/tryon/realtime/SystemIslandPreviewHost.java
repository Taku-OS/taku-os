// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.AttachedSurfaceControl;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.Consumer;

/** Compatibility transport for the original system-owned window; no camera or business state. */
final class SystemIslandPreviewHost implements ShoppingPreviewHost {
    static final String PERMISSION = "org.agentos.surfaces.permission.HOST_ISLAND_VIEW";
    static final ComponentName COMPONENT = new ComponentName("org.agentos.assistant",
            "org.agentos.surfaces.IslandViewHostService");
    private static final int OPEN=1, SURFACE=2, LAYOUT=3, CLOSE=4, READY=10, ERROR=11;
    private final Context context;
    private final View root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final WindowManager.LayoutParams latest = new WindowManager.LayoutParams();
    private final Rect touchBounds = new Rect();
    private Messenger remote;
    private SurfaceControlViewHost embedded;
    private FrameLayout stage;
    private Context displayContext;
    private Runnable ready;
    private Consumer<String> failure;
    private boolean bound, closed, opening, surfaceSent, readyReported;
    private int embeddedWidth, embeddedHeight, hostUid = -1;
    private final Runnable timeout = () -> fail("system_island_timeout");
    private final Runnable awaitAttached = this::reportReady;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed) return;
            ServiceInfo host = trustedService(context);
            if (!COMPONENT.equals(name) || service == null || host == null) { fail("system_island_unavailable"); return; }
            hostUid = host.applicationInfo.uid;
            remote = new Messenger(service);
            send(OPEN, geometry());
        }
        @Override public void onServiceDisconnected(ComponentName name) { fail("system_island_disconnected"); }
        @Override public void onBindingDied(ComponentName name) { fail("system_island_binding_died"); }
        @Override public void onNullBinding(ComponentName name) { fail("system_island_unavailable"); }
    };

    static boolean available(Context context) { return trustedService(context) != null; }
    private static ServiceInfo trustedService(Context context) {
        if (context == null || context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return null;
        try {
            PackageManager packages = context.getPackageManager();
            ServiceInfo service = packages.getServiceInfo(COMPONENT, 0);
            return service.enabled && service.exported && service.applicationInfo != null && service.applicationInfo.enabled
                    && COMPONENT.getPackageName().equals(service.packageName) && COMPONENT.getClassName().equals(service.name)
                    && PERMISSION.equals(service.permission)
                    && packages.checkSignatures("android", service.packageName) == PackageManager.SIGNATURE_MATCH ? service : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return null; }
    }

    SystemIslandPreviewHost(Context context, View root) { this.context = context; this.root = root; }
    @Override public String id() { return "system_island"; }
    @Override public void attach(WindowManager.LayoutParams params, Runnable ready, Consumer<String> failed) {
        if (closed || opening) return;
        opening = true; latest.copyFrom(params); this.ready = ready; failure = failed;
        main.postDelayed(timeout, 5000);
        try {
            boolean accepted = context.bindService(new Intent().setComponent(COMPONENT), connection, Context.BIND_AUTO_CREATE);
            if (closed) {
                if (accepted) try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
                return;
            }
            bound = accepted;
            if (!bound) fail("system_island_unavailable");
        } catch (RuntimeException unavailable) { fail("system_island_bind_failed"); }
    }
    @Override public void update(WindowManager.LayoutParams params) {
        if (closed) return;
        latest.copyFrom(params);
        try { if (embedded != null) place(); if (remote != null) send(LAYOUT, geometry()); }
        catch (RuntimeException unavailable) { fail("system_island_layout_failed"); }
    }
    private void place() {
        Rect display = displayContext.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        if (display.width() != embeddedWidth || display.height() != embeddedHeight) {
            embeddedWidth = display.width(); embeddedHeight = display.height(); embedded.relayout(embeddedWidth, embeddedHeight);
        }
        int width = Math.max(1, Math.min(embeddedWidth, latest.width)), height = Math.max(1, Math.min(embeddedHeight, latest.height));
        int x = Math.max(0, Math.min(embeddedWidth - width, latest.x)), y = Math.max(0, Math.min(embeddedHeight - height, latest.y));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = x; params.topMargin = y; root.setLayoutParams(params);
        touchBounds.set(x, y, x + width, y + height); applyTouchRegion();
    }
    private void applyTouchRegion() {
        AttachedSurfaceControl control = stage.getRootSurfaceControl();
        if (control != null) control.setTouchableRegion(latest.alpha <= 0 || (latest.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
                ? new Region() : new Region(touchBounds));
    }
    private Bundle geometry() {
        Bundle value = new Bundle(); value.putInt("x", latest.x); value.putInt("y", latest.y);
        value.putInt("width", Math.max(1, latest.width)); value.putInt("height", Math.max(1, latest.height));
        value.putFloat("alpha", latest.alpha); value.putBoolean("hidden", (latest.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        return value;
    }
    private boolean receive(Message message) {
        if (closed || message.sendingUid != hostUid) return true;
        if (message.what == ERROR) { fail("system_island_error"); return true; }
        if (message.what != READY || embedded != null) return true;
        try {
            IBinder token = message.getData().getBinder("token");
            Display display = context.getSystemService(DisplayManager.class).getDisplay(message.getData().getInt("display", Display.DEFAULT_DISPLAY));
            if (token == null || display == null) { fail("system_island_invalid_display"); return true; }
            displayContext = context.createDisplayContext(display);
            embedded = new SurfaceControlViewHost(displayContext, display, token);
            Rect bounds = displayContext.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            embeddedWidth = bounds.width(); embeddedHeight = bounds.height();
            stage = new FrameLayout(displayContext) {
                @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); applyTouchRegion(); }
                @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom); applyTouchRegion();
                }
            };
            stage.setClipChildren(true); stage.addView(root);
            embedded.setView(stage, embeddedWidth, embeddedHeight); place();
            SurfaceControlViewHost.SurfacePackage surface = embedded.getSurfacePackage();
            if (surface == null) { fail("system_island_missing_surface"); return true; }
            try { Bundle value = new Bundle(); value.putParcelable("surface", surface); surfaceSent = send(SURFACE, value); }
            finally { surface.release(); }
            reportReady();
        } catch (RuntimeException unavailable) { fail("system_island_attach_failed"); }
        return true;
    }
    private void reportReady() {
        if (closed || readyReported || !surfaceSent) return;
        if (!root.isAttachedToWindow()) { main.postDelayed(awaitAttached, 16); return; }
        readyReported = true; main.removeCallbacks(timeout); main.removeCallbacks(awaitAttached);
        Runnable callback = ready; ready = null; if (callback != null) callback.run();
    }
    private boolean send(int what, Bundle data) {
        if (closed || remote == null) return false;
        try { Message message = Message.obtain(null, what); message.replyTo = inbox; message.setData(data); remote.send(message); return true; }
        catch (RemoteException unavailable) { fail("system_island_connection_lost"); return false; }
    }
    private void fail(String code) {
        if (closed) return;
        Consumer<String> callback = failure; close(); if (callback != null) callback.accept(code);
    }
    @Override public void close() {
        if (closed) return;
        closed = true; ready = null; failure = null; main.removeCallbacks(timeout); main.removeCallbacks(awaitAttached);
        if (remote != null) {
            try { Message message = Message.obtain(null, CLOSE); message.replyTo = inbox; remote.send(message); } catch (RemoteException ignored) { }
            remote = null;
        }
        if (embedded != null) { embedded.release(); embedded = null; }
        if (bound) { bound = false; try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { } }
    }
}
