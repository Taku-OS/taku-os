package org.agentos.tests;

import android.os.Bundle;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import java.io.*;

/** Kills the Expo app process while the Kotlin backend and native broker remain alive. */
public final class DrawerRecoveryTest extends UiAutomatorTestCase {
    static final String CHAT = "org.agentos.agent";
    static final String OPEN_ACCESS = "content://org.agentos.assistant.open-access";
    static final File TASKS = new File("/data/user/0/org.agentos.assistant/files/runtime/tasks");
    static final String INPUT = "message-input";
    static final String REBOOT_DRAFT = "重启验收草稿：车票与酒店待确认。";
    File output;
    UiAutomation automation;
    void refreshAccessibility() throws Exception {
        if (automation == null) {
            java.lang.reflect.Method method=getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
            method.setAccessible(true);Object bridge=method.invoke(getUiDevice());
            for(Class<?> type=bridge.getClass();type!=null;type=type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field=type.getDeclaredField("mUiAutomation");
                    field.setAccessible(true);automation=(UiAutomation)field.get(bridge);break;
                } catch(NoSuchFieldException ignored) {}
            }
            assertNotNull("Use the existing UiAutomator connection",automation);
            AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            try { automation.waitForIdle(100,1500); } catch(java.util.concurrent.TimeoutException ignored) {}
        }
        UiAutomation.class.getMethod("clearCache").invoke(automation);
    }
    class Node extends UiObject {
        final String expected;
        final boolean text;
        Node(String id) { this(id, false); }
        Node(String expected, boolean text) {
            super(new UiSelector().packageName(CHAT));
            this.expected = expected; this.text = text;
        }
        AccessibilityNodeInfo matching(AccessibilityNodeInfo node, int depth, int[] budget) {
            if (node == null || depth > 50 || --budget[0] < 0) return null;
            CharSequence visibleText = node.getText();
            String id = node.getViewIdResourceName();
            if (node.isVisibleToUser() && node.isEnabled() && CHAT.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                    && (text ? visibleText != null && expected.equals(visibleText.toString().trim())
                             : expected.equals(id) || (CHAT + ":id/" + expected).equals(id))) return node;
            for(int i=0;i<node.getChildCount();i++) {
                AccessibilityNodeInfo found=matching(node.getChild(i),depth+1,budget);
                if(found!=null)return found;
            }
            return null;
        }
        @Override protected AccessibilityNodeInfo findAccessibilityNodeInfo(long timeout) {
            long until=System.nanoTime()+timeout*1_000_000L;
            try {
                do {
                    refreshAccessibility();
                    // Background display 2 may own the active window; recovery operates only display 0.
                    List<AccessibilityWindowInfo> windows=automation.getWindowsOnAllDisplays().get(0);
                    if(windows!=null)for(AccessibilityWindowInfo window:windows) {
                        AccessibilityNodeInfo found=matching(window.getRoot(),0,new int[]{4096});
                        if(found!=null)return found;
                    }
                    if(System.nanoTime()<until)sleep(100);
                } while(System.nanoTime()<until);
                return null;
            } catch(Exception failure){throw new IllegalStateException(failure);}
        }
        boolean replace(String value){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
        String value(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);if(n==null)throw new AssertionError("Composer node missing");return n.isShowingHintText()||n.getText()==null?"":n.getText().toString();}
        boolean act(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    }
    String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertEquals(args[0] + ": " + result, 0, process.waitFor());
        return result;
    }
    JSONObject read(File path) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            return new JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    void save(JSONObject result) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(output, "acceptance.json"))) {
            out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
    JSONObject access(String method, JSONObject args) throws Exception {
        String reply = command("/system/bin/content", "call", "--user", "0", "--uri", OPEN_ACCESS,
                "--method", method, "--arg", args.toString());
        int start = reply.indexOf("Bundle[{result=");
        int end = reply.lastIndexOf("}]");
        assertTrue("Missing Open Access response for " + method + ": " + reply, start >= 0 && end > start);
        JSONObject envelope = new JSONObject(reply.substring(start + "Bundle[{result=".length(), end));
        assertTrue(method + ": " + envelope, envelope.optBoolean("ok"));
        return envelope.getJSONObject("result");
    }
    JSONObject key(String name, String value) throws Exception { return new JSONObject().put(name, value); }
    void open(String thread) throws Exception {
        command("/system/bin/am", "start", "-W", "--display", "0", "-a", "android.intent.action.MAIN",
                "-n", CHAT + "/.MainActivity", "--es", "threadId", thread);
        assertTrue("Expo composer missing", new Node(INPUT).waitForExists(25000));
    }
    void expectDraft(String thread, String expected) throws Exception {
        long until = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < until) {
            if (expected.equals(new Node(INPUT).value()) && expected.equals(publicDraft(thread))) return;
            sleep(150);
        }
        assertEquals("Expo composer text", expected, new Node(INPUT).value());
        assertEquals("Public composer text", expected, publicDraft(thread));
    }
    String publicDraft(String thread) throws Exception {
        JSONObject draft = access("composer/read", key("threadId", thread));
        assertEquals(thread, draft.getString("threadId"));
        return draft.getString("text");
    }
    String frontendPid() throws Exception {
        String pid = command("/system/bin/pidof", CHAT);
        assertTrue("Expected one Expo app process", pid.matches("[0-9]+"));
        return pid;
    }
    void killAndReopen(String thread) throws Exception {
        String before = frontendPid();
        command("/system/bin/kill", "-9", before);
        long until = System.nanoTime() + 5_000_000_000L;
        File process = new File("/proc/" + before);
        while (process.exists() && System.nanoTime() < until) sleep(100);
        assertFalse("The original Expo process must exit", process.exists());
        open(thread);
        assertFalse("A new Expo process must open the conversation", before.equals(frontendPid()));
    }
    void capture(String name) throws Exception {
        command("/system/bin/screencap", "-p", new File(output, name + ".png").getPath());
    }
    String ownTask(String thread) throws Exception {
        File[] tasks = TASKS.listFiles((dir, name) -> name.matches("[0-9]{1,20}"));
        assertNotNull("Task directory is unavailable", tasks);
        String owned = "";
        for (File task : tasks) {
            if (!new File(task, "ready").isFile()) continue;
            File request = new File(task, "request.json");
            if (!request.isFile() || !thread.equals(read(request).optString("conversationId"))) continue;
            assertTrue("More than one task published for " + thread, owned.isEmpty());
            owned = task.getName();
        }
        return owned;
    }
    JSONObject taskDetail(String id) throws Exception { return access("readTaskDetail", key("taskId", id)); }
    String realReply(String thread, String task) throws Exception {
        return access("manageAssistant", key("operation", "taskFiles/read").put("threadId", thread)
                .put("taskId", task).put("path", "result.txt")).getString("text");
    }
    void expectCompletedTurn(String thread, String turnId) throws Exception {
        JSONArray turns = NativeSocketProbe.rpc("thread/read", key("threadId", thread).put("includeTurns", true))
                .getJSONObject("thread").getJSONArray("turns");
        assertEquals("Opening the conversation must not replay the request", 1, turns.length());
        JSONObject turn = turns.getJSONObject(0);
        assertEquals(turnId, turn.getString("id"));
        assertEquals("completed", turn.getString("status"));
    }
    void verifyReply(String thread, String task, String turn, String marker) throws Exception {
        JSONObject detail = taskDetail(task);
        assertEquals(detail.getString("message"), "completed", detail.getString("state"));
        expectCompletedTurn(thread, turn);
        String reply = realReply(thread, task);
        assertTrue("Native task reply is missing the marker: " + reply, reply.contains(marker));
        assertTrue("Expo must show the real reply", new Node(marker, true).waitForExists(25000));
        assertEquals("Recovery must not publish another task", task, ownTask(thread));
    }
    void checkReboot(String phase) throws Exception {
        JSONObject accepted = read(new File(output, "acceptance.json"));
        String thread = accepted.getString("threadId");
        open(thread);
        if (phase.equals("prepareReboot")) {
            assertTrue(new Node(INPUT).replace(REBOOT_DRAFT));
            expectDraft(thread, REBOOT_DRAFT);
            capture("reboot-before");
            return;
        }
        expectDraft(thread, REBOOT_DRAFT);
        verifyReply(thread, accepted.getString("taskId"), accepted.getString("turnId"), accepted.getString("marker"));
        capture("reboot-after");
        assertTrue(new Node(INPUT).replace(""));
        expectDraft(thread, "");
        save(accepted.put("draftSurvivesAndroidReboot", true).put("actualReplyHistorySurvivesAndroidReboot", true));
    }
    public void testDraftAndActualTaskSurviveAppDeath() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        String run = getParams().getString("runId", "");
        assertTrue("A unique host runId is required", run.matches("[a-zA-Z0-9-]{1,80}"));
        output = new File("/data/local/tmp/taku-recovery-tests", run);
        String phase = getParams().getString("phase", "");
        if (phase.equals("prepareReboot") || phase.equals("verifyReboot")) {
            checkReboot(phase);
            return;
        }
        assertTrue("Unknown recovery-test phase", phase.isEmpty());
        assertTrue(output.mkdirs());
        String thread = NativeSocketProbe.rpc("thread/start", key("developerInstructions",
                "这是静音恢复验收，只执行本轮用户明确指定的命令，不调用手机写操作，不发通知，不播放声音。"))
                .getJSONObject("thread").getString("id");
        assertTrue(thread.matches("[a-f0-9-]{36}"));
        JSONObject result = key("threadId", thread).put("modelResponsesSimulated", false);
        save(result);
        String markerDraft = "Recovery fixture " + thread;
        String revision = access("composer/read", key("threadId", thread)).getString("revision");
        access("composer/write", key("threadId", thread).put("revision", revision).put("text", markerDraft));
        open(thread);
        expectDraft(thread, markerDraft);
        String draft = "旅行草稿：明天先整理行李，再确认车票。";
        assertTrue(new Node(INPUT).replace(draft));
        expectDraft(thread, draft);
        capture("draft-before");
        killAndReopen(thread);
        expectDraft(thread, draft);
        capture("draft-after");
        command("/system/bin/am", "start", "-W", "--display", "0", "-n", "com.android.settings/.Settings");
        open(thread);
        expectDraft(thread, draft);
        String marker = "RECOVERY_TASK_OK_" + run;
        assertTrue(new Node(INPUT).replace("请真实执行 /system/bin/sleep 15，完成后仅回复 " + marker + "。"));
        assertTrue(new Node("send-message").act());
        String task = "", turn = "";
        long until = System.nanoTime() + 45_000_000_000L;
        while (System.nanoTime() < until) {
            if (task.isEmpty()) task = ownTask(thread);
            JSONObject state = NativeSocketProbe.exchange(key("op", "state").put("threadId", thread));
            if (!task.isEmpty() && taskDetail(task).getString("state").equals("running")) {
                turn = state.optString("activeTurnId");
                if (!turn.isEmpty()) break;
            }
            sleep(250);
        }
        assertFalse("The task must be running in a real upstream turn before process death", turn.isEmpty());
        result.put("taskId", task).put("turnId", turn).put("marker", marker);
        save(result);
        killAndReopen(thread);
        until = System.nanoTime() + 120_000_000_000L;
        while (taskDetail(task).getString("state").matches("queued|running") && System.nanoTime() < until) sleep(250);
        verifyReply(thread, task, turn, marker);
        expectDraft(thread, "");
        capture("completed");
        save(result.put("draftSurvivesAbruptAppDeath", true).put("draftSurvivesBackground", true)
                .put("actualModelTaskSurvivesAppDeath", true).put("actualReplyVisibleAfterRecovery", true)
                .put("reply", realReply(thread, task)));
    }
}
