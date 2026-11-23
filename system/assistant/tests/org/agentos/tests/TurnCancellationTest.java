package org.agentos.tests;

import android.system.Os;
import android.system.OsConstants;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.Configurator;
import java.io.*;
import org.json.*;

/** Live model, real Stop button, nested processes, sibling turn and prior-session preservation. */
public final class TurnCancellationTest extends AgentInterfaceTest {
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
                + (nested ? "trap \"\" TERM; /system/bin/sleep 120 & echo $! > " + prefix + "child.pid; wait" : "/system/bin/sleep 120") + "'";
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
        connect();
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
            int targetPid = pid("target"), childPid = pid("child");
            assertTrue(live(targetPid));
            assertTrue(live(childPid));
            open(target);
            AccessibilityNodeInfo stop = findExpo("Stop this turn", "停止本轮", 10000);
            assertNotNull("Actual Expo Stop button required", stop);
            long stoppedAt = System.nanoTime();
            click(stop);
            assertEquals("interrupted", ended(target, turn).getString("status"));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while ((live(targetPid) || live(childPid)) && System.nanoTime() < deadline) sleep(100);
            assertFalse("Target shell must exit", live(targetPid));
            assertFalse("Nested target process must exit", live(childPid));
            assertTrue("Prior turn's persistent session must survive", live(priorPid));
            assertTrue("Different thread's command must survive", live(siblingPid));
            double seconds = (System.nanoTime() - stoppedAt) / 1_000_000_000.0;
            String followup = start(target, "Execute /system/bin/sleep 3, then reply exactly TAKU_CANCEL_RECOVERED. Do not execute other commands.");
            boolean staleRejected = false;
            try { access("interrupt", object("threadId", target).put("turnId", turn)); }
            catch (IOException rejected) { staleRejected = "TURN_CHANGED".equals(rejected.getMessage()); }
            assertTrue("Open Access must reject Stop for the old turn", staleRejected);
            JSONObject reply = ended(target, followup);
            assertEquals("completed", reply.getString("status"));
            JSONArray items = reply.getJSONArray("items");
            boolean recovered = false;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("agentMessage".equals(item.optString("type")) && item.optString("text").contains("TAKU_CANCEL_RECOVERED")) {
                    recovered = true;
                    break;
                }
            }
            assertTrue("An Agent reply confirms the next turn completed", recovered);
            JSONObject evidence = new JSONObject().put("realUpstream", true).put("realStopButton", true)
                    .put("nativeInterrupted", true).put("nestedProcessGone", true).put("previousSessionPreserved", true)
                    .put("siblingThreadPreserved", true).put("followupModelReply", true).put("stopSeconds", seconds)
                    .put("staleStopRejected", staleRejected).put("physicalDeviceVerified", false);
            try (FileOutputStream out = new FileOutputStream(ROOT + "acceptance.json")) { out.write(evidence.toString(2).getBytes("UTF-8")); }
        } finally {
            if (!siblingTurn.isEmpty()) try { rpc("turn/interrupt", object("threadId", sibling).put("turnId", siblingTurn)); } catch (Exception ignored) { }
            cleanupFixture("target"); cleanupFixture("sibling"); cleanupFixture("prior");
        }
    }
}
