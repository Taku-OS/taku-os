package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.*;
import org.w3c.dom.*;

/** Sends only synthetic capability requests through the actual chat UI. No model mocks. */
public final class AgentCapabilityTest extends UiAutomatorTestCase {
    private static final String PACKAGE = "org.agentos.assistant";
    private static final String ROOT = "/data/user/0/" + PACKAGE + "/files/runtime/";
    private static final String OUTPUT = "/data/local/tmp/taku-capability/";

    private static final class Control extends UiObject {
        Control(UiSelector selector) { super(selector.packageName(PACKAGE)); }
        boolean action(int action) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(3000);
            return node != null && node.performAction(action);
        }
        boolean replace(String text) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(3000);
            Bundle value = new Bundle();
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value);
        }
    }

    private Control description(String text) { return new Control(new UiSelector().description(text)); }
    private Control text(String text) { return new Control(new UiSelector().text(text)); }
    private void click(Control control) throws Exception {
        if (!control.exists()) new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true))
                .scrollIntoView(control.getSelector());
        assertTrue("UI action unavailable", control.action(AccessibilityNodeInfo.ACTION_CLICK));
        sleep(400);
    }
    private String read(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, n);
                if (bytes.size() > 2097152) throw new IOException("Acceptance record too large");
            }
            return bytes.toString("UTF-8");
        }
    }
    private void write(File file, JSONObject value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.toString().getBytes("UTF-8"));
        }
    }
    private String preference(String name) throws Exception {
        File file = new File("/data/user/0/" + PACKAGE + "/shared_prefs/agent-settings.xml");
        if (!file.exists()) return "";
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element) {
            Element node = (Element) nodes.item(i);
            if (name.equals(node.getAttribute("name"))) return node.hasAttribute("value")
                    ? node.getAttribute("value") : node.getTextContent();
        }
        return "";
    }
    private void command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096]; while (input.read(buffer) != -1) { /* Do not log device data. */ }
        }
        assertEquals("Acceptance command failed", 0, process.waitFor());
    }
    private void open(String id) throws Exception {
        if (id.isEmpty()) command("am", "start", "-n", PACKAGE + "/.PanelActivity");
        else command("am", "start", "-n", PACKAGE + "/.PanelActivity", "--es", "conversationId", id);
        assertTrue(description("向下拖动收起助手").waitForExists(10000));
        sleep(800);
        if (text("Back to settings").exists()) click(text("Back to settings"));
        if (text("返回对话").exists()) click(text("返回对话"));
    }

    public void testCapabilityRequest() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        JSONObject plan = new JSONObject(read(new File(OUTPUT, "plan.json")));
        String caseId = plan.getString("id");
        assertTrue(caseId.matches("[a-z0-9-]+"));
        String originalThread = preference("conversation");
        boolean originalScreenshot = preference("includeScreenshot").equals("true");
        String originalStyle = preference("responseStyle");
        String testThread = plan.optString("threadId");
        open(testThread);
        try {
            click(description("助手设置"));
            if (text("Include main-screen screenshot when sending").isChecked()) click(text("Include main-screen screenshot when sending"));
            if (plan.has("responseStyle")) click(text(plan.getString("responseStyle").equals("concise")?"Concise":"Detailed"));
            click(text("返回对话"));
            if (testThread.isEmpty()) {
                click(description("切换会话")); click(text("New conversation")); sleep(1200);
                click(description("助手设置"));
                Control title = description("Edit conversation title");
                if (!title.exists()) new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true))
                        .scrollIntoView(title.getSelector());
                assertTrue(title.replace("Agent 能力实测")); click(text("保存标题"));
                sleep(600); click(text("返回对话"));
            }
            File latest = new File(ROOT, "latest.id");
            String previous = latest.exists() ? read(latest).trim() : "";
            assertTrue(description("Taku 对话输入框").replace(plan.getString("prompt")));
            click(description("发送消息"));
            long started = System.currentTimeMillis();
            String id = previous;
            while (id.equals(previous) && System.currentTimeMillis() - started < 10000) {
                sleep(150); if (latest.exists()) id = read(latest).trim();
            }
            assertFalse("No actual task published", id.equals(previous));
            File task = new File(ROOT + "tasks/" + id);
            boolean cancelled = false;
            long timeout = Math.min(600, Math.max(30, plan.optInt("timeoutSeconds", 180))) * 1000L;
            JSONObject observation = new JSONObject().put("id", caseId).put("taskId", id);
            write(new File(OUTPUT, caseId + ".running.json"), observation);
            while (!new File(task, "done").exists() && System.currentTimeMillis() - started < timeout) {
                if (plan.optBoolean("cancelAfterCommand") && !cancelled
                        && new File(task, "runtime-info.json").exists()) {
                    JSONObject runtime = new JSONObject(read(new File(task, "runtime-info.json")));
                    File view = new File(ROOT + "agent-view/" + runtime.getString("threadId") + ".json");
                    if (view.exists()) {
                        JSONArray turns = new JSONObject(read(view)).optJSONArray("turns");
                        if (turns != null) for (int i = 0; i < turns.length(); i++) {
                            JSONObject turn = turns.getJSONObject(i);
                            if (!turn.optString("id").equals(runtime.optString("turnId"))) continue;
                            JSONArray items = turn.optJSONArray("items");
                            if (items != null) for (int j = 0; j < items.length(); j++)
                                if (items.getJSONObject(j).optString("type").equals("commandExecution")) {
                                    JSONObject command = items.getJSONObject(j);
                                    if (!command.optString("status").equals("inProgress")) continue;
                                    observation.put("commandObservedBeforeStop", new JSONObject()
                                            .put("command", command.optString("command"))
                                            .put("status", command.optString("status")));
                                    write(new File(OUTPUT, caseId + ".running.json"), observation);
                                    click(description("停止当前执行")); cancelled = true; break;
                                }
                        }
                    }
                }
                sleep(250);
            }
            if (!new File(task, "done").exists()) {
                if (description("停止当前执行").exists()) click(description("停止当前执行"));
                throw new IOException("Real model turn timeout; stop requested");
            }
            JSONObject state = new JSONObject(read(new File(task, "state.json")));
            observation.put("state", state.optString("state"))
                    .put("seconds", (System.currentTimeMillis() - started) / 1000.0)
                    .put("cancelRequested", cancelled);
            File info = new File(task, "runtime-info.json");
            JSONArray commands = new JSONArray(); JSONArray types = new JSONArray();
            if (info.exists()) {
                JSONObject runtime = new JSONObject(read(info));
                observation.put("threadId", runtime.optString("threadId"))
                        .put("turnId", runtime.optString("turnId"))
                        .put("model", runtime.optString("model"))
                        .put("turnStarted", runtime.optBoolean("turnStarted"));
                File view = new File(ROOT + "agent-view/" + runtime.getString("threadId") + ".json");
                JSONArray turns = new JSONObject(read(view)).getJSONArray("turns");
                for (int i = 0; i < turns.length(); i++) {
                    JSONObject turn = turns.getJSONObject(i);
                    if (!turn.optString("id").equals(runtime.optString("turnId"))) continue;
                    observation.put("nativeStatus", turn.optString("status"));
                    JSONArray items = turn.getJSONArray("items");
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j); types.put(item.optString("type"));
                        if (item.optString("type").equals("commandExecution")) commands.put(
                                new JSONObject().put("command", item.optString("command"))
                                        .put("status", item.optString("status"))
                                        .put("exitCode", item.optInt("exitCode", -1)));
                    }
                }
            }
            File reply = new File(task, "result.txt");
            observation.put("reply", reply.exists() ? read(reply) : "")
                    .put("commands", commands).put("itemTypes", types);
            write(new File(OUTPUT, caseId + ".json"), observation);
            sleep(700);
            command("screencap", "-p", OUTPUT + caseId + ".png");
        } finally {
            open(preference("conversation"));
            click(description("助手设置"));
            if (plan.has("responseStyle") && !originalStyle.isEmpty()) click(text(originalStyle.equals("concise")?"Concise":"Detailed"));
            Control screenshot = text("Include main-screen screenshot when sending");
            if (!screenshot.exists()) new UiScrollable(new UiSelector().packageName(PACKAGE).scrollable(true))
                    .scrollIntoView(screenshot.getSelector());
            if (screenshot.isChecked() != originalScreenshot) click(screenshot);
            click(text("返回对话")); getUiDevice().pressBack();
            if (!originalThread.isEmpty()) open(originalThread);
        }
    }
}
