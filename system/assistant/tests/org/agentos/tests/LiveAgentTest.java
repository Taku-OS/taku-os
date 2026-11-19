package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.uiautomator.core.Configurator;

import org.json.*;
import org.w3c.dom.*;

import java.io.*;

import javax.xml.parsers.DocumentBuilderFactory;

/** Real upstream model + read-only Android tool acceptance. Never used without device login. */
public final class LiveAgentTest extends AgentInterfaceTest {
    private static final String ROOT = "/data/user/0/org.agentos.assistant/files/runtime/";
    private String threadId = "";


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


    @Override void capture(String name) throws Exception {
        sleep(700);
        command("/system/bin/screencap", "-p", "/data/local/tmp/live-agent-" + name + ".png");
    }

    private File submit(String prompt) throws Exception {
        File latest = new File(ROOT, "latest.id");
        String previous = latest.exists() ? read(latest).trim() : "";
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
        assertTrue(composer().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
        click(control("send-message", 10000));
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
        connect();
        String original = preference("conversation");
        String testThread = NativeSocketProbe.rpc("thread/start", new JSONObject()).getJSONObject("thread").getString("id");
        try {
            open(testThread);
            expectDraft(testThread, "");
            File first = submit("请只回复「Taku 已连接」。不要调用工具。");
            String reply = read(new File(first, "result.txt"));
            assertTrue("Real assistant reply missing", reply.contains("Taku 已连接"));
            node(reply.trim());
            capture("chat");
            String version = command("/system/bin/getprop", "ro.build.version.release").trim();
            String model = command("/system/bin/getprop", "ro.product.model").trim();
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
            openRoute("/system-ai");
            node("Taku AI");
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
                                    command("/system/bin/getprop", "ro.kernel.qemu").trim()));
            try (FileOutputStream output =
                    new FileOutputStream("/data/local/tmp/live-agent-acceptance.json")) {
                output.write(evidence.toString().getBytes("UTF-8"));
            }
        } finally {
            if (!original.isEmpty()) open(original);
        }
    }
}
