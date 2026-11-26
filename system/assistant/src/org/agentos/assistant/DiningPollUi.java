package org.agentos.assistant;

import android.app.*;
import android.view.accessibility.*;

import java.io.*;
import java.util.*;

/** Reads the top WhatsApp window on the authorized execution display. */
final class DiningPollUi {
    static AccessibilityNodeInfo root(UiAutomation ui, int display, String owner) throws Exception {
        IOException last = null;
        for (int i = 0; i < 5; i++)
            try {
                return rootOnce(ui, display, owner);
            } catch (IOException notReady) {
                last = notReady;
                Thread.sleep(80);
            }
        throw last;
    }

    private static AccessibilityNodeInfo rootOnce(UiAutomation ui, int display, String owner)
            throws Exception {
        List<AccessibilityWindowInfo> windows = ui.getWindowsOnAllDisplays().get(display);
        AccessibilityWindowInfo top = null;
        AccessibilityNodeInfo result = null;
        if (windows != null)
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo node = window.getRoot();
                if (node == null) continue;
                String pkg = value(node.getPackageName());
                if (pkg.equals("org.agentos.assistant")) continue;
                if (top == null || window.getLayer() > top.getLayer()) {
                    top = window;
                    result = node;
                }
            }
        if (result == null || !result.refresh() || !owner.equals(value(result.getPackageName())))
            throw new IOException("POLL_SOURCE_NOT_VISIBLE");
        return result;
    }

    static String value(CharSequence value) {
        return value == null ? "" : value.toString().strip();
    }

    static int display(ActivityManager.RunningTaskInfo task) {
        try {
            return task.getClass().getField("displayId").getInt(task);
        } catch (ReflectiveOperationException unavailable) {
            return -1;
        }
    }
}
