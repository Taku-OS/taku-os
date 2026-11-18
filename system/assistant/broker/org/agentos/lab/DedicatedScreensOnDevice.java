package org.agentos.lab;

import android.util.Log;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The broker's dedicated screens, built from the device's parts: phone-display, am, a monotonic
 * clock, Codex through NativeBridge, and the system language from getprop. Only the broker uses
 * this; host tests build DedicatedScreens from fakes, so it never names NativeBridge.
 */
final class DedicatedScreensOnDevice {
    private static final String LOG_TAG = "TakuDedicatedScreens";

    private static DedicatedScreens shared;

    static synchronized DedicatedScreens shared() {
        if (shared == null)
            shared =
                    new DedicatedScreens(
                            PhoneExecutionScreens.DEVICE_HOST,
                            PhoneTasks.DEVICE,
                            new DeviceClock(),
                            NativeBridge::askCodex,
                            DedicatedScreensOnDevice::systemLanguage,
                            message -> Log.w(LOG_TAG, message));
        return shared;
    }

    /** Monotonic time; callbacks run one at a time on a daemon thread. */
    private static final class DeviceClock implements DedicatedScreens.Clock {
        private final ScheduledExecutorService timer =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dedicated-screens-timer");
                    t.setDaemon(true);
                    return t;
                });

        public long now() {
            return System.nanoTime() / 1_000_000;
        }

        public void after(long ms, Runnable run) {
            timer.schedule(run, ms, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The system language ("zh", "en", ...): persist.sys.locale (written when the user picks a
     * language in Settings), else ro.product.locale, else the process default.
     */
    private static String systemLanguage() {
        for (String key : new String[] {"persist.sys.locale", "ro.product.locale"})
            try {
                String tag = TaskBroker.command("/system/bin/getprop", key).trim();
                if (!tag.isEmpty()) return Locale.forLanguageTag(tag).getLanguage();
            } catch (Exception unavailable) {
                break;
            }
        return Locale.getDefault().getLanguage();
    }
}
