package org.agentos.lab;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.*;
import android.view.accessibility.*;
import android.util.SparseArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Display-scoped Unicode input. A bounded helper isolates framework accessibility failures. */
public final class PhoneTextInput {
    // Android permits one UiAutomation connection; screens remain independent while
    // this short input helper (and foreground draft helper) takes the shared lock.
    static final java.util.concurrent.locks.ReentrantLock AUTOMATION_LOCK=new java.util.concurrent.locks.ReentrantLock();
    static void enter(int display, String text) throws Exception {
        AUTOMATION_LOCK.lockInterruptibly();
        try {if(Thread.currentThread().isInterrupted())throw new InterruptedException();enterLocked(display,text);}
        finally {AUTOMATION_LOCK.unlock();}
    }
    private static void enterLocked(int display, String text) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", PhoneTextInput.class.getName());
        builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        java.lang.Process helper = builder.redirectErrorStream(true).start();
        try {
            try (OutputStream input = helper.getOutputStream()) {
                input.write(new JSONObject().put("displayId", display).put("text", text).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!helper.waitFor(15, TimeUnit.SECONDS)) throw new IOException("The Unicode input helper timed out and was terminated");
            String result;
            try (InputStream output = helper.getInputStream()) {
                result = new String(output.readNBytes(4096), StandardCharsets.UTF_8).trim();
            }
            if (helper.exitValue() != 0 || !result.equals("OK")) throw new IOException("The Unicode input helper failed (" + (result.matches("ERROR: [A-Z_]+") ? result.substring(7) : "FRAMEWORK_ERROR") + "). Focus the background screen input field again");
        } finally {
            boolean interrupted=Thread.interrupted();
            try {
                if(helper.isAlive())helper.destroyForcibly();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(helper.isAlive()&&System.nanoTime()<deadline)try{helper.waitFor(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(InterruptedException ignored){interrupted=true;}
                if(helper.isAlive())throw new IOException("TEXT_HELPER_EXIT_UNVERIFIED");
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }

    public static void main(String[] args) {
        int exit = 1;
        try {
            // AccessibilityInteractionClient needs a main Looper even when callbacks use a HandlerThread.
            Looper.prepareMainLooper();
            byte[] bytes = System.in.readNBytes(32769);
            if (bytes.length > 32768) throw new IOException("Input limit");
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int display = request.getInt("displayId");
            String text = request.getString("text");
            if (display <= 0 || text.length() > 4000) throw new IOException("INVALID_INPUT");
            enterWindow(display, text);
            System.out.println("OK");
            exit = 0;
        } catch (Exception error) {
            System.out.println("ERROR: " + (error.getMessage() != null && error.getMessage().matches("[A-Z_]+") ? error.getMessage() : "FRAMEWORK_ERROR"));
        }
        System.exit(exit);
    }

    private static void enterWindow(int display, String text) throws Exception {
        HandlerThread callbacks = new HandlerThread("phone-unicode-input");
        callbacks.start();
        UiAutomation automation = null;
        try {
            Class<?> binder = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            java.lang.reflect.Constructor<UiAutomation> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, binder);
            constructor.setAccessible(true);
            automation = constructor.newInstance(callbacks.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1);
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(service);
            // Let newly connected application accessibility clients publish their windows.
            try { automation.waitForIdle(100, 1500); } catch (java.util.concurrent.TimeoutException ignored) {}
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String failure = "NO_BACKGROUND_WINDOW";
            while (System.nanoTime() < deadline) {
                try { UiAutomation.class.getMethod("clearCache").invoke(automation); } catch (Exception ignored) {}
                SparseArray<List<AccessibilityWindowInfo>> windows = automation.getWindowsOnAllDisplays();
                List<AccessibilityWindowInfo> owned = windows.get(display);
                if (owned != null) {
                    AccessibilityNodeInfo node;
                    try { node = find(display, owned); }
                    catch (IOException error) {
                        if (!"BACKGROUND_WINDOW_UNAVAILABLE".equals(error.getMessage())) throw error;
                        failure = error.getMessage();
                        Thread.sleep(150);
                        continue;
                    }
                    if (node == null) { failure = "NO_BACKGROUND_INPUT"; Thread.sleep(150); continue; }
                    Bundle values = new Bundle();
                    values.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    // ACTION_SET_TEXT does not require changing input focus or the foreground display.
                    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values))
                        throw new IOException("TEXT_ACTION_REJECTED");
                    long readbackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    do {
                        if (node.refresh() && text.contentEquals(node.getText() == null ? "" : node.getText())) return;
                        Thread.sleep(50);
                    } while (System.nanoTime() < readbackDeadline);
                    throw new IOException("TEXT_READBACK_MISMATCH");
                }
                Thread.sleep(150);
            }
            throw new IOException(failure);
        } finally {
            if (automation != null) try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (Exception ignored) {}
            callbacks.quitSafely();
        }
    }

    private static AccessibilityNodeInfo find(int display, List<AccessibilityWindowInfo> windows) throws IOException {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        Set<AccessibilityNodeInfo> seen = new HashSet<>();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getDisplayId() != display || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            // Do not guess a unique editor when another application window cannot be inspected.
            if (root == null) throw new IOException("BACKGROUND_WINDOW_UNAVAILABLE");
            queue.add(root);
        }
        AccessibilityNodeInfo singleEditable = null, focused = null;
        int editableCount = 0, focusedCount = 0;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (!seen.add(node)) continue;
            if (seen.size() > 512) throw new IOException("BACKGROUND_INPUT_TREE_LIMIT");
            if (node.isEditable() && node.isVisibleToUser() && node.isEnabled()) {
                if (node.isFocused()) { focused = node; focusedCount++; }
                singleEditable = node; editableCount++;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        if (focusedCount == 1) return focused;
        if (focusedCount > 1 || editableCount > 1) throw new IOException("AMBIGUOUS_BACKGROUND_INPUT");
        return editableCount == 1 ? singleEditable : null;
    }
}
