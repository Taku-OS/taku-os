// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;
import java.util.function.Consumer;

/** Optional presentation transport. The camera and its View tree always stay in the app. */
public interface ShoppingPreviewHost {
    interface Factory {
        /** Return null when this host is unavailable; the ordinary overlay remains usable. */
        ShoppingPreviewHost create(Context context, View root);
    }

    /** Register from Application.onCreate, before a preview is constructed. Null restores the default. */
    static void installFactory(Factory factory) { Registry.factory = factory; }

    /** All host operations and callbacks run on the main thread; ready means root is attached. */
    String id();
    void attach(WindowManager.LayoutParams params, Runnable ready, Consumer<String> failed);
    void update(WindowManager.LayoutParams params);
    void close();

    static ShoppingPreviewHost create(Context context, View root) {
        Factory factory = Registry.factory;
        if (factory != null) {
            ShoppingPreviewHost host = factory.create(context, root);
            return host == null ? new ApplicationOverlay(context, root) : host;
        }
        return SystemIslandPreviewHost.available(context) ? new SystemIslandPreviewHost(context, root) : new ApplicationOverlay(context, root);
    }

    final class Registry {
        private Registry() {}
        private static volatile Factory factory;
    }

    final class ApplicationOverlay implements ShoppingPreviewHost {
        private final WindowManager windows;
        private final View root;
        private boolean attached, closed;
        ApplicationOverlay(Context context, View root) {
            windows = context.getSystemService(WindowManager.class); this.root = root;
        }
        public String id() { return "application_overlay"; }
        public void attach(WindowManager.LayoutParams params, Runnable ready, Consumer<String> failed) {
            if (closed || attached) return;
            windows.addView(root, params); attached = true; ready.run();
        }
        public void update(WindowManager.LayoutParams params) {
            if (attached && !closed) windows.updateViewLayout(root, params);
        }
        public void close() {
            if (closed) return;
            closed = true;
            if (attached) {
                attached = false;
                try { windows.removeViewImmediate(root); } catch (RuntimeException ignored) { }
            }
        }
    }
}
