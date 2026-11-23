package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import java.io.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.*;
import org.w3c.dom.*;

/** Sends only synthetic capability requests through the actual chat UI. No model mocks. */
public final class AgentCapabilityTest extends AgentInterfaceTest {
    private static final String PACKAGE = "org.agentos.assistant";
    private static final String ROOT = "/data/user/0/" + PACKAGE + "/files/runtime/";
    private static final String OUTPUT = "/data/local/tmp/taku-capability/";

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

    public void testCapabilityRequest() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        connect();
        JSONObject plan = new JSONObject(read(new File(OUTPUT, "plan.json")));
        String caseId = plan.getString("id");
        assertTrue(caseId.matches("[a-z0-9-]+"));
        String originalThread = preference("conversation");
        String requestedThread = plan.optString("threadId");
        String testThread = requestedThread.isEmpty()
                ? NativeSocketProbe.rpc("thread/start", new JSONObject()).getJSONObject("thread").getString("id")
                : requestedThread;
        JSONObject key = new JSONObject().put("threadId", testThread);
        JSONObject originalOptions = plan.has("responseStyle")
                ? access("composer/options/read", key).optJSONObject("options") : null;
        Object originalStyle = originalOptions == null ? JSONObject.NULL
                : originalOptions.opt("responseStyle");
        assertEquals("Use an empty draft on the test thread", "", composerState(testThread).getString("text"));
        assertEquals("Use a test thread without pending attachments", 0,
                composerState(testThread).getJSONArray("attachments").length());
        try {
            if (plan.has("responseStyle")) updateStyle(testThread, plan.getString("responseStyle"));
            open(testThread);
            expectDraft(testThread, "");
            File latest = new File(ROOT, "latest.id");
            String previous = latest.exists() ? read(latest).trim() : "";
            Bundle value = new Bundle();
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.getString("prompt"));
            assertTrue(composer().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value));
            click(control("send-message", 10000));
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
                                    click(control("stop-turn", 10000)); cancelled = true; break;
                                }
                        }
                    }
                }
                sleep(250);
            }
            if (!new File(task, "done").exists()) {
                AccessibilityNodeInfo stop = control("stop-turn", 1000);
                if (stop != null) click(stop);
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
            try {
                if (plan.has("responseStyle")) updateStyle(testThread, originalStyle == null ? JSONObject.NULL : originalStyle);
            } finally {
                if (!originalThread.isEmpty()) open(originalThread);
            }
        }
    }

    private void updateStyle(String thread, Object style) throws Exception {
        access("composer/options/write", new JSONObject().put("threadId", thread)
                .put("options", new JSONObject().put("responseStyle", style)));
    }
}
