package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import org.json.*;
import org.w3c.dom.*;

import java.io.*;

import javax.xml.parsers.DocumentBuilderFactory;

/** Real upstream model + read-only Android tool acceptance. Never used without device login. */
public final class LiveAgentTest extends UiAutomatorTestCase {
    private static final String PACKAGE = "org.agentos.assistant";
    private static final String ROOT = "/data/user/0/org.agentos.assistant/files/runtime/";
    private String threadId = "";

    private static final class Node extends UiObject {
        Node(UiSelector selector) {
            super(selector.packageName(PACKAGE));
        }

        boolean action(int operation) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(2500);
            return node != null && node.performAction(operation);
        }

        boolean replace(String value) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(2500);
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return node != null
                    && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        }
    }

    private Node description(String value) {
        return new Node(new UiSelector().description(value));
    }

    private Node text(String value) {
        return new Node(new UiSelector().text(value));
    }

    private void visible(Node node) throws Exception {
        if (!node.exists())
            new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true))
                    .scrollIntoView(node.getSelector());
    }

    private void click(Node node) throws Exception {
        visible(node);
        assertTrue(node.action(AccessibilityNodeInfo.ACTION_CLICK));
        sleep(400);
    }

    private String command(String... args) throws Exception {
        Process process = Runtime.getRuntime().exec(args);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = process.getInputStream().read(buffer)) != -1) out.write(buffer, 0, n);
        assertEquals("Device command failed", 0, process.waitFor());
        return out.toString("UTF-8").trim();
    }

    private String read(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 2097152) throw new IOException("Record too large");
            }
            return out.toString("UTF-8");
        }
    }

    private String preference(String name) throws Exception {
        Document document =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(
                                new File(
                                        "/data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml"));
        NodeList values = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < values.getLength(); i++)
            if (values.item(i) instanceof Element) {
                Element value = (Element) values.item(i);
                if (name.equals(value.getAttribute("name")))
                    return value.hasAttribute("value")
                            ? value.getAttribute("value")
                            : value.getTextContent();
            }
        return "";
    }

    private void open(String id) throws Exception {
        if (id.isEmpty()) command("/system/bin/am", "start", "-n", PACKAGE + "/.PanelActivity");
        else
            command(
                    "/system/bin/am",
                    "start",
                    "-n",
                    PACKAGE + "/.PanelActivity",
                    "--es",
                    "conversationId",
                    id);
        assertTrue(description("向下拖动收起助手").waitForExists(8000));
        sleep(1000);
        if (text("Back to settings").exists()) click(text("Back to settings"));
        if (text("返回对话").exists()) click(text("返回对话"));
    }

    private void capture(String name) throws Exception {
        sleep(700);
        command("/system/bin/screencap", "-p", "/data/local/tmp/live-agent-" + name + ".png");
    }

    private File submit(String prompt) throws Exception {
        String previous = read(new File(ROOT, "latest.id")).trim();
        assertTrue(description("Taku 对话输入框").replace(prompt));
        click(description("发送消息"));
        long deadline = System.nanoTime() + 10_000_000_000L;
        String id;
        do {
            sleep(200);
            id = read(new File(ROOT, "latest.id")).trim();
        } while (previous.equals(id) && System.nanoTime() < deadline);
        assertFalse("A real task must be published", previous.equals(id));
        File task = new File(ROOT + "tasks/" + id);
        deadline = System.nanoTime() + 120_000_000_000L;
        while (!new File(task, "done").isFile() && System.nanoTime() < deadline) sleep(400);
        JSONObject state = new JSONObject(read(new File(task, "state.json")));
        assertEquals("Real model task did not complete", "completed", state.getString("state"));
        JSONObject info = new JSONObject(read(new File(task, "runtime-info.json")));
        assertTrue(info.optBoolean("turnStarted"));
        assertFalse(info.optBoolean("screenshotAttached"));
        threadId = info.getString("threadId");
        return task;
    }

    public void testRealModelAndSystemRead() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        String original = preference("conversation");
        boolean screenshot = preference("includeScreenshot").equals("true");
        command("/system/bin/am", "start", "-n", "com.android.settings/.Settings");
        open("");
        try {
            click(description("助手设置"));
            if (text("Include main-screen screenshot when sending").isChecked()) click(text("Include main-screen screenshot when sending"));
            click(text("返回对话"));
            click(description("切换会话"));
            click(text("New conversation"));
            sleep(1000);
            click(description("助手设置"));
            visible(description("Edit conversation title"));
            assertTrue(description("Edit conversation title").replace("真实连接验收"));
            click(text("保存标题"));
            sleep(700);
            click(text("返回对话"));
            assertTrue(text("真实连接验收").waitForExists(7000));
            File first = submit("请只回复「Taku 已连接」。不要调用工具。");
            String reply = read(new File(first, "result.txt"));
            assertTrue("Real assistant reply missing", reply.contains("Taku 已连接"));
            assertTrue(text(reply).waitForExists(7000));
            capture("chat");
            String version = command("/system/bin/getprop", "ro.build.version.release");
            String model = command("/system/bin/getprop", "ro.product.model");
            File second =
                    submit(
                            "请真实调用 /system/bin/getprop ro.build.version.release 和"
                                + " /system/bin/getprop ro.product.model，只读取、不修改系统。然后用一句中文告诉我读到的"
                                + " Android 版本和设备型号。");
            String result = read(new File(second, "result.txt"));
            assertTrue("Android version must match actual device", result.contains(version));
            assertTrue("Model must match actual device", result.contains(model));
            JSONObject info = new JSONObject(read(new File(second, "runtime-info.json")));
            JSONObject thread =
                    new JSONObject(read(new File(ROOT + "agent-view/" + threadId + ".json")));
            JSONArray turns = thread.getJSONArray("turns");
            int commands = 0;
            for (int i = 0; i < turns.length(); i++) {
                JSONObject turn = turns.getJSONObject(i);
                if (!turn.optString("id").equals(info.getString("turnId"))) continue;
                assertEquals("completed", turn.getString("status"));
                JSONArray items = turn.getJSONArray("items");
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    if (item.optString("type").equals("commandExecution")
                            && item.optString("command").contains("getprop")) {
                        assertEquals("completed", item.getString("status"));
                        assertEquals(0, item.optInt("exitCode", -1));
                        commands++;
                    }
                }
            }
            assertTrue("Native command execution evidence required", commands > 0);
            capture("system-read");
            click(description("助手设置"));
            capture("connected-settings");
            JSONObject evidence =
                    new JSONObject()
                            .put("nativeThreadId", threadId)
                            .put("nativeTurnId", info.getString("turnId"))
                            .put("model", info.getString("model"))
                            .put("realModelReplyVerified", true)
                            .put("realAndroidReadOnlyToolVerified", true)
                            .put("nativeCommandCount", commands)
                            .put("androidVersion", version)
                            .put("deviceModel", model)
                            .put("credentialsExported", false)
                            .put("physicalDeviceVerified", !"1".equals(
                                    command("/system/bin/getprop", "ro.kernel.qemu")));
            try (FileOutputStream output =
                    new FileOutputStream("/data/local/tmp/live-agent-acceptance.json")) {
                output.write(evidence.toString().getBytes("UTF-8"));
            }
        } finally {
            if (!text("返回对话").exists()) click(description("助手设置"));
            visible(text("Include main-screen screenshot when sending"));
            if (text("Include main-screen screenshot when sending").isChecked() != screenshot) click(text("Include main-screen screenshot when sending"));
            click(text("返回对话"));
            getUiDevice().pressBack();
            sleep(500);
            if (!original.isEmpty()) open(original);
        }
    }
}
