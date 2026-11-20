package org.agentos.tests;

import android.os.SystemClock;
import org.json.JSONObject;

/** Opt-in live upstream acceptance with a separate conversation and a synthetic page. */
public final class JevMagpieLiveUiTest extends JevMagpieUiTest {
    public void testLiveJevMagpieChain() throws Exception {
        assertEquals("Live calls must be explicitly authorized", "true", getParams().getString("liveAuthorized"));
        String model = getParams().getString("model"), thread = getParams().getString("thread");
        assertNotNull(model);
        assertNotNull(thread);
        assertTrue("Use a catalogued Jev routing group", model.startsWith("group/"));

        prepare();
        JSONObject connection = connection();
        String pending = "真实 Jev 验收草稿：这段不要发送。";
        String attachment = "keep-live-jev-fixture.txt";
        JSONObject capture = null;
        try {
            stageDraft(thread, pending, attachment);
            enableJev(model);
            checkDraft(thread, pending, attachment);
            showSyntheticPage();
            capture("live-page-before-trigger");
            long started = SystemClock.elapsedRealtime();
            capture = requestCapture();
            android.view.accessibility.AccessibilityNodeInfo hint = findExpo(ACCEPT, ACCEPT, 35000);
            if (hint == null) capture("live-no-action");
            assertNotNull("Real Jev must offer a suggestion", hint);
            long decisionMs = SystemClock.elapsedRealtime() - started;
            capture("live-hint");
            JSONObject receipt = readyReceipt(capture, model);
            assertEquals("magpie", receipt.getString("gateway"));
            long accepted = SystemClock.elapsedRealtime();
            JSONObject job = acceptAndWait(capture, 180000);
            long agentMs = SystemClock.elapsedRealtime() - accepted;
            JSONObject turn = backgroundTurn(job, thread, pending, attachment);
            String reply = agentReply(turn);
            assertTrue("Actual Agent reply must be nonempty", reply.length() > 20);
            assertTrue("Reply must use the captured dining context", reply.contains("西湖") || reply.contains("周六") || reply.contains("18:00"));
            checkDraft(thread, pending, attachment);
            assertConnection(connection);
            capture("live-agent-completed");
            JSONObject report = new JSONObject().put("passed", true).put("modelResponsesSimulated", false)
                    .put("pageSynthetic", true).put("gatewayOnAndroid", true).put("nativeCodexOnAndroid", true)
                    .put("decisionModel", model).put("threadId", thread).put("taskId", job.getString("taskId"))
                    .put("backgroundThreadId", job.getString("threadId")).put("turnId", turn.getString("id"))
                    .put("mainConnectionMode", connection.getString("mode")).put("mainConnectionUnchanged", true)
                    .put("decisionToHintMs", decisionMs).put("agentAfterConsentMs", agentMs)
                    .put("noTaskBeforeConsent", true).put("duplicateTriggerSuppressed", true).put("exactlyOneAgentTask", true)
                    .put("draftAndAttachmentPreserved", true).put("originalScreenshotPreserved", true)
                    .put("decisionReceipt", receipt).put("agentReply", reply);
            writeReport("live-acceptance.json", report);
        } finally {
            try {
                if (capture != null) stopUnfinishedCapture(capture);
            } finally {
                clearDraft(thread);
            }
        }
    }
}
