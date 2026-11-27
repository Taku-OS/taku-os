package org.agentos.assistant;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.Semaphore;

/**
 * Send operations acknowledge cleanup only after a successful native disconnect and thread exit.
 */
final class DiningPollSendAutomation {
    interface Read<T> {
        T run(UiAutomation ui) throws Exception;
    }

    // Binder can reuse a thread after a failed operation, so the fence must not be reentrant.
    private static final Semaphore ACCESSIBILITY = new Semaphore(1);
    private static final ThreadLocal<Boolean> CLEAN = ThreadLocal.withInitial(() -> true);
    static final String CLEANUP_UNCONFIRMED = "POLL_AUTOMATION_CLEANUP_UNCONFIRMED";

    static void beginOperation() {
        CLEAN.set(true);
    }

    static boolean cleanupConfirmed() {
        return CLEAN.get();
    }

    static <T> T read(Read<T> work) throws Exception {
        if (!ACCESSIBILITY.tryAcquire()) throw new IOException("POLL_EDITOR_BUSY");
        HandlerThread thread = new HandlerThread("dining-poll-send-accessibility");
        UiAutomation ui = null;
        Method disconnect = null;
        CLEAN.set(false);
        boolean connectionAttempted = false;
        try {
            thread.start();
            Class<?> binder = Class.forName("android.app.IUiAutomationConnection");
            Object connection =
                    Class.forName("android.app.UiAutomationConnection")
                            .getConstructor()
                            .newInstance();
            Constructor<UiAutomation> constructor =
                    UiAutomation.class.getDeclaredConstructor(Looper.class, binder);
            constructor.setAccessible(true);
            ui = constructor.newInstance(thread.getLooper(), connection);
            Method connect = UiAutomation.class.getMethod("connect", int.class);
            disconnect = UiAutomation.class.getMethod("disconnect");
            // A timeout can happen after Android has already registered the service.
            connectionAttempted = true;
            connect.invoke(ui, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |=
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            return work.run(ui);
        } finally {
            boolean disconnected = !connectionAttempted;
            if (connectionAttempted)
                try {
                    disconnect.invoke(ui);
                    disconnected = true;
                } catch (ReflectiveOperationException | RuntimeException failed) {
                    // UiAutomation can mark itself disconnected before unregistering remotely.
                }
            thread.quitSafely();
            try {
                thread.join(1500);
                if (thread.isAlive()) {
                    thread.quit();
                    thread.join(500);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            boolean cleaned = disconnected && !thread.isAlive();
            CLEAN.set(cleaned);
            if (cleaned) ACCESSIBILITY.release();
            else {
                restartAfterUnconfirmedCleanup();
                throw new IOException(CLEANUP_UNCONFIRMED);
            }
        }
    }

    private static void restartAfterUnconfirmedCleanup() {
        android.util.Log.e("TakuDining", "Restarting Assistant after unconfirmed UI automation cleanup");
        Thread restart = new Thread(() -> {
            // Keep both fences until exit. Android unregisters the automation on owner Binder death.
            // Let the current callback return its operation outcome before recycling the process.
            SystemClock.sleep(2_000);
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "dining-poll-automation-recovery");
        restart.setDaemon(true);
        restart.start();
    }
}
