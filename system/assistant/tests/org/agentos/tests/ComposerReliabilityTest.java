package org.agentos.tests;

import android.os.Bundle;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Real DocumentsUI, task queue and upstream errors. No model result is simulated. */
public final class ComposerReliabilityTest extends UiAutomatorTestCase {
    static final String PKG = "org.agentos.assistant";
    static final String DOCS = "com.android.documentsui";
    static final String APP = "/data/user/0/" + PKG;
    static final String RUNTIME = APP + "/files/runtime/";
    String run, phase, thread = "", ownedTurn = "", imageCode, textCode;
    File output, fixtures;
    JSONObject evidence;
    UiAutomation automation;

    void refreshAccessibility() throws Exception {
        if (automation == null) {
            java.lang.reflect.Method bridgeMethod = getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
            bridgeMethod.setAccessible(true);
            Object bridge = bridgeMethod.invoke(getUiDevice());
            for (Class<?> type = bridge.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("mUiAutomation");
                    field.setAccessible(true); automation = (UiAutomation) field.get(bridge); break;
                } catch (NoSuchFieldException ignored) {}
            }
            assertNotNull("Use the existing UiAutomator connection", automation);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            // Allow the initial accessibility connection to settle once. Each selector owns its deadline.
            try { automation.waitForIdle(100, 1500); } catch (java.util.concurrent.TimeoutException ignored) {}
        }
        UiAutomation.class.getMethod("clearCache").invoke(automation);
    }
    boolean waitAccessible(UiObject item, long millis) throws Exception {
        long until = System.nanoTime() + millis * 1_000_000L;
        do {
            if (item.exists()) return true;
            sleep(150);
        } while (System.nanoTime() < until);
        return false;
    }

    class Entry extends UiObject {
        final String pkg, field, value;
        final boolean regex;
        Entry(String pkg, String field, String value, boolean regex) {
            super(new UiSelector().packageName(pkg));
            this.pkg=pkg; this.field=field; this.value=value; this.regex=regex;
        }
        AccessibilityNodeInfo matching(AccessibilityNodeInfo node, int depth, int[] budget) {
            if (node == null || depth > 50 || --budget[0] < 0) return null;
            if (node.isVisibleToUser() && node.isEnabled()
                    && pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                CharSequence actual = field.equals("description") ? node.getContentDescription() : node.getText();
                String actualText = actual == null ? "" : actual.toString();
                String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
                if (field.equals("package") || (field.equals("scrollable") && node.isScrollable())
                        || (field.equals("document") && (actualText.equals(value) || description.equals(value)
                                || description.startsWith(value + ",")))
                        || (!field.equals("scrollable") && (regex ? actualText.matches(value) : actualText.equals(value)))) return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo found = matching(node.getChild(i), depth+1, budget);
                if (found != null) return found;
            }
            return null;
        }
        @Override protected AccessibilityNodeInfo findAccessibilityNodeInfo(long timeout) {
            long until = System.nanoTime() + timeout * 1_000_000L;
            try {
                do {
                    refreshAccessibility();
                    // A background virtual display can own the active window. Only operate display 0.
                    List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(0);
                    if (windows != null) for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo found = matching(window.getRoot(), 0, new int[]{4096});
                        if (found != null) return found;
                    }
                    if (System.nanoTime() < until) sleep(100);
                } while (System.nanoTime() < until);
                return null;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public boolean click() {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
            if (node == null) return false;
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            // Legacy UiObject.click clips coordinates against the active window, which may
            // belong to display 2. Activate the selected display-0 control (or its row) instead.
            while (node != null && pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                node = node.getParent();
            }
            // DocumentsUI's image-grid cells use touch selection without ACTION_CLICK.
            // Use their observed bounds and explicitly target display 0, never the active display.
            // Folder navigation can expose nodes before the toolbar finishes laying out.
            for (int attempt = 0; attempt < 20; attempt++) {
                sleep(200);
                AccessibilityNodeInfo current = findAccessibilityNodeInfo(1000);
                if (current == null) return false;
                android.graphics.Rect next = new android.graphics.Rect();
                current.getBoundsInScreen(next);
                if (next.equals(bounds)) break;
                bounds.set(next);
            }
            if (bounds.isEmpty()) return false;
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                    android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80,
                    android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
            try {
                for (android.view.MotionEvent event : new android.view.MotionEvent[]{down, up}) {
                    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    android.view.InputEvent.class.getMethod("setDisplayId", int.class).invoke(event, 0);
                }
                boolean pressed = automation.injectInputEvent(down, true);
                sleep(80);
                return automation.injectInputEvent(up, true) && pressed;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            finally { down.recycle(); up.recycle(); }
        }
    }
    Entry textEntry(String pkg, String value) { return new Entry(pkg, "text", value, false); }
    final class Node extends Entry {
        Node(String description) { super(PKG, "description", description, false); }
        boolean replace(String value) throws Exception {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        String value() throws Exception {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
            if (node == null) throw new AssertionError("Composer is missing");
            return node.isShowingHintText() || node.getText() == null ? "" : node.getText().toString();
        }
        boolean act() throws Exception {
            // The controller can replace a node between lookup and ACTION_CLICK.
            for (int attempt = 0; attempt < 3; attempt++) {
                AccessibilityNodeInfo node = findAccessibilityNodeInfo(4000);
                if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                sleep(150);
            }
            return false;
        }
    }

    String read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    void write(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
    String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("Command failed: " + args[0] + "\n" + result, 0, process.waitFor());
        return result.trim();
    }
    JSONObject object(String key, Object value) throws Exception { return new JSONObject().put(key, value); }

    // Read only the named test-thread value. Never dump the user's preferences or auth material.
    String preference(String key, String fallback) throws Exception {
        File file = new File(APP + "/shared_prefs/agent-settings.xml");
        try (InputStream in = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(in, "UTF-8");
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && "string".equals(parser.getName())
                        && key.equals(parser.getAttributeValue(null, "name"))) return parser.nextText();
            }
        }
        return fallback;
    }
    JSONArray attachments() throws Exception {
        return new JSONArray(preference("composer." + thread + ".attachments", "[]"));
    }
    void expectAttachments(int count) throws Exception {
        long until = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < until) { if (attachments().length() == count) return; sleep(100); }
        assertEquals("Selected attachments must be persisted for this test thread", count, attachments().length());
    }
    void expectDraft(String value) throws Exception {
        long until = System.nanoTime() + 12_000_000_000L;
        while (System.nanoTime() < until) {
            if (value.equals(new Node("Taku 对话输入框").value())
                    && value.equals(preference("draft-" + thread, ""))) return;
            sleep(150);
        }
        assertEquals("Visible draft must survive failure", value, new Node("Taku 对话输入框").value());
        assertEquals("Persisted draft must survive failure", value, preference("draft-" + thread, ""));
    }
    boolean keyboardVisible() throws Exception {
        refreshAccessibility();
        List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(0);
        if (windows != null) for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    && root != null && root.isVisibleToUser()) return true;
        }
        return false;
    }
    void expectKeyboard(boolean visible) throws Exception {
        long until = System.nanoTime() + 20_000_000_000L;
        do {
            if (keyboardVisible() == visible) return;
            sleep(150);
        } while (System.nanoTime() < until);
        assertEquals("Soft keyboard visibility on display 0", visible, keyboardVisible());
    }
    void showKeyboard() throws Exception {
        assertTrue(new Node("Taku 对话输入框").act());
        expectKeyboard(true);
    }
    void openThread() throws Exception {
        command("/system/bin/am", "start", "-n", PKG + "/.PanelActivity", "--es", "conversationId", thread);
        assertTrue("Composer accessibility missing", waitAccessible(new Node("Taku 对话输入框"), 25000));
        long until = System.nanoTime() + 8_000_000_000L;
        while (!thread.equals(preference("conversation", "")) && System.nanoTime() < until) sleep(100);
        assertEquals("Test conversation must be selected", thread, preference("conversation", ""));
        sleep(350);
    }
    void createThread() throws Exception {
        JSONObject options = object("developerInstructions",
                "这是独立、静音的输入框回归。仅按用户要求读取其主动选择的附件或执行 sleep。"
                + "不要调用手机操作，不修改文件，不发通知，不播放声音。图片必须实际读取；无法读取时明确报错。");
        if (phase.equals("failure-async")) {
            String unavailable = "taku-composer-unavailable-" + run;
            options.put("model", unavailable);
            evidence.put("failureTrigger", "unavailable-model-on-isolated-test-thread").put("requestedModel", unavailable);
        }
        thread = NativeSocketProbe.rpc("thread/start", options)
                .getJSONObject("thread").getString("id");
        assertTrue(thread.matches("[a-f0-9-]{36}"));
        evidence.put("threadId", thread); saveEvidence(); openThread();
    }
    void createFixtures() throws Exception {
        assertTrue(fixtures.mkdirs() || fixtures.isDirectory());
        imageCode = getParams().getString("imageCode", "");
        assertTrue("Host-generated image token is required", imageCode.matches("PIC-[0-9]{8}"));
        textCode = "DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // Raw app_process has no initialized Android font manager; prepare pixels on the host.
        try (InputStream in = new FileInputStream("/data/local/tmp/taku-composer-" + run + "-image.png");
                FileOutputStream out = new FileOutputStream(new File(fixtures, "image.png"))) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
        }
        write(new File(fixtures, "document.txt"), "Document verification token: " + textCode + "\n");
        evidence.put("fixtureDirectory", fixtures.getPath()).put("imageCode", imageCode).put("textCode", textCode);
    }

    void clickDocumentText(String text) throws Exception {
        Entry item = new Entry(DOCS, "document", text, false);
        for (int attempt=0; attempt<12 && !item.waitForExists(900); attempt++) {
            AccessibilityNodeInfo list = new Entry(DOCS, "scrollable", "", false).findAccessibilityNodeInfo(500);
            if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
            sleep(150);
        }
        assertTrue("DocumentsUI entry missing: " + text, item.waitForExists(2000));
        assertTrue("DocumentsUI entry could not be activated: " + text, item.click());
    }
    void pick(String filename, boolean image, int expectedCount) throws Exception {
        assertTrue(new Node("添加附件").act());
        UiObject choice = textEntry(PKG, image ? "添加图片" : "添加文件");
        // Popup accessibility can arrive after its opening animation on the shared AVD.
        assertTrue("Attachment menu missing", waitAccessible(choice, 8000)); assertTrue(choice.click());
        assertTrue("Real Android document picker did not open", waitAccessible(new Entry(DOCS, "package", "", false), 25000));
        UiObject roots = new Entry(DOCS, "description", "(?i)(Show roots|Show navigation drawer|显示根目录|显示导航抽屉|显示根路径)", true);
        assertTrue("DocumentsUI navigation button missing", roots.waitForExists(3000)); assertTrue(roots.click());
        refreshAccessibility();
        // The Downloads provider only shows indexed files. Follow the real storage tree instead.
        String model = command("/system/bin/getprop", "ro.product.model");
        UiObject storage = textEntry(DOCS, model);
        if (!storage.waitForExists(1200)) storage = new Entry(DOCS, "text", "(?i)(Internal storage|内部存储设备|内部存储)", true);
        assertTrue("DocumentsUI internal storage root missing: " + model, storage.waitForExists(3000));
        assertTrue(storage.click());
        clickDocumentText("Download"); clickDocumentText(fixtures.getName());
        Entry listView = new Entry(DOCS, "description", "(?i)(List view|列表视图)", true);
        if (listView.waitForExists(1200)) assertTrue(listView.click());
        clickDocumentText(filename);
        // Some modes expose Open; one condition deadline covers either selection flow.
        long returnUntil = System.nanoTime() + 25_000_000_000L;
        boolean returned = false, accepted = false;
        do {
            if (new Node("Taku 对话输入框").exists()) { returned = true; break; }
            UiObject accept = new Entry(DOCS, "text", "(?i)(Open|Select|打开|选择)", true);
            if (!accepted && accept.exists()) { assertTrue(accept.click()); accepted = true; }
            sleep(150);
        } while (System.nanoTime() < returnUntil);
        assertTrue("Picker must return to the assistant", returned);
        expectAttachments(expectedCount);
        evidence.put("actualAndroidPickerSelections", evidence.optInt("actualAndroidPickerSelections") + 1);
        saveEvidence();
    }

    String latest() throws Exception {
        File file = new File(RUNTIME + "latest.id"); return file.exists() ? read(file).trim() : "";
    }
    String ownTask(String previous) throws Exception {
        String id = latest();
        if (id.equals(previous) || !id.matches("[0-9]+")) return "";
        File request = new File(RUNTIME + "tasks/" + id + "/request.json");
        return request.isFile() && thread.equals(new JSONObject(read(request)).optString("conversationId")) ? id : "";
    }
    String waitOwnTask(String previous) throws Exception {
        long until = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < until) { String id = ownTask(previous); if (!id.isEmpty()) return id; sleep(100); }
        fail("No actual queue task for the isolated test thread"); return "";
    }
    JSONObject waitDone(String id) throws Exception {
        long until = System.nanoTime() + 150_000_000_000L;
        File directory = new File(RUNTIME + "tasks/" + id);
        while (System.nanoTime() < until) {
            if (new File(directory, "done").exists()) return new JSONObject(read(new File(directory, "state.json")));
            sleep(200);
        }
        fail("Actual task did not finish within 150 seconds: " + id); return null;
    }
    void saveEvidence() throws Exception { write(new File(output, "result.json"), evidence.toString(2)); }
    void capture(String name) throws Exception {
        command("/system/bin/screencap", "-p", new File(output, name + ".png").getPath());
        // Legacy UiDevice treats its argument as a filename beneath /data/local/tmp.
        String xmlName = "taku-composer-" + run + "-" + phase + "-" + name + ".xml";
        getUiDevice().dumpWindowHierarchy(xmlName);
        File hierarchy = new File("/data/local/tmp", xmlName);
        if (hierarchy.isFile()) {
            write(new File(output, name + ".xml"), read(hierarchy));
            assertTrue("Clean temporary hierarchy", hierarchy.delete());
        }
    }
    void failureMissing() throws Exception {
        String draft = "未发送草稿：附件失效时请保留这段文字。";
        assertTrue(new Node("Taku 对话输入框").replace(draft)); pick("document.txt", false, 1);
        expectDraft(draft); JSONArray selected = attachments();
        File privateFile = new File(selected.getJSONObject(0).getString("path"));
        assertEquals("document.txt", selected.getJSONObject(0).getString("name"));
        assertTrue(privateFile.getCanonicalPath().startsWith(new File(APP + "/files/composer-attachments").getCanonicalPath() + "/"));
        assertTrue("Delete only the freshly selected test attachment", privateFile.delete());
        showKeyboard();
        String previous = latest(); assertTrue(new Node("发送消息").act());
        long until = System.nanoTime() + 20_000_000_000L; String task = ""; boolean rejected = false;
        while (System.nanoTime() < until) {
            task = ownTask(previous);
            if (!task.isEmpty()) { evidence.put("taskId", task).put("taskState", waitDone(task)); break; }
            if (new Entry(PKG, "text",
                    ".*附件.*(失效|不存在|不可|重新选择).*|发送失败，消息仍保留在输入框。|发送未开始，草稿已保留。", true).exists()) {
                rejected = true; break;
            }
            sleep(150);
        }
        evidence.put("queueCreated", !task.isEmpty()).put("prequeueRejectionVisible", rejected); saveEvidence(); capture("failure");
        expectDraft(draft); expectAttachments(1);
        assertEquals("Failed submit must preserve the selected attachment metadata", selected.toString(), attachments().toString());
        assertTrue("Invalid attachment must be rejected before queue publication", rejected && task.isEmpty());
        expectKeyboard(true);
        evidence.put("keyboardPreservedOnFailure", true);
        evidence.put("draftPreserved", true).put("attachmentsPreserved", true).put("noQueuePublished", true);
    }
    void failureAsync() throws Exception {
        String draft = "异步发送失败时保留本草稿及图片附件。";
        assertTrue(new Node("Taku 对话输入框").replace(draft)); pick("image.png", true, 1);
        showKeyboard();
        JSONArray selected = attachments(); String previous = latest(); assertTrue(new Node("发送消息").act());
        String task = waitOwnTask(previous); JSONObject state = waitDone(task);
        evidence.put("taskId", task).put("taskState", state).put("queueCreated", true); saveEvidence();
        assertEquals("The real unavailable-model request must fail", "failed", state.optString("state"));
        expectDraft(draft); expectAttachments(1);
        assertEquals("Asynchronous failure must restore attachment metadata", selected.toString(), attachments().toString());
        expectKeyboard(true);
        evidence.put("keyboardPreservedOnFailure", true);
        capture("failure");
        evidence.put("draftPreserved", true).put("attachmentsPreserved", true).put("realUpstreamFailure", true);
    }
    void addBoth() throws Exception {
        pick("image.png", true, 1); pick("document.txt", false, 2);
    }
    String requestPrompt() {
        return "请实际查看所附图片并读取所附文本文件，只回复图片中的 PIC 验证码和文档中的 DOC 验证码。"
                + "不要根据文件名猜测；不要调用手机操作，不写文件，不发通知。";
    }
    void expectCodes(String answer) {
        assertTrue("Actual image code missing from real response: " + answer, answer.contains(imageCode));
        assertTrue("Actual text-file token missing from real response: " + answer, answer.contains(textCode));
    }
    void sendAttachments() throws Exception {
        assertTrue(new Node("Taku 对话输入框").replace(requestPrompt())); addBoth();
        showKeyboard(); capture("before-send-keyboard");
        String previous = latest(); assertTrue(new Node("发送消息").act());
        String task = waitOwnTask(previous);
        expectKeyboard(false); capture("sent-keyboard-hidden");
        evidence.put("keyboardHiddenAfterSend", true);
        JSONObject state = waitDone(task);
        evidence.put("taskId", task).put("taskState", state); saveEvidence();
        assertEquals(state.optString("message"), "completed", state.optString("state"));
        JSONObject request = new JSONObject(read(new File(RUNTIME + "tasks/" + task + "/request.json")));
        assertEquals(2, request.getJSONArray("attachments").length());
        String answer = read(new File(RUNTIME + "tasks/" + task + "/result.txt")); expectCodes(answer);
        expectDraft(""); expectAttachments(0);
        AccessibilityNodeInfo message = new Entry(PKG, "text", requestPrompt(), false).findAccessibilityNodeInfo(5000);
        assertNotNull("Sent message remains visible", message);
        Bundle locations = new Bundle();
        locations.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, requestPrompt().length() - 4);
        locations.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 4);
        android.os.Parcelable[] chars = null;
        // IME dismissal and streamed-message updates can replace/layout the text node.
        // Obtain geometry from a fresh node after those asynchronous updates settle.
        for (int attempt = 0; attempt < 20 && chars == null; attempt++) {
            message = new Entry(PKG, "text", requestPrompt(), false).findAccessibilityNodeInfo(1000);
            if (message != null && message.refreshWithExtraData(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, locations)) {
                chars = message.getExtras().getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
            }
            if (chars == null) sleep(100);
        }
        assertNotNull("Last line has character geometry", chars);
        assertEquals(4, chars.length);
        android.graphics.Rect bounds = new android.graphics.Rect(); message.getBoundsInScreen(bounds);
        for (android.os.Parcelable value : chars) {
            assertNotNull("Last-line characters are not clipped by attachments", value);
            android.graphics.RectF rect = (android.graphics.RectF) value;
            assertTrue("Last line stays inside its text view", rect.bottom <= bounds.bottom + 1);
        }
        evidence.put("sentMessageLastLineVisible", true);
        capture("completed");
        showKeyboard();
        evidence.put("keyboardReopensOnTap", true);
        evidence.put("reply", answer).put("realImageAndTextUnderstanding", true);
    }
    JSONObject waitNativeTurn(boolean completed) throws Exception {
        long until = System.nanoTime() + 150_000_000_000L;
        File view = new File(RUNTIME + "agent-view/" + thread + ".json");
        while (System.nanoTime() < until) {
            if (view.isFile()) {
                JSONArray turns = new JSONObject(read(view)).optJSONArray("turns");
                if (turns != null) for (int i = 0; i < turns.length(); i++) {
                    JSONObject turn = turns.getJSONObject(i);
                    if (ownedTurn.equals(turn.optString("id"))) {
                        String status = turn.optString("status");
                        if (status.equals("completed") || status.equals("interrupted") || status.equals("failed")) {
                            if (completed) assertEquals(turn.toString(), "completed", status);
                            return turn;
                        }
                    }
                }
            }
            sleep(200);
        }
        fail("Actual native turn did not reach a terminal state"); return null;
    }
    void startNativeSleep() throws Exception {
        ownedTurn = NativeSocketProbe.rpc("turn/start", object("threadId", thread).put("input", new JSONArray()
                .put(object("type", "text").put("text", "请真实执行 /system/bin/sleep 60，等待完成再回复 ORIGINAL。"
                        + "如果收到追加指令，就在同一轮遵循追加指令。"))))
                .getJSONObject("turn").getString("id");
        evidence.put("turnId", ownedTurn); saveEvidence();
    }
    void steerAttachments() throws Exception {
        addBoth(); assertTrue(new Node("Taku 对话输入框").replace(requestPrompt()));
        startNativeSleep(); openThread();
        showKeyboard();
        assertTrue(new Node("追加指令").waitForExists(8000)); assertTrue(new Node("追加指令").act());
        expectKeyboard(false);
        evidence.put("keyboardHiddenAfterSteer", true);
        JSONObject turn = waitNativeTurn(true); StringBuilder answer = new StringBuilder();
        JSONArray items = turn.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.optString("type").equals("agentMessage")) answer.append(item.optString("text"));
        }
        expectCodes(answer.toString()); expectDraft(""); expectAttachments(0);
        // Native completion precedes the UI's next snapshot; capture the rendered terminal state.
        assertTrue("Steered reply exposes its completed copy action", waitAccessible(new Node("复制回复"), 8000));
        assertTrue("Steered composer returns to Send", waitAccessible(new Node("发送消息"), 8000));
        evidence.put("nativeCompletionRendered", true); capture("completed");
        evidence.put("reply", answer.toString()).put("realSteerSameTurn", true).put("steeredAttachmentCount", 2);
    }
    void stopTurn() throws Exception {
        assertTrue(new Node("Taku 对话输入框").replace(
                "请真实执行 /system/bin/sleep 60，等待命令完成后再回复。不要执行其他操作。"));
        String previous = latest(); assertTrue(new Node("发送消息").act());
        String task = waitOwnTask(previous);
        evidence.put("taskId", task).put("queueCreated", true).put("submittedThroughComposer", true);
        saveEvidence();
        long until = System.nanoTime() + 30_000_000_000L;
        do {
            JSONObject nativeState = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
            ownedTurn = nativeState.optString("activeTurnId");
            if (!ownedTurn.isEmpty()) break;
            sleep(150);
        } while (System.nanoTime() < until);
        assertFalse("The composer task must start a real native turn in its own thread", ownedTurn.isEmpty());
        evidence.put("turnId", ownedTurn); saveEvidence();
        assertTrue(new Node("停止当前执行").waitForExists(8000));
        assertTrue(new Node("Taku 对话输入框").replace("尚未提交的追加指令"));
        assertTrue(new Node("追加指令").waitForExists(3000));
        assertTrue("Stop stays available with an unsent draft", new Node("停止当前执行").exists());
        assertTrue(new Node("停止当前执行").waitForExists(3000)); assertTrue(new Node("停止当前执行").act());
        JSONObject state = waitDone(task);
        JSONObject turn = waitNativeTurn(false);
        evidence.put("taskState", state).put("nativeTurnStatus", turn.optString("status")); saveEvidence();
        assertEquals("Stop must cancel the actual composer queue task", "cancelled", state.optString("state"));
        assertEquals("Stop must interrupt the matching real upstream turn", "interrupted", turn.optString("status"));
        assertTrue("Stopped composer must return to the Send action", waitAccessible(new Node("发送消息"), 25000));
        assertFalse("Stop must disappear after interruption", new Node("停止当前执行").exists());
        expectDraft("尚未提交的追加指令");
        assertTrue("Native stopped state stays visible", new Entry(PKG, "text", ".*本轮已停止.*", true).waitForExists(8000));
        evidence.put("nativeStoppedStateRendered", true);
        evidence.put("stopWithDraftPreserved", true).put("actualTurnInterrupted", true).put("actualTaskCancelled", true)
                .put("composerReturnedToSend", true); capture("stopped");
    }

    public void testComposerPhase() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        run = getParams().getString("runId", ""); phase = getParams().getString("phase", "");
        assertTrue("A unique host runId is required", run.matches("[a-zA-Z0-9-]{1,80}"));
        assertTrue("Unknown phase", phase.matches("failure-missing|failure-async|attachments|steer|stop"));
        output = new File("/data/local/tmp/taku-composer-tests/" + run + "/" + phase);
        fixtures = new File("/sdcard/Download/taku-composer-" + run + "-" + phase);
        assertTrue(output.mkdirs());
        evidence = object("phase", phase).put("runId", run).put("modelResponsesSimulated", false).put("passed", false);
        try {
            command("/system/bin/input", "-d", "0", "keyevent", "KEYCODE_HOME");
            createFixtures(); createThread();
            switch (phase) {
                case "failure-missing": failureMissing(); break;
                case "failure-async": failureAsync(); break;
                case "attachments": sendAttachments(); break;
                case "steer": steerAttachments(); break;
                case "stop": stopTurn(); break;
                default: throw new AssertionError("Unknown phase");
            }
            evidence.put("passed", true);
        } catch (Throwable failure) {
            evidence.put("failure", failure.toString());
            try { capture("assertion-failure"); } catch (Exception ignored) {}
            if (failure instanceof Error) throw (Error) failure;
            throw (Exception) failure;
        } finally {
            // Only interrupt this test's owned native turn; never inspect or cancel unrelated work.
            if (!ownedTurn.isEmpty()) try {
                JSONObject state = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
                if (ownedTurn.equals(state.optString("activeTurnId")))
                    NativeSocketProbe.rpc("turn/interrupt", object("threadId", thread).put("turnId", ownedTurn));
            } catch (Exception cleanup) { evidence.put("turnCleanupError", cleanup.toString()); }
            saveEvidence();
        }
    }
}
