package org.agentos.tests;

import android.system.Os;
import android.system.OsConstants;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.*;
import org.json.*;

/** Live model, real Stop button, nested processes, sibling turn and prior-session preservation. */
public final class TurnCancellationTest extends UiAutomatorTestCase {
    private static final String ROOT = "/data/local/tmp/taku-turn-cancellation/";
    private final String prefix = ROOT + System.currentTimeMillis() + "-";
    private JSONObject object(String key, Object value) throws Exception { return new JSONObject().put(key, value); }
    private JSONObject rpc(String method, JSONObject params) throws Exception { return NativeSocketProbe.rpc(method, params); }
    private String read(String path) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            return out.toString("UTF-8");
        }
    }
    private String thread() throws Exception {
        return rpc("thread/start", object("cwd", "/data/local/agentos/work")
                .put("approvalPolicy", "never").put("sandbox", "danger-full-access")
                .put("developerInstructions", "Cancellation acceptance. Follow exact harmless commands. Never inspect unrelated files or credentials."))
                .getJSONObject("thread").getString("id");
    }
    private String start(String thread, String prompt) throws Exception {
        return rpc("turn/start", object("threadId", thread).put("input", new JSONArray()
                .put(object("type", "text").put("text", prompt)))).getJSONObject("turn").getString("id");
    }
    private JSONObject ended(String thread, String turn) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JSONObject state = NativeSocketProbe.exchange(object("op", "state").put("threadId", thread));
            JSONObject finished = state.optJSONObject("completedTurn");
            if (finished == null || !finished.optString("id").equals(turn)) { sleep(200); continue; }
            JSONObject current = rpc("thread/read", object("threadId", thread).put("includeTurns", true)).getJSONObject("thread");
            JSONArray turns = current.getJSONArray("turns");
            for (int i = 0; i < turns.length(); i++) {
                JSONObject value = turns.getJSONObject(i);
                if (value.optString("id").equals(turn)) {
                    assertEquals("Persisted history must agree with native terminal event", finished.getString("status"), value.getString("status"));
                    return value;
                }
            }
            sleep(200);
        }
        throw new IOException("Native turn did not reach a terminal state");
    }
    private int pid(String name) throws Exception {
        File file = new File(prefix + name + ".pid");
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!file.exists() && System.nanoTime() < deadline) sleep(200);
        assertTrue("Actual command must start", file.isFile());
        return Integer.parseInt(read(file.getPath()).trim());
    }
    private boolean live(int pid) {
        try { String stat = read("/proc/" + pid + "/stat"); return stat.charAt(stat.lastIndexOf(')') + 2) != 'Z'; }
        catch (Exception exited) { return false; }
    }
    private String command(String name, boolean nested) {
        return "/system/bin/sh -c 'echo $$ > " + prefix + name + ".pid; "
                + (nested ? "trap \"\" TERM; /system/bin/sleep 120 & wait" : "/system/bin/sleep 120") + "'";
    }
    private void cleanupFixture(String name) {
        // Only the process group recorded by this test command, verified through its environment marker path.
        try {
            int pid = Integer.parseInt(read(prefix + name + ".pid").trim());
            String stat = read("/proc/" + pid + "/stat");
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" +");
            String cmdline = read("/proc/" + pid + "/cmdline");
            if (cmdline.contains(prefix + name + ".pid")) Os.kill(-Integer.parseInt(fields[2]), OsConstants.SIGKILL);
        } catch (Exception alreadyExited) { }
    }
    public void testStopOwnsOnlyCurrentTurn() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        new File(ROOT).mkdirs();
        String target = thread(), sibling = thread();
        String siblingTurn = "";
        try {
            String prior = start(target, "Use exec_command once with yield_time_ms=1000 to run exactly: " + command("prior", false)
                    + ". The process may remain in its session. Do not poll it. Reply only READY immediately after the tool returns.");
            int priorPid = pid("prior");
            assertEquals("completed", ended(target, prior).getString("status"));
            assertTrue("Prior session must still be running before the next turn", live(priorPid));
            siblingTurn = start(sibling, "Use exec_command to run exactly: " + command("sibling", false) + ". Wait for completion; no other commands.");
            int siblingPid = pid("sibling");
            String turn = start(target, "Use exec_command to run exactly: " + command("target", true) + ". Wait for completion; no other commands.");
            int targetPid = pid("target");
            assertTrue(live(targetPid));
            assertEquals(0, new ProcessBuilder("/system/bin/am", "start", "-n", "org.agentos.assistant/.PanelActivity", "--es", "conversationId", target).start().waitFor());
            UiObject stop = new UiObject(new UiSelector().packageName("org.agentos.assistant").description("停止当前执行"));
            assertTrue("Actual Stop button required", stop.waitForExists(10000));
            long stoppedAt = System.nanoTime(); assertTrue(stop.click());
            assertEquals("interrupted", ended(target, turn).getString("status"));
            JSONObject state = NativeSocketProbe.exchange(object("op", "state").put("threadId", target));
            JSONObject cleanup = state.getJSONObject("cancellationCleanup");
            assertTrue("Linux child cleanup must be independently confirmed", cleanup.getBoolean("complete"));
            assertTrue(cleanup.getInt("trackedProcesses") >= 2);
            assertFalse("Nested target shell must be gone", live(targetPid));
            assertTrue("Prior turn's persistent session must survive", live(priorPid));
            assertTrue("Different thread's command must survive", live(siblingPid));
            double seconds = (System.nanoTime() - stoppedAt) / 1_000_000_000.0;
            String followup = start(target, "Execute /system/bin/sleep 3, then reply exactly TAKU_CANCEL_RECOVERED. Do not execute other commands.");
            boolean staleRejected = false;
            try { rpc("turn/interrupt", object("threadId", target).put("turnId", turn)); }
            catch (IOException rejected) { staleRejected = rejected.getMessage().contains("目标回合已结束"); }
            assertTrue("Late Stop for the old turn must not interrupt the next turn", staleRejected);
            JSONObject reply = ended(target, followup);
            assertEquals("completed", reply.getString("status"));
            assertTrue(reply.toString().contains("TAKU_CANCEL_RECOVERED"));
            JSONObject evidence = new JSONObject().put("realUpstream", true).put("realStopButton", true)
                    .put("nativeInterrupted", true).put("nestedProcessGone", true).put("previousSessionPreserved", true)
                    .put("siblingThreadPreserved", true).put("followupModelReply", true).put("stopSeconds", seconds)
                    .put("staleStopRejected", staleRejected)
                    .put("cleanup", cleanup).put("physicalDeviceVerified", false);
            try (FileOutputStream out = new FileOutputStream(ROOT + "acceptance.json")) { out.write(evidence.toString(2).getBytes("UTF-8")); }
        } finally {
            if (!siblingTurn.isEmpty()) try { rpc("turn/interrupt", object("threadId", sibling).put("turnId", siblingTurn)); } catch (Exception ignored) { }
            cleanupFixture("target"); cleanupFixture("sibling"); cleanupFixture("prior");
        }
    }
}
