package org.agentos.lab;

import java.io.IOException;
import java.io.File;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Presents an existing exact lease through the same public entry point as the UI. */
final class BackgroundPresentation {
    private static final long LAUNCH_TIMEOUT_MS = 5_000;
    interface Launch { String run(String[] command) throws Exception; }
    static int duration(JSONObject args) throws IOException {
        Object value = args.opt("durationSeconds");
        if (value == null) return 600;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 10 || number.intValue() > 1800)
            throw new IOException("INVALID_PRESENTATION_DURATION");
        return number.intValue();
    }

    static String[] previewCommand(String thread, String key, String generation) throws Exception {
        JSONObject lease = new JSONObject().put("threadId", thread).put("screenKey", key)
                .put("generation", generation);
        return new String[]{"/system/bin/content", "call", "--uri",
                "content://org.agentos.assistant.open-access", "--method", "workspaces/preview",
                "--arg", lease.toString()};
    }

    static void requireOpened(String output, String thread) throws Exception {
        int start = output.indexOf("{\"ok\":");
        if (start < 0) throw new IOException("BACKGROUND_VIEWER_LAUNCH_FAILED");
        Object decoded = new JSONTokener(output.substring(start)).nextValue();
        if (!(decoded instanceof JSONObject envelope) || !envelope.optBoolean("ok"))
            throw new IOException("BACKGROUND_VIEWER_LAUNCH_FAILED");
        JSONObject result = envelope.optJSONObject("result");
        if (result == null || !result.optBoolean("opened") || !thread.equals(result.optString("threadId")))
            throw new IOException("BACKGROUND_VIEWER_LAUNCH_FAILED");
    }

    static JSONObject show(DedicatedScreens registry, String thread,
            PhoneExecutionScreens.Screen screen, int seconds) throws Exception {
        return show(registry, thread, screen, seconds,
                command -> BoundedCommand.run(new File(TaskBroker.ROOT, "viewer-output"), LAUNCH_TIMEOUT_MS, command));
    }

    static JSONObject show(DedicatedScreens registry, String thread,
            PhoneExecutionScreens.Screen screen, int seconds, Launch launch) throws Exception {
        JSONObject receipt = registry.present(thread, screen.screenKey, screen.generation, seconds);
        try {
            requireOpened(launch.run(previewCommand(thread, screen.screenKey, screen.generation)), thread);
            return receipt.put("viewerLaunched", true).put("requiresObservation", true);
        } catch (Exception failure) {
            registry.cancelPresentation(thread, screen.screenKey, screen.generation);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("BACKGROUND_VIEWER_LAUNCH_FAILED", failure);
        }
    }
}
