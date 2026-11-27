package org.agentos.lab;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Bounded child-process, display-scoped read only. No navigation or accessibility action. */
public final class PhoneUberUpcomingProbe {
    static final String PREFIX = "TAKU_UBER_UPCOMING:";
    private record Snapshot(int window, List<UberUpcomingPolicy.Label> labels, boolean loading, boolean editable) {}
    private static final class Read {
        final List<UberUpcomingPolicy.Label> labels = new ArrayList<>();
        int nodes, characters;
        boolean loading, editable;
    }

    public static void main(String[] args) throws Exception {
        JSONObject result;
        try {
            if (args.length != 1 || !args[0].matches("[1-9][0-9]{0,5}")) throw new IOException("INVALID_DISPLAY");
            Looper.prepareMainLooper();
            result = inspect(Integer.parseInt(args[0]));
        } catch (Exception unavailable) { result = UberUpcomingPolicy.unknown("native-view-unavailable"); }
        System.out.println(PREFIX + result);
        System.exit(0);
    }

    private static JSONObject inspect(int display) throws Exception {
        HandlerThread callbacks = new HandlerThread("uber-upcoming-read");
        callbacks.start();
        UiAutomation ui = null;
        try {
            Class<?> binder = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            var constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, binder);
            constructor.setAccessible(true);
            ui = constructor.newInstance(callbacks.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(ui, 1);
            AccessibilityServiceInfo service = ui.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(service);
            try {
                ui.waitForIdle(100, 1000);
            } catch (java.util.concurrent.TimeoutException loading) {
                return UberUpcomingPolicy.unknown("native-view-busy");
            }
            Snapshot first = snapshot(ui, display);
            Thread.sleep(100);
            try { UiAutomation.class.getMethod("clearCache").invoke(ui); } catch (Exception ignored) {}
            Snapshot second = snapshot(ui, display);
            if (!first.equals(second)) return UberUpcomingPolicy.unknown("native-view-changed");
            return UberUpcomingPolicy.classify(second.labels(), second.loading(), second.editable(), second.window());
        } finally {
            if (ui != null) try { UiAutomation.class.getMethod("disconnect").invoke(ui); } catch (Exception ignored) {}
            callbacks.quitSafely();
        }
    }

    private static Snapshot snapshot(UiAutomation ui, int display) throws Exception {
        List<AccessibilityWindowInfo> windows = ui.getWindowsOnAllDisplays().get(display);
        AccessibilityWindowInfo top = null;
        boolean tied = false;
        if (windows != null) for (var window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (top == null || window.getLayer() > top.getLayer()) { top = window; tied = false; }
            else if (window.getLayer() == top.getLayer()) tied = true;
        }
        AccessibilityNodeInfo root = top == null ? null : top.getRoot();
        if (tied || root == null || !root.refresh() || root.getWindowId() != top.getId()
                || !"com.ubercab".contentEquals(root.getPackageName())) throw new IOException("UBER_WINDOW_UNVERIFIED");
        Rect bounds = new Rect(); top.getBoundsInScreen(bounds);
        Read read = new Read();
        collect(root, read, top.getId(), bounds, 0);
        return new Snapshot(top.getId(), List.copyOf(read.labels), read.loading, read.editable);
    }

    private static void collect(AccessibilityNodeInfo node, Read read, int window, Rect bounds, int depth) throws IOException {
        if (node == null || !node.isVisibleToUser()) return;
        if (++read.nodes > 700 || depth > 32) throw new IOException("UBER_VIEW_LIMIT");
        Rect nodeBounds = new Rect(); node.getBoundsInScreen(nodeBounds);
        if (node.getWindowId() != window || !"com.ubercab".contentEquals(node.getPackageName())
                || nodeBounds.isEmpty() || !Rect.intersects(bounds, nodeBounds)) return;
        if (node.isEditable()) read.editable = true;
        if (node.isPassword() || node.isAccessibilityDataSensitive() || node.isEditable()) return;
        if ("android.widget.ProgressBar".contentEquals(node.getClassName())) read.loading = true;
        CharSequence raw = node.getText() == null ? node.getContentDescription() : node.getText();
        if (raw != null && !raw.toString().isBlank()) {
            String text = raw.toString().strip();
            read.characters += text.length();
            if (read.characters > 16000) throw new IOException("UBER_VIEW_LIMIT");
            if (text.length() <= 240) read.labels.add(new UberUpcomingPolicy.Label(text, node.isHeading() || node.isSelected()));
            if (java.util.Set.of("Loading", "Loading…", "Loading...", "加载中", "載入中").contains(text)) read.loading = true;
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), read, window, bounds, depth + 1);
    }
}
