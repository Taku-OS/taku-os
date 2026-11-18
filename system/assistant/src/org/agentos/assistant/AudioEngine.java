// SPDX-License-Identifier: Apache-2.0
package org.agentos.assistant;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PatternMatcher;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Whether the separate Audio Engine (org.agentos.audio) is present on this system.
 *
 * Standalone/development images may omit the ROM engine. Every
 * assistant entry that needs it must fall back to its pre-engine path or stay hidden, so a
 * clean image never shows a voice entry that cannot work. Agents read the same fact with
 * {@code pm list packages org.agentos.audio}.
 *
 * The answer is cached for the process (callers include per-frame chat binding on the UI
 * thread) and refreshed by package broadcasts for this one package; screens that build their
 * layout once register a listener to rebuild when it changes.
 */
final class AudioEngine {
    static final String PACKAGE = "org.agentos.audio";
    static final ComponentName SERVICE = new ComponentName(PACKAGE, "org.agentos.audio.AudioEngineService");

    private static volatile Boolean cached;
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

    private AudioEngine() {}

    static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(PACKAGE, "org.agentos.audio.AudioHomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** Installed, enabled and its engine service resolves. Cached; no IPC after the first call. */
    static boolean available(Context context) {
        Boolean value = cached;
        if (value != null) return value;
        synchronized (AudioEngine.class) {
            if (cached == null) {
                Context app = context.getApplicationContext();
                watch(app);
                cached = probe(app);
            }
            return cached;
        }
    }

    /** Runs on the main thread whenever availability changes. Remove it when the screen goes away. */
    static void addListener(Runnable listener) { listeners.add(listener); }
    static void removeListener(Runnable listener) { listeners.remove(listener); }

    /** resolveService on the explicit component is null when the package is missing or disabled. */
    private static boolean probe(Context context) {
        return context.getPackageManager().resolveService(new Intent().setComponent(SERVICE), 0) != null;
    }

    private static void watch(Context app) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(PACKAGE, PatternMatcher.PATTERN_LITERAL);
        // Package broadcasts come from the system, which reaches non-exported receivers.
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                boolean next = probe(app);
                Boolean previous = cached;
                cached = next;
                if (previous == null || previous != next) for (Runnable listener : listeners) listener.run();
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
    }
}
