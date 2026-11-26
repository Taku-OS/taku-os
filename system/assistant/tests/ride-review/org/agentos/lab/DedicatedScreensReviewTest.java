package org.agentos.lab;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import static org.agentos.lab.DedicatedScreensTest.*;

/** Real lease state machine with fake Android tasks; no mocked handoff implementation. */
public final class DedicatedScreensReviewTest {
    private static int checks;
    private static void verify(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        Rig rig = new Rig();
        JSONObject opened = rig.request(1, "Prepare dinner ride", "com.ubercab");
        int display = opened.getInt("displayId");
        int task = rig.tasks.open("com.ubercab", display);
        String key = opened.getString("screenKey"), generation = opened.getString("generation");
        JSONObject handed = rig.screens.withScreen(session(1), DedicatedScreens.Use.ACT, "", screen ->
                rig.screens.handoffForReview(session(1), screen.screenKey, screen.generation, "Review ride details", "Check the actual Uber page before ordering."));
        verify("awaiting_review".equals(handed.getString("status")), "Native lease records waiting for review");
        verify("paused".equals(handed.getString("controlOwner")) && handed.getBoolean("resumeRequired"), "Input is sealed before the tool returns");
        verify(!handed.getJSONObject("review").getBoolean("userAccepted"), "An Agent handoff is not a user confirmation");
        verify(!handed.getBoolean("canResume"), "Review requires user takeover before offering generic resume");
        verify(!handed.toString().contains("quoteVerified"), "Presentation does not assert a verified quote");
        verify(handed.getInt("displayId") == display && generation.equals(handed.getString("generation")), "Exact screen generation survives handoff");
        verify(!rig.screens.agentAdmitted(session(1)), "Later Agent turns cannot execute until explicit return");
        rejects("WORKSPACE_REVIEW_REQUIRED", () -> rig.screens.beginReturn(session(1), key, generation));
        verify("awaiting_review".equals(rig.screens.describe(session(1)).getString("status")), "Generic return cannot bypass the real-user review step");
        rejects("SCREEN_AGENT_PAUSED", () -> rig.screens.withScreen(session(1), DedicatedScreens.Use.ACT, "", screen -> new JSONObject()));
        rejects("SCREEN_AGENT_PAUSED", () -> rig.screens.withScreen(session(1), DedicatedScreens.Use.OPEN, "com.ubercab", screen -> new JSONObject()));
        rejects("SCREEN_AGENT_PAUSED", () -> rig.screens.release(session(1), java.util.List.of("com.ubercab")));
        rejects("SCREEN_AGENT_PAUSED", () -> rig.request(1, "Reopen a ride", "com.ubercab"));
        verify(rig.screens.turnEnded(session(1)) == null, "Normal task completion skips destructive turn cleanup");
        rig.clock.advance(24 * 60 * 60_000L);
        verify(rig.tasks.tasks.stream().anyMatch(t -> t.id() == task), "The identical Uber App task and draft are retained after idle timers");
        verify(rig.host.stopped.isEmpty(), "Handoff never stops or reallocates the display");
        verify(rig.screens.list(session(1)).length() == 1, "Retained workspace remains discoverable for real user takeover");
        // Even a late question/timer cannot indirectly give the retained Uber App to another task.
        rig.screens.asked(session(1), "Late question", "Same Uber draft");
        Call waiting = new Call(rig, 3, "Other Uber work", "com.ubercab");
        try {
            awaitWaiting(rig, "com.ubercab", 1);
            rig.clock.advance(DedicatedScreens.HOLD_MS + 1);
            verify(!waiting.done() && session(1).equals(app(rig, "com.ubercab").getString("heldBy")), "Review hold survives the waiting-question App handover timer");
            verify(rig.tasks.tasks.stream().anyMatch(t -> t.id() == task), "A waiting task cannot clear the retained review draft");
        } finally { waiting.worker.interrupt(); waiting.worker.join(2000); }
        rig.screens.beginPause(session(1), key, generation);
        JSONObject controlled = rig.screens.finishControl(session(1), key, generation, "user");
        verify("user_control".equals(controlled.getString("status")), "Existing real-user takeover owns the retained screen");
        verify(!controlled.has("review"), "A real successful takeover clears the pending review marker while keeping user control");
        verify(rig.screens.turnEnded(session(1)) == null, "Stopping the old task after takeover still preserves the draft");
        verify(rig.tasks.tasks.stream().anyMatch(t -> t.id() == task), "User takeover did not replace the App task");
        rig.screens.beginReturn(session(1), key, generation);
        JSONObject returned = rig.screens.finishControl(session(1), key, generation, "agent");
        verify(!returned.has("review"), "After real takeover, explicit return follows the ordinary resume path");

        Rig busy = new Rig(); JSONObject busyLease = busy.request(2, "Ride", "com.ubercab");
        PhoneExecutionScreens.Lease binding = busy.screens.exact(session(2), busyLease.getString("screenKey"), busyLease.getString("generation"));
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> { binding.inputLock().lock(); try { held.countDown(); release.await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } finally { binding.inputLock().unlock(); } });
        worker.start();
        try {
            verify(held.await(2, TimeUnit.SECONDS), "A concurrent step actually holds the input lock");
            rejects("SCREEN_INPUT_NOT_QUIESCENT", () -> busy.screens.handoffForReview(session(2), busyLease.getString("screenKey"), busyLease.getString("generation"), "Review", "Check ride details."));
            verify("agent".equals(busy.screens.describe(session(2)).getString("controlOwner")), "Refused handoff does not publish a false review state");
        } finally { release.countDown(); worker.join(2000); }
        Rig replaced = new Rig(); JSONObject old = replaced.request(4, "Ride", "com.ubercab");
        replaced.host.slots.get(old.getString("screenKey")).put("generation", java.util.UUID.randomUUID().toString());
        rejects("SCREEN_GENERATION_CHANGED", () -> replaced.screens.handoffForReview(session(4), old.getString("screenKey"), old.getString("generation"), "Review", "Check current page."));
        verify(!replaced.screens.describe(session(4)).has("review"), "A replaced host cannot acquire a false review receipt");
        System.out.println("DedicatedScreensReviewTest: " + checks + " checks passed");
    }
}
