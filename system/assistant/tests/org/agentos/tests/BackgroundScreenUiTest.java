package org.agentos.tests;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.Configurator;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in live model acceptance on a synthetic foreground page. */
public final class BackgroundScreenUiTest extends AgentInterfaceTest {
    private static final String PAGE = "合成约饭 · 后台交互验收";
    private static final String ACCEPT = "Accept screen suggestion and hand to Agent";

    public void testLiveBackgroundAskUser() throws Exception {
        assertEquals("true", getParams().getString("liveAuthorized"));
        assertEquals("AVD-only integration test", "1", command("/system/bin/getprop", "ro.kernel.qemu").trim());
        Configurator.getInstance().setWaitForIdleTimeout(0);
        connect();
        Files.createDirectories(new File(OUT).toPath());
        command("/system/bin/am", "start", "-W", "-n", "org.agentos.screenfixture/.ScreenFixtureActivity");
        node(PAGE);
        capture("00-synthetic-foreground");
        JSONObject capture = access("capture/request", new JSONObject().put("manual", true));
        JSONObject key = new JSONObject().put("captureId", capture.getString("captureId"));
        String thread = "";
        try {
            AccessibilityNodeInfo consent = findExpo(ACCEPT, ACCEPT, 40000);
            assertNotNull("Real image model must generate an offer", consent);
            capture("01-real-image-model-hint");
            JSONObject before = access("capture/read", key);
            assertEquals("ready", before.getString("state"));
            assertFalse("No Agent task before acceptance", before.has("job"));
            assertTrue(before.getJSONObject("receipt").getBoolean("userTriggered"));
            click(consent);
            node("Background task accepted");
            click("Close screen suggestions");
            expectForeground();
            capture("02-background-task-keeps-foreground");

            JSONObject job = waitForTask(key);
            thread = job.getString("threadId");
            String task = job.getString("taskId");
            JSONObject repeated = access("capture/accept", key).getJSONObject("job");
            assertEquals("Acceptance publishes one task", task, repeated.getString("taskId"));
            JSONObject question = waitForQuestion(thread, key);
            JSONObject args = question.getJSONObject("params").getJSONObject("arguments");
            String title = args.getString("question"), answer = args.getJSONArray("options").getString(0);
            node(title);
            node(answer);
            assertNull("Asking does not open chat", findComposer(500));
            capture("03-real-agent-bottom-question");
            String choice = (title + " " + answer).toLowerCase(Locale.ROOT);
            for (String forbidden : new String[]{"付款", "支付", "下单", "发送消息", "confirm booking", "book now", "purchase", "payment", "submit"}) {
                assertFalse("Test never authorizes external commitments: " + forbidden, choice.contains(forbidden));
            }
            click(answer);
            String request = question.getString("key");
            boolean resolved = false, resumed = false;
            long deadline = SystemClock.uptimeMillis() + 30000;
            do {
                JSONObject state = state(thread);
                JSONArray pending = state.getJSONArray("requests");
                boolean same = false;
                for (int i = 0; i < pending.length(); i++) same |= request.equals(pending.getJSONObject(i).optString("key"));
                resolved |= !same;
                resumed |= !same && (state.has("completedTurn") || pending.length() > 0);
                if (resumed) break;
                sleep(200);
            } while (SystemClock.uptimeMillis() < deadline);
            assertTrue("Actual answer resolves the pending question", resolved);
            assertTrue("Real Agent continues after receiving the choice", resumed);
            expectForeground();
            capture("04-answered-without-opening-chat");
            JSONObject report = new JSONObject().put("passed", true).put("target", "emulator")
                    .put("foregroundPageSynthetic", true).put("modelResponsesSimulated", false)
                    .put("taskId", task).put("threadId", thread).put("noTaskBeforeConsent", true)
                    .put("exactlyOneTask", true).put("foregroundPreserved", true).put("mainChatNotOpened", true)
                    .put("realAskUserQuestion", title).put("actualOptionChosen", answer)
                    .put("nativeToolCallResolved", true).put("agentResumedAfterAnswer", true)
                    .put("fullCrossAppCasePassed", false).put("physicalDevicePassed", false);
            Files.writeString(new File(OUT, "background-acceptance.json").toPath(), report.toString(2));
        } finally {
            JSONObject job = access("capture/dismiss", key).optJSONObject("job");
            if (thread.isEmpty() && job != null) thread = job.optString("threadId");
            if (!thread.isEmpty()) {
                String active = state(thread).optString("activeTurnId");
                if (!active.isEmpty()) try {
                    NativeSocketProbe.rpc("turn/interrupt", new JSONObject().put("threadId", thread).put("turnId", active));
                } catch (java.io.IOException ended) {
                    if (!state(thread).optString("activeTurnId").isEmpty()) throw ended;
                }
            }
        }
    }

    private void expectForeground() throws Exception {
        assertNotNull("Original foreground remains visible", find(PAGE, 8000));
        assertNull("No main chat is opened", findComposer(500));
    }

    private JSONObject waitForTask(JSONObject capture) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 45000;
        do {
            JSONObject job = access("capture/read", capture).getJSONObject("job");
            if (!job.getString("taskId").isEmpty()) return job;
            assertEquals(job.toString(), "starting", job.getString("state"));
            sleep(200);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Accepted background task was not published");
    }

    private JSONObject waitForQuestion(String thread, JSONObject capture) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 90000;
        do {
            JSONArray pending = state(thread).optJSONArray("requests");
            if (pending != null) for (int i = 0; i < pending.length(); i++) {
                JSONObject item = pending.getJSONObject(i);
                if ("phone/askUser".equals(item.optString("method"))) return item;
            }
            JSONObject job = access("capture/read", capture).getJSONObject("job");
            assertEquals("Expected Agent to request a necessary choice: " + job, "running", job.getString("state"));
            sleep(300);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Real Agent did not ask a question");
    }

    private JSONObject state(String thread) throws Exception {
        return NativeSocketProbe.exchange(new JSONObject().put("op", "state").put("threadId", thread));
    }
}
