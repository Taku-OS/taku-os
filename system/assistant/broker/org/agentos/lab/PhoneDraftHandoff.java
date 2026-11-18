package org.agentos.lab;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** Writes a verified foreground conversation draft, without focus, navigation or send actions. */
public final class PhoneDraftHandoff {
    private static final int INPUT_LIMIT = 32768;
    private static final int OUTPUT_LIMIT = 4096;
    private static final int NODE_LIMIT = 512;
    private static final ReentrantLock DRAFT_LOCK = new ReentrantLock();

    static JSONObject prepare(String packageName, String conversationTitle, String text) throws Exception {
        PhoneDraftTarget.validateRequest(packageName, conversationTitle, text);
        if (Thread.currentThread().isInterrupted()) throw PhoneDraftTarget.failure("DRAFT_STATE_UNVERIFIED_INTERRUPTED");
        if (!DRAFT_LOCK.tryLock()) throw PhoneDraftTarget.failure("DRAFT_HELPER_BUSY");
        try { return runHelper(packageName, conversationTitle, text); }
        finally { DRAFT_LOCK.unlock(); }
    }

    private static JSONObject runHelper(String packageName, String conversationTitle, String text) throws Exception {
        byte[] request = new JSONObject().put("packageName", packageName)
                .put("conversationTitle", conversationTitle).put("text", text)
                .toString().getBytes(StandardCharsets.UTF_8);
        if (request.length > INPUT_LIMIT) throw PhoneDraftTarget.failure("INVALID_DRAFT_TEXT");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", PhoneDraftHandoff.class.getName());
        builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        Process helper = builder.redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Both pipes are drained off the broker thread so a stuck framework cannot defeat the deadline.
        Thread writer = new Thread(() -> {
            try (OutputStream input = helper.getOutputStream()) { input.write(request); }
            catch (IOException ignored) {}
        }, "foreground-draft-request");
        Thread reader = new Thread(() -> {
            try (InputStream input = helper.getInputStream()) {
                byte[] chunk = new byte[1024];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    synchronized (output) {
                        if (output.size() < OUTPUT_LIMIT + 1)
                            output.write(chunk, 0, Math.min(read, OUTPUT_LIMIT + 1 - output.size()));
                    }
                }
            } catch (IOException ignored) {}
        }, "foreground-draft-result");
        writer.setDaemon(true); reader.setDaemon(true);
        try {
            writer.start(); reader.start();
            if (!helper.waitFor(12, TimeUnit.SECONDS)) throw PhoneDraftTarget.failure("DRAFT_STATE_UNVERIFIED_TIMEOUT");
            reader.join(1000);
            String result;
            synchronized (output) { result = output.toString(StandardCharsets.UTF_8).trim(); }
            if (reader.isAlive() || helper.exitValue() != 0
                    || !(result.equals("OK_WRITTEN") || result.equals("OK_PRESENT"))) {
                String code = result.matches("ERROR: [A-Z_]+") ? result.substring(7) : "DRAFT_STATE_UNVERIFIED";
                throw PhoneDraftTarget.failure(code);
            }
            return new JSONObject().put("draftPrepared", true).put("sent", false)
                    .put("displayId", 0).put("packageName", packageName)
                    .put("characterCount", text.codePointCount(0, text.length()))
                    .put("alreadyPrepared", result.equals("OK_PRESENT"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw PhoneDraftTarget.failure("DRAFT_STATE_UNVERIFIED_INTERRUPTED");
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (helper.isAlive()) {
                    helper.destroyForcibly();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (helper.isAlive() && System.nanoTime() < deadline) {
                        try { helper.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                }
                try { helper.getOutputStream().close(); } catch (IOException ignored) {}
                try { helper.getInputStream().close(); } catch (IOException ignored) {}
                try { helper.getErrorStream().close(); } catch (IOException ignored) {}
                if (helper.isAlive()) throw PhoneDraftTarget.failure("DRAFT_HELPER_EXIT_UNVERIFIED");
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        int exit = 1;
        try {
            Looper.prepareMainLooper();
            byte[] bytes = System.in.readNBytes(INPUT_LIMIT + 1);
            if (bytes.length > INPUT_LIMIT) throw PhoneDraftTarget.failure("INVALID_REQUEST");
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String packageName = request.getString("packageName");
            String title = request.getString("conversationTitle");
            String text = request.getString("text");
            PhoneDraftTarget.validateRequest(packageName, title, text);
            boolean alreadyPrepared = prepareWindow(packageName, title, text);
            System.out.println(alreadyPrepared ? "OK_PRESENT" : "OK_WRITTEN");
            exit = 0;
        } catch (Exception error) {
            String code = error.getMessage();
            System.out.println("ERROR: " + (code != null && code.matches("[A-Z_]+") ? code : "DRAFT_STATE_UNVERIFIED"));
        }
        System.exit(exit);
    }

    private static boolean prepareWindow(String packageName, String title, String text) throws Exception {
        HandlerThread callbacks = new HandlerThread("foreground-draft-accessibility");
        callbacks.start();
        UiAutomation automation = null;
        try {
            Class<?> binder = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            var constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, binder);
            constructor.setAccessible(true);
            automation = constructor.newInstance(callbacks.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1);
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(service);
            try { automation.waitForIdle(100, 1500); } catch (java.util.concurrent.TimeoutException ignored) {}
            try (Snapshot initial = inspect(automation, packageName, title, text);
                 Snapshot before = inspect(automation, packageName, title, text)) {
                initial.requireSameTarget(before);
                AccessibilityNodeInfo header = before.nodes.get(before.selection.titleKey());
                AccessibilityNodeInfo editor = before.editor();
                if (!header.refresh() || !header.isVisibleToUser()
                        || !PhoneDraftTarget.normalizeTitle(title).equals(PhoneDraftTarget.normalizeTitle(value(header.getText())))
                        || !editor.refresh() || !editor.isVisibleToUser() || !editor.isEditable()
                        || !editor.isEnabled() || editor.isPassword())
                    throw PhoneDraftTarget.failure("FOREGROUND_TARGET_CHANGED");
                // Recheck immediately before mutation as the user may be typing concurrently.
                if (PhoneDraftTarget.alreadyPrepared(editor.isShowingHintText() ? "" : value(editor.getText()), text))
                    return true;
                // ACTION_SET_TEXT is the only action in this helper. Newlines remain literal draft text.
                Bundle values = new Bundle();
                values.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (!before.editor().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values))
                    throw PhoneDraftTarget.failure("DRAFT_ACTION_REJECTED");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                do {
                    if (before.editor().refresh() && text.contentEquals(value(before.editor().getText()))) {
                        try (Snapshot after = inspect(automation, packageName, title, text)) {
                            before.requireSameTarget(after);
                            if (after.selection.alreadyPrepared()) return false;
                        }
                    }
                    Thread.sleep(50);
                } while (System.nanoTime() < deadline);
                // No rollback or retries: either could overwrite a concurrent user edit.
                throw PhoneDraftTarget.failure("DRAFT_READBACK_UNVERIFIED");
            }
        } finally {
            if (automation != null) try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (Exception ignored) {}
            callbacks.quitSafely();
        }
    }

    static void requireRootContent(int childCount, boolean emptyBounds, boolean visible) throws IOException {
        // Some apps expose only an empty placeholder root. That cannot establish a title mismatch.
        if (childCount == 0 && (emptyBounds || !visible))
            throw PhoneDraftTarget.failure("FOREGROUND_CONTENT_UNAVAILABLE");
    }

    private static Snapshot inspect(UiAutomation automation, String packageName, String title, String text) throws Exception {
        try { UiAutomation.class.getMethod("clearCache").invoke(automation); } catch (Exception ignored) {}
        Snapshot snapshot = new Snapshot();
        try {
            List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(0);
            if (windows == null) throw PhoneDraftTarget.failure("NO_FOREGROUND_WINDOW");
            snapshot.windows.addAll(windows);
            List<PhoneDraftTarget.Window> facts = new ArrayList<>();
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = null;
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.getDisplayId() == 0) {
                    root = window.getRoot();
                    if (root != null) snapshot.owned.add(root);
                }
                roots.add(root);
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                facts.add(new PhoneDraftTarget.Window(window.getId(), window.getDisplayId(), window.getLayer(),
                        window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION, window.isActive(), window.isFocused(),
                        root == null ? "" : value(root.getPackageName()), box(bounds)));
            }
            snapshot.window = PhoneDraftTarget.selectWindow(facts, packageName);
            AccessibilityNodeInfo root = roots.get(facts.indexOf(snapshot.window));
            if (root == null) throw PhoneDraftTarget.failure("FOREGROUND_ROOT_UNAVAILABLE");
            Rect rootBounds = new Rect(); root.getBoundsInScreen(rootBounds);
            requireRootContent(root.getChildCount(), rootBounds.isEmpty(), root.isVisibleToUser());
            ArrayDeque<Visit> queue = new ArrayDeque<>();
            Set<AccessibilityNodeInfo> seen = new HashSet<>();
            queue.add(new Visit(root, false));
            List<PhoneDraftTarget.Node> nodes = new ArrayList<>();
            while (!queue.isEmpty()) {
                Visit visit = queue.removeFirst();
                AccessibilityNodeInfo node = visit.node();
                if (!seen.add(node)) continue;
                if (seen.size() > NODE_LIMIT) throw PhoneDraftTarget.failure("FOREGROUND_UI_TREE_LIMIT");
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                int key = snapshot.nodes.size(); snapshot.nodes.add(node);
                boolean scrolling = visit.scrolling() || node.isScrollable();
                boolean editable = node.isEditable();
                // Read text only from potential headers and the editable target, never export a UI tree.
                PhoneDraftTarget.Node candidate = new PhoneDraftTarget.Node(key, value(node.getPackageName()), box(bounds),
                        node.isVisibleToUser(), node.isEnabled(), editable, node.isPassword(),
                        node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT),
                        scrolling, "", "");
                String header = PhoneDraftTarget.isTitleCandidate(snapshot.window.bounds(), candidate) ? value(node.getText()) : "";
                String draft = editable && node.isVisibleToUser() && !node.isPassword() && !node.isShowingHintText()
                        ? value(node.getText()) : "";
                nodes.add(new PhoneDraftTarget.Node(key, candidate.packageName(), candidate.bounds(), candidate.visible(),
                        candidate.enabled(), editable, candidate.password(), candidate.supportsSetText(), scrolling, header, draft));
                int children = node.getChildCount();
                if (children > NODE_LIMIT || queue.size() + seen.size() + children > NODE_LIMIT)
                    throw PhoneDraftTarget.failure("FOREGROUND_UI_TREE_LIMIT");
                for (int i = 0; i < children; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) { snapshot.owned.add(child); queue.add(new Visit(child, scrolling)); }
                }
            }
            snapshot.selection = PhoneDraftTarget.select(snapshot.window, nodes, packageName, title, text);
            return snapshot;
        } catch (Exception error) { snapshot.close(); throw error; }
    }

    private record Visit(AccessibilityNodeInfo node, boolean scrolling) {}
    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    private static PhoneDraftTarget.Box box(Rect value) { return new PhoneDraftTarget.Box(value.left, value.top, value.right, value.bottom); }

    private static final class Snapshot implements AutoCloseable {
        final List<AccessibilityWindowInfo> windows = new ArrayList<>();
        final List<AccessibilityNodeInfo> owned = new ArrayList<>();
        final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        PhoneDraftTarget.Window window;
        PhoneDraftTarget.Selection selection;
        AccessibilityNodeInfo editor() { return nodes.get(selection.editorKey()); }
        void requireSameTarget(Snapshot other) throws IOException {
            if (window.id() != other.window.id() || !editor().equals(other.editor())
                    || !nodes.get(selection.titleKey()).equals(other.nodes.get(other.selection.titleKey())))
                throw PhoneDraftTarget.failure("FOREGROUND_TARGET_CHANGED");
        }
        @SuppressWarnings("deprecation")
        public void close() {
            for (AccessibilityNodeInfo node : owned) node.recycle();
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }
}
