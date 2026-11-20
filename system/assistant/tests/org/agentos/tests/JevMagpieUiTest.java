package org.agentos.tests;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.Configurator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual Expo UI, Magpie and Codex with a deterministic model upstream. */
public class JevMagpieUiTest extends AgentInterfaceTest {
    static final String ACCEPT = "Accept screen suggestion and hand to Agent";
    static final String PAGE = "合成聊天 · Jev/Magpie 协议验收";

    public void testJevMagpieChain() throws Exception {
        String thread = getParams().getString("thread");
        assertNotNull("Fixture conversation", thread);
        String model = "group/jev-fixture-vision";
        String pending = "Jev 迁移回归草稿：这段不要发送。";
        String attachment = "keep-jev-fixture.txt";
        prepare();
        JSONObject connection = connection();
        try {
            stageDraft(thread, pending, attachment);
            enableJev(model);
            checkDraft(thread, pending, attachment);
            showSyntheticPage();
            JSONObject capture = requestCapture();
            assertNotNull("Jev should offer a suggestion", findExpo(ACCEPT, ACCEPT, 35000));
            capture("image-hint");
            JSONObject receipt = readyReceipt(capture, model);
            JSONObject job = acceptAndWait(capture, 90000);
            JSONObject turn = backgroundTurn(job, thread, pending, attachment);
            assertTrue("Fixture Agent response", agentReply(turn).contains("已整理合成约饭方案"));
            checkDraft(thread, pending, attachment);
            capture("image-completed");
            assertConnection(connection);
            JSONObject result = new JSONObject().put("model", model).put("taskId", job.getString("taskId"))
                    .put("completed", true).put("consentBeforeTask", true).put("duplicateTriggerSuppressed", true)
                    .put("draftAndAttachmentPreserved", true).put("contextScreenshot", true).put("decisionReceipt", receipt);
            writeReport("jev-magpie-ui.json", new JSONObject().put("passed", true).put("modelResponsesSimulated", true)
                    .put("gatewayOnAndroid", true).put("nativeCodexOnAndroid", true).put("mainConnectionUnchanged", true)
                    .put("cases", new JSONArray().put(result)));
        } finally {
            clearDraft(thread);
        }
    }

