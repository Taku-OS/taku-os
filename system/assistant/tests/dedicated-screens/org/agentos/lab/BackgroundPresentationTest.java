package org.agentos.lab;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;
import static org.agentos.lab.DedicatedScreensTest.*;

/** Public viewer acknowledgements and opt-in presentation, without Android or real commands. */
public final class BackgroundPresentationTest {
    private static void badAck(String output) throws Exception {
        rejects("BACKGROUND_VIEWER_LAUNCH_FAILED", () -> BackgroundPresentation.requireOpened(output, session(1)));
    }

    public static void main(String[] args) throws Exception {
        check(BackgroundPresentation.duration(new JSONObject()) == 600, "default is ten minutes");
        for (int n : new int[]{10, 600, 1800})
            check(BackgroundPresentation.duration(new JSONObject().put("durationSeconds", n)) == n, "valid duration");
        for (Object n : new Object[]{9, 1801, -1, 10.5, "600", true, JSONObject.NULL})
            rejects("INVALID_PRESENTATION_DURATION", () -> BackgroundPresentation.duration(new JSONObject().put("durationSeconds", n)));

        String[] command = BackgroundPresentation.previewCommand(session(1), "screen", "generation");
        check(List.of(command).contains("workspaces/preview"), "launches through the shared public method");
        JSONObject lease = new JSONObject(command[command.length - 1]);
        check(lease.length() == 3 && lease.getString("threadId").equals(session(1))
                && lease.getString("screenKey").equals("screen")
                && lease.getString("generation").equals("generation"), "complete exact lease, no arbitrary display");
        BackgroundPresentation.requireOpened("Result: Bundle[{result={\"ok\":true,\"result\":{\"opened\":true,\"threadId\":\"" + session(1) + "\"}}}]", session(1));
        check(true, "parses the content provider shell envelope");
        badAck("");
        badAck("{\"ok\":false,\"error\":\"STALE_LEASE\"}");
        badAck("{\"ok\":true,\"result\":{\"opened\":false}}");
        badAck("{\"ok\":true,\"result\":{\"opened\":true,\"threadId\":\"" + session(2) + "\"}}");

        Rig rig = new Rig();
        JSONObject got = rig.request(1, "viewer failure", "com.example.preview");
        PhoneExecutionScreens.Screen screen = new PhoneExecutionScreens.Screen(got.getString("screenKey"), got.getString("generation"), got.getInt("displayId"), false);
        rejects("BACKGROUND_VIEWER_LAUNCH_FAILED", () -> BackgroundPresentation.show(rig.screens, session(1), screen, 10,
                commandArgs -> { throw new java.util.concurrent.TimeoutException("synthetic viewer timeout"); }));
        check(rig.screens.list(session(1)).getJSONObject(0).getLong("presentationRemainingMs") == 0, "failed viewer launch cancels retention");
        rejects("BACKGROUND_VIEWER_LAUNCH_FAILED", () -> BackgroundPresentation.show(rig.screens, session(1), screen, 10,
                commandArgs -> { throw new InterruptedException("synthetic cancellation"); }));
        check(Thread.interrupted(), "launch cancellation preserves the interrupt");
        check(rig.screens.list(session(1)).getJSONObject(0).getLong("presentationRemainingMs") == 0, "cancelled launch cancels retention");
        Thread release = rig.screens.turnEnded(session(1)); release.join(5000);
        check(!release.isAlive(), "failed launch cannot block turn cleanup");
        System.out.println("BackgroundPresentationTest: " + checks + " checks passed (no device commands)");
    }
}
