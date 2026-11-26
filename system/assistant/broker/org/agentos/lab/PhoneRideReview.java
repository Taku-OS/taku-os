package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Native task/turn/lease-bound evidence and a one-way review handoff, never an order action. */
final class PhoneRideReview {
    static final String PACKAGE = "com.ubercab";
    private static final String SCHEMA = "taku.dining-ride-evidence/1";
    private static final Pattern RIDE_KEY = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private PhoneRideReview() {}

    static JSONObject inspect(JSONObject call, PhoneExecutionScreens.Screen screen) throws Exception {
        var automation = PhoneTextInput.AUTOMATION_LOCK;
        if (!automation.tryLock(3, TimeUnit.SECONDS)) throw new IOException("UBER_INSPECTION_BUSY");
        try {
            DiningPollInput.requireAutomationAvailable();
            JSONObject owner = owner(call, screen);
            int task = visibleTask(screen);
            PhoneGestures.requireIdle(screen.displayId);
            JSONObject probe = probe(screen.displayId);
            owner(call, screen);
            if (task != visibleTask(screen)) throw new IOException("UBER_INSPECTION_TARGET_CHANGED");
            probe.put("observedAt", System.currentTimeMillis()).put("appTaskId", task);
            record(owner, "upcoming", probe);
            return new JSONObject(owner.toString()).put("upcoming", probe);
        } finally {
            automation.unlock();
        }
    }

    private static JSONObject probe(int displayId) throws Exception {
        try {
            String output = BoundedCommand.run(new File(TaskBroker.ROOT, "uber-upcoming-read"), 8000,
                    "/system/bin/env", "CLASSPATH=" + System.getProperty("java.class.path"),
                    "/system/bin/app_process", "/system/bin", PhoneUberUpcomingProbe.class.getName(),
                    Integer.toString(displayId));
            if (output.length() > 16_384) throw new IOException("UBER_INSPECTION_LIMIT");
            List<String> replies = output.lines().filter(line -> line.startsWith(PhoneUberUpcomingProbe.PREFIX))
                    .limit(2).toList();
            if (replies.size() != 1) throw new IOException("UBER_INSPECTION_UNKNOWN");
            return new JSONObject(replies.get(0).substring(PhoneUberUpcomingProbe.PREFIX.length()));
        } catch (InterruptedException stopped) {
            throw stopped;
        } catch (Exception unavailable) {
            return UberUpcomingPolicy.unknown("native-view-unavailable");
        }
    }

    static JSONObject handoff(JSONObject call, JSONObject args, PhoneExecutionScreens.Screen screen) throws Exception {
        if (args.length() != 2 || !(args.opt("title") instanceof String) || !(args.opt("summary") instanceof String))
            throw new IOException("INVALID_REVIEW_PRESENTATION");
        var automation = PhoneTextInput.AUTOMATION_LOCK;
        if (!automation.tryLock(3, TimeUnit.SECONDS)) throw new IOException("UBER_INSPECTION_BUSY");
        try {
            DiningPollInput.requireAutomationAvailable();
            JSONObject owner = owner(call, screen);
            int task = visibleTask(screen);
            PhoneGestures.requireIdle(screen.displayId);
            JSONObject lease = DedicatedScreensOnDevice.shared().handoffForReview(owner.getString("threadId"),
                    screen.screenKey, screen.generation, args.getString("title"), args.getString("summary"));
            // A failed evidence write still leaves control paused and the current phone turn sealed.
            NativeBridge.sealReviewInputs(call);
            if (task != visibleTask(screen)) throw new IOException("RIDE_EVIDENCE_TARGET_CHANGED");
            JSONObject handoff = new JSONObject().put("status", "awaiting_review").put("at", System.currentTimeMillis())
                    .put("controlRevision", lease.getLong("controlRevision")).put("userAccepted", false)
                    .put("title", args.getString("title")).put("summary", args.getString("summary"))
                    .put("packageName", PACKAGE).put("appTaskId", task).put("orderStatus", "not-attested");
            record(owner, "handoff", handoff);
            return new JSONObject(owner.toString()).put("handoff", handoff).put("screen", lease)
                    .put("next", "Agent input is paused. The user may take control of this screen to review the Uber ride.");
        } finally {
            automation.unlock();
        }
    }

    static boolean authorizedRideRequest(JSONObject request) {
        if (request == null || !(request.opt("diningRideKey") instanceof String key)) return false;
        return RIDE_KEY.matcher(key).matches() && key.equals(request.opt("composerRequestId"));
    }

    private static JSONObject owner(JSONObject call, PhoneExecutionScreens.Screen screen) throws Exception {
        JSONObject owner;
        try (CleanupWork.Held held = NativeBridge.lock()) {
            JSONObject request = CodexAppServer.currentRequest;
            String thread = call.getString("threadId"), turn = call.getString("turnId");
            String task = request == null ? "" : request.optString("id");
            if (Thread.currentThread().isInterrupted() || !NativeBridge.phoneTurnActive(call)
                    || !thread.equals(CodexAppServer.activeThread) || !turn.equals(CodexAppServer.activeTurn)
                    || !authorizedRideRequest(request)
                    || !task.matches("[0-9]{13,17}") || !thread.equals(request.optString("conversationId"))
                    || screen.legacy || screen.displayId <= 0) throw new IOException("RIDE_EVIDENCE_TASK_CHANGED");
            File directory = new File(TaskBroker.TASKS, task);
            if (!new File(directory, "ready").isFile() || new File(directory, "done").exists()
                    || new File(directory, "cancel").exists()) throw new IOException("RIDE_EVIDENCE_TASK_CHANGED");
            owner = screen.json().put("schema", SCHEMA).put("taskId", task).put("threadId", thread).put("turnId", turn)
                    .put("diningRideKey", request.getString("diningRideKey"));
        }
        DedicatedScreens registry = DedicatedScreensOnDevice.shared();
        registry.validate(registry.exact(owner.getString("threadId"), screen.screenKey, screen.generation));
        JSONObject lease = registry.describe(owner.getString("threadId"));
        if (!lease.optBoolean("admitted") || lease.optBoolean("stopping")
                || !"agent".equals(lease.optString("controlOwner"))) throw new IOException("SCREEN_AGENT_PAUSED");
        return owner;
    }

    private static int visibleTask(PhoneExecutionScreens.Screen screen) throws Exception {
        return DiningPollInput.visibleTask(DiningPollInput.taskInventory(), screen.displayId, screen.userId, PACKAGE).getInt("taskId");
    }

    private static void record(JSONObject owner, String kind, JSONObject value) throws Exception {
        File target = new File(new File(TaskBroker.TASKS, owner.getString("taskId")), "dining-ride-evidence.json");
        JSONObject saved = new JSONObject(owner.toString());
        if (target.isFile()) {
            if (target.length() <= 0 || target.length() > 16384) throw new IOException("RIDE_EVIDENCE_UNAVAILABLE");
            JSONObject previous = new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            for (String field : List.of("schema", "taskId", "threadId", "turnId", "screenKey", "generation",
                    "diningRideKey", "displayId", "userId"))
                if (!owner.get(field).equals(previous.opt(field))) throw new IOException("RIDE_EVIDENCE_BINDING_CHANGED");
            if (previous.has("upcoming")) saved.put("upcoming", previous.getJSONObject("upcoming"));
            if (previous.has("handoff")) saved.put("handoff", previous.getJSONObject("handoff"));
        }
        saved.put(kind, value).put("updatedAt", System.currentTimeMillis());
        TaskBroker.write(target, saved.toString());
    }
}
