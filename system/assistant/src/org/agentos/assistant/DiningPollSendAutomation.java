package org.agentos.assistant;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Send operations acknowledge cleanup only after a successful native disconnect and thread exit.
 */
final class DiningPollSendAutomation {
    interface Read<T> {
        T run(UiAutomation ui) throws Exception;
    }

    private static final ReentrantLock ACCESSIBILITY = new ReentrantLock();
    private static final ThreadLocal<Boolean> CLEAN = ThreadLocal.withInitial(() -> true);

    static void beginOperation() {
        CLEAN.set(true);
    }

    static boolean cleanupConfirmed() {
        return CLEAN.get();
    }

    static <T> T read(Read<T> work) throws Exception {
        if (!ACCESSIBILITY.tryLock()) throw new IOException("POLL_EDITOR_BUSY");
        HandlerThread thread = new HandlerThread("dining-poll-send-accessibility");
        UiAutomation ui = null;
        CLEAN.set(false);
        boolean connected = false;
        boolean disconnected = false;
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
            UiAutomation.class.getMethod("connect", int.class).invoke(ui, 1);
            connected = true;
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |=
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            return work.run(ui);
        } finally {
            if (!connected) disconnected = true;
            else
                try {
                    UiAutomation.class.getMethod("disconnect").invoke(ui);
                    disconnected = true;
                } catch (Exception failed) {
                    disconnected = false;
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
            // Keep the common AX lock closed only when a live UiAutomation connection failed to
            // disconnect cleanly; a pre-connect failure never registered a service and releases.
            if (cleaned) ACCESSIBILITY.unlock();
            else throw new IOException("POLL_AUTOMATION_CLEANUP_UNCONFIRMED");
        }
    }
}