    void prepare() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        connect();
        Files.createDirectories(new File(OUT).toPath());
    }

    JSONObject connection() throws Exception {
        return access("manageSystemAi", new JSONObject().put("operation", "readConnection"));
    }

    void assertConnection(JSONObject before) throws Exception {
        JSONObject after = connection();
        assertEquals(before.getString("mode"), after.getString("mode"));
        assertEquals(before.getString("model"), after.getString("model"));
    }

    void enableJev(String model) throws Exception {
        openRoute("/system-ai?section=jev");
        AccessibilityNodeInfo toggle = node("Enable Jev decision model");
        JSONObject request = new JSONObject().put("operation", "readJev");
        JSONObject config = access("manageSystemAi", request);
        assertEquals("Selected Jev decision model", model, config.getString("selected"));
        if (!config.getBoolean("enabled")) click(toggle);
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!config.getBoolean("enabled") && SystemClock.uptimeMillis() < deadline) {
            sleep(200);
            config = access("manageSystemAi", request);
        }
        assertTrue("Jev enabled through Expo settings", config.getBoolean("enabled"));
    }

    void stageDraft(String thread, String text, String name) throws Exception {
        File dir = Files.createTempDirectory(new File("/sdcard/Download").toPath(), "taku-jev-").toFile();
        File file = new File(dir, name);
        try {
            Files.writeString(file.toPath(), "Synthetic unsent attachment. Do not send.");
            open(thread);
            click(control("composer-tools", 10000));
            click(control("attach-file", 10000));
            AccessibilityNodeInfo roots = find("Show roots", 5000);
            if (roots == null) roots = find("Show navigation drawer", 2000);
            if (roots == null) roots = find("显示根目录", 2000);
            click(roots);
            AccessibilityNodeInfo storage = find(command("/system/bin/getprop", "ro.product.model").trim(), 3000);
            if (storage == null) storage = find("Internal storage", 2000);
            if (storage == null) storage = find("内部存储设备", 2000);
            click(storage);
            click("Download");
            click(dir.getName());
            click(name);
            if (findComposer(2500) == null) {
                AccessibilityNodeInfo confirm = find("Open", 3000);
                if (confirm == null) confirm = find("打开", 2000);
                click(confirm);
            }
            composer();
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
        assertNotNull("Fixture attachment is visible", findAttachment(name));
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue("Edit Expo draft", composer().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
        expectDraft(thread, text);
    }

    private AccessibilityNodeInfo findAttachment(String name) throws Exception {
        return findExpo("Remove attachment " + name, "移除附件 " + name, 10000);
    }

    void checkDraft(String thread, String text, String name) throws Exception {
        open(thread);
        expectDraft(thread, text);
        assertNotNull("Unsent attachment is visible", findAttachment(name));
        JSONArray attachments = composerState(thread).getJSONArray("attachments");
        assertEquals("Exactly one unsent attachment", 1, attachments.length());
        assertEquals(name, attachments.getJSONObject(0).getString("name"));
    }

    void clearDraft(String thread) throws Exception {
        JSONObject draft = composerState(thread);
        JSONArray attachments = draft.getJSONArray("attachments");
        for (int i = 0; i < attachments.length(); i++) {
            draft = access("composer/removeAttachment", new JSONObject().put("threadId", thread)
                    .put("expectedRevision", draft.getString("revision"))
                    .put("attachmentId", attachments.getJSONObject(i).getString("id")));
        }
        writeDraft(thread, "");
    }

    void showSyntheticPage() throws Exception {
        command("/system/bin/am", "start", "-W", "-n", "org.agentos.jevfixture/.ChatFixtureActivity");
        node(PAGE);
    }

    JSONObject requestCapture() throws Exception {
        return access("capture/request", new JSONObject().put("manual", false));
    }

    void stopUnfinishedCapture(JSONObject capture) throws Exception {
        JSONObject key = new JSONObject().put("captureId", capture.getString("captureId"));
        JSONObject job = access("capture/dismiss", key).optJSONObject("job");
        if (job == null || !"running".equals(job.getString("state"))) return;
        String thread = job.getString("threadId");
        JSONObject query = new JSONObject().put("op", "state").put("threadId", thread);
        String turn = NativeSocketProbe.exchange(query).optString("activeTurnId");
        if (turn.isEmpty()) return;
        try {
            NativeSocketProbe.rpc("turn/interrupt", new JSONObject().put("threadId", thread).put("turnId", turn));
        } catch (IOException ended) {
            if (turn.equals(NativeSocketProbe.exchange(query).optString("activeTurnId"))) throw ended;
        }
    }

    JSONObject readyReceipt(JSONObject capture, String model) throws Exception {
        JSONObject key = new JSONObject().put("captureId", capture.getString("captureId"));
        JSONObject ready = access("capture/read", key);
        assertEquals("ready", ready.getString("state"));
        assertFalse("No Agent task before consent", ready.has("job"));
        try {
            access("capture/request", new JSONObject().put("manual", false));
            fail("Duplicate capture should be rejected while the suggestion is ready");
        } catch (IOException busy) {
            assertEquals("CAPTURE_BUSY", busy.getMessage());
        }
        JSONObject unchanged = access("capture/read", key);
        assertEquals("ready", unchanged.getString("state"));
        assertFalse("Duplicate request does not create an Agent task", unchanged.has("job"));
        JSONObject receipt = ready.getJSONObject("receipt");
        assertEquals(model, receipt.getString("gateModel"));
        assertTrue("Screenshot was analyzed", receipt.getInt("imageWidth") > 0 && receipt.getInt("imageHeight") > 0);
        return receipt;
    }

    JSONObject acceptAndWait(JSONObject capture, long timeout) throws Exception {
        click(findExpo(ACCEPT, ACCEPT, 10000));
        JSONObject key = new JSONObject().put("captureId", capture.getString("captureId"));
        long deadline = SystemClock.uptimeMillis() + timeout;
        JSONObject state;
        do {
            state = access("capture/read", key);
            JSONObject job = state.optJSONObject("job");
            if (job != null) {
                String status = job.getString("state");
                if (status.equals("completed")) {
                    JSONObject repeated = access("capture/accept", key).getJSONObject("job");
                    assertFalse("Acceptance published a task", job.getString("taskId").isEmpty());
                    assertEquals("Repeat consent returns the same task", job.getString("taskId"), repeated.getString("taskId"));
                    return job;
                }
                assertTrue(job.toString(), status.equals("starting") || status.equals("running"));
            }
            sleep(250);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("Background task did not complete: " + state);
    }

    JSONObject backgroundTurn(JSONObject job, String originalThread, String text, String attachment) throws Exception {
        String background = job.getString("threadId");
        assertFalse("The background task uses its own conversation", background.equals(originalThread));
        JSONObject history = NativeSocketProbe.rpc("thread/read", new JSONObject().put("threadId", background)
                .put("includeTurns", true)).getJSONObject("thread");
        JSONArray turns = history.getJSONArray("turns");
        assertEquals("Exactly one accepted turn", 1, turns.length());
        JSONObject turn = turns.getJSONObject(0);
        assertEquals("completed", turn.getString("status"));
        JSONArray items = turn.getJSONArray("items");
        int images = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!"userMessage".equals(item.getString("type"))) continue;
            JSONArray content = item.getJSONArray("content");
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.getJSONObject(j);
                if ("localImage".equals(part.getString("type")) || "image".equals(part.getString("type"))) images++;
                assertFalse("Unsent draft text is excluded", part.optString("text").contains(text));
                assertFalse("Unsent attachment is excluded", part.optString("text").contains(attachment));
            }
        }
        assertEquals("One screenshot reaches the Agent", 1, images);
        return turn;
    }

    String agentReply(JSONObject turn) throws Exception {
        JSONArray items = turn.getJSONArray("items");
        StringBuilder reply = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if ("agentMessage".equals(item.getString("type"))) reply.append(item.getString("text")).append('\n');
        }
        return reply.toString().trim();
    }

    void writeReport(String name, JSONObject report) throws Exception {
        Files.writeString(new File(OUT, name).toPath(), report.toString(2));
    }
}
