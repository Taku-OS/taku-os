package org.agentos.lab;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only poll observation from the native send ledger's execution binding. */
final class DiningPollObservationInput {
    private static final String PACKAGE = "com.whatsapp";
    private static final Pattern UUID = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern TURN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,159}");
    private static final Pattern SCREEN = Pattern.compile("task-[a-f0-9]{20}");

    private DiningPollObservationInput() {}

    record Binding(String intentId, String thread, String turn, String screenKey, String generation,
            int taskId, int displayId, int userId) {
        JSONObject callback(int observedTask, boolean foreground) throws Exception {
            JSONObject result = new JSONObject().put("action", "observe").put("intentId", intentId)
                    .put("executionThreadId", thread).put("turnId", turn).put("screenKey", screenKey)
                    .put("generation", generation).put("taskId", observedTask)
                    .put("displayId", foreground ? 0 : displayId).put("userId", userId)
                    .put("packageName", PACKAGE).put("legacy", false);
            if (foreground) {
                result.put("readOnlyForeground", true).put("originalDisplayId", displayId);
                if (observedTask != taskId) result.put("readOnlyRebound", true).put("originalTaskId", taskId);
            }
            return result;
        }
    }

    static Binding decode(JSONObject input) throws Exception {
        requireFields(input, Set.of("op", "action", "intentId", "executionThreadId", "binding"));
        if (!"diningPollInput".equals(input.opt("op")) || !"observe".equals(input.opt("action")))
            throw error("DINING_POLL_OBSERVATION_INVALID");
        String intent = checkedString(input, "intentId", UUID);
        String thread = checkedString(input, "executionThreadId", UUID);
        JSONObject bound = input.optJSONObject("binding");
        requireFields(bound, Set.of("screenKey", "generation", "turnId", "taskId", "displayId", "userId"));
        return new Binding(intent, thread, checkedString(bound, "turnId", TURN),
                checkedString(bound, "screenKey", SCREEN), checkedString(bound, "generation", UUID),
                checkedInt(bound, "taskId", 0), checkedInt(bound, "displayId", 1), checkedInt(bound, "userId", 0));
    }

    private static void requireFields(JSONObject value, Set<String> fields) throws IOException {
        if (value == null || value.length() != fields.size()) throw error("DINING_POLL_OBSERVATION_INVALID");
        for (String field : fields)
            if (!value.has(field)) throw error("DINING_POLL_OBSERVATION_INVALID");
    }

    private static String checkedString(JSONObject value, String field, Pattern allowed) throws IOException {
        if (!(value.opt(field) instanceof String text) || !allowed.matcher(text).matches())
            throw error("DINING_POLL_OBSERVATION_INVALID");
        return text;
    }

    private static int checkedInt(JSONObject value, String field, int minimum) throws IOException {
        Object raw = value.opt(field);
        long number;
        if (raw instanceof Integer integer) number = integer;
        else if (raw instanceof Long wide) number = wide;
        else throw error("DINING_POLL_OBSERVATION_INVALID");
        if (number < minimum || number > Integer.MAX_VALUE) throw error("DINING_POLL_OBSERVATION_INVALID");
        return (int) number;
    }

    static JSONObject control(JSONObject input) throws Exception {
        Binding binding = decode(input);
        requireIdle(binding);
        Integer foreground = foregroundTask(binding, DiningPollInput.taskInventory());
        if (foreground != null) return read(binding, foreground, true, null, null);

        DedicatedScreens registry = DedicatedScreensOnDevice.shared();
        PhoneExecutionScreens.Lease expected = registry.exact(binding.thread(), binding.screenKey(), binding.generation());
        return registry.withScreen(binding.thread(), DedicatedScreens.Use.ACT_HELD, PACKAGE, screen -> {
            if (registry.exact(binding.thread(), screen.screenKey, screen.generation) != expected
                    || screen.legacy || screen.displayId != binding.displayId() || screen.userId != binding.userId())
                throw error("DINING_POLL_TARGET_CHANGED");
            return read(binding, binding.taskId(), false, registry, expected);
        });
    }

    private static JSONObject read(Binding binding, int taskId, boolean foreground, DedicatedScreens registry,
            PhoneExecutionScreens.Lease lease) throws Exception {
        var automation = PhoneTextInput.AUTOMATION_LOCK;
        if (!automation.tryLock(3, TimeUnit.SECONDS)) throw error("DINING_POLL_OBSERVER_BUSY");
        try {
            DiningPollInput.requireAutomationAvailable();
            verify(binding, taskId, foreground, registry, lease);
            JSONObject proof = DiningPollInput.nativeCall(binding.callback(taskId, foreground));
            verify(binding, taskId, foreground, registry, lease);
            if (proof.optBoolean("cancelled") || !binding.intentId().equals(proof.opt("intentId"))
                    || proof.optInt("observationTaskId", -1) != taskId
                    || proof.optInt("observationDisplayId", -1) != (foreground ? 0 : binding.displayId())
                    || proof.optInt("observationUserId", -1) != binding.userId())
                throw error("DINING_POLL_TARGET_CHANGED");
            return proof;
        } finally {
            automation.unlock();
        }
    }

    private static void verify(Binding binding, int taskId, boolean foreground, DedicatedScreens registry,
            PhoneExecutionScreens.Lease lease) throws Exception {
        requireIdle(binding);
        WorkspacePolicy.requireAppAllowed(PACKAGE);
        if (PhoneAppCatalog.currentUser() != binding.userId()) throw error("ANDROID_USER_CHANGED");
        JSONArray tasks = DiningPollInput.taskInventory();
        if (foreground) {
            Integer live = foregroundTask(binding, tasks);
            if (live == null || live != taskId) throw error("DINING_POLL_TARGET_CHANGED");
            PhoneGestures.requireIdle();
        } else {
            PhoneExecutionScreens.Screen screen = lease.screenHandle();
            registry.validate(lease);
            JSONObject descriptor = registry.describe(binding.thread());
            if (!"agent".equals(descriptor.opt("controlOwner")) || descriptor.optBoolean("stopping")
                    || registry.exact(binding.thread(), binding.screenKey(), binding.generation()) != lease
                    || DiningPollInput.visibleTask(tasks, binding.displayId(), binding.userId()).getInt("taskId") != taskId)
                throw error("DINING_POLL_TARGET_CHANGED");
            PhoneGestures.prepare(screen);
            PhoneGestures.requireIdle(screen.displayId);
        }
    }

    private static void requireIdle(Binding binding) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            if (!"full".equals(NativeBridge.phonePermissions.getOrDefault(binding.thread(), "full")))
                throw error("DINING_POLL_PERMISSION_REQUIRED");
            if (SessionRemovalRuntime.pending(binding.thread()) || CodexAppServer.currentRequest != null
                    || !CodexAppServer.activeThread.isEmpty() || !CodexAppServer.activeTurn.isEmpty()
                    || !NativeBridge.phoneWorkers.isEmpty())
                throw error("DINING_POLL_OBSERVER_BUSY");
            for (JSONObject state : NativeBridge.state.values()) {
                String active = state.optString("activeTurnId");
                if (!active.isEmpty() && !active.equals(state.optString("phoneStoppedTurnId")))
                    throw error("DINING_POLL_OBSERVER_BUSY");
            }
        }
    }

    /**
     * Only the original task can move between displays. A replacement is a read-only candidate
     * after the original disappeared entirely; the Assistant still verifies the exact coded poll.
     */
    static Integer foregroundTask(Binding binding, JSONArray inventory) throws Exception {
        JSONObject original = null;
        JSONObject top = null;
        int visibleWhatsApp = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject task = inventory.getJSONObject(i);
            if (task.getInt("taskId") == binding.taskId()) {
                if (original != null || task.getInt("userId") != binding.userId())
                    throw error("DINING_POLL_TARGET_CHANGED");
                original = task;
            }
            if (task.getInt("userId") != binding.userId() || task.getInt("displayId") != 0
                    || !Boolean.TRUE.equals(task.opt("visible"))) continue;
            if (top == null) top = task;
            if (PACKAGE.equals(task.opt("packageName")) || PACKAGE.equals(task.opt("topPackage"))) visibleWhatsApp++;
        }
        if (original != null && original.getInt("displayId") != 0) return null;
        if (visibleWhatsApp == 0) {
            if (original == null) return null;
            throw error("DINING_POLL_SCREEN_UNAVAILABLE");
        }
        if (visibleWhatsApp != 1 || top == null || original != null && original != top
                || !PACKAGE.equals(top.opt("packageName")) || !PACKAGE.equals(top.opt("topPackage")))
            throw error("DINING_POLL_SCREEN_UNAVAILABLE");
        return top.getInt("taskId");
    }

    private static IOException error(String code) { return new IOException(code); }
}
