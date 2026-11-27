package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads a native OpenTable receipt after a booking task ends without interacting with the app. */
final class DiningBookingReconciliation {
    private static final String PACKAGE = DiningBookingInput.PACKAGE;

    private DiningBookingReconciliation() {}

    record Target(String screenKey, String generation, String turnId, int taskId, int displayId, int userId) {
        boolean foreground() { return displayId == 0; }

        boolean matchesLease(JSONObject descriptor) {
            return descriptor.optBoolean("admitted") && !descriptor.optBoolean("stopping")
                    && screenKey.equals(descriptor.opt("screenKey"))
                    && generation.equals(descriptor.opt("generation"))
                    && displayId == descriptor.optInt("displayId", -1) && userId == descriptor.optInt("userId", -1);
        }

        JSONObject callback(JSONObject input, String task) throws Exception {
            JSONObject result = new JSONObject(input.toString());
            result.remove("binding");
            return result.put("requestId", task).put("turnId", turnId)
                    .put("screenKey", screenKey).put("generation", generation)
                    .put("displayId", displayId).put("userId", userId).put("taskId", taskId)
                    .put("packageName", PACKAGE).put("legacy", false)
                    .put("readOnlyBooking", true).put("readOnlyForeground", foreground());
        }
    }

    static Target original(JSONObject binding) throws IOException {
        if (binding == null) return null;
        if (binding.length() != 6) throw error("BOOKING_RECONCILE_INVALID_BINDING");
        return new Target(text(binding, "screenKey", "task-[a-f0-9]{20}"),
                text(binding, "generation", "[a-f0-9-]{36}"), text(binding, "turnId", "[a-zA-Z0-9_-]{1,160}"),
                number(binding, "taskId", 0), number(binding, "displayId", 1), number(binding, "userId", 0));
    }

    private static String text(JSONObject value, String field, String pattern) throws IOException {
        if (!(value.opt(field) instanceof String text) || !text.matches(pattern))
            throw error("BOOKING_RECONCILE_INVALID_BINDING");
        return text;
    }

    private static int number(JSONObject value, String field, int minimum) throws IOException {
        if (!(value.opt(field) instanceof Integer number) || number < minimum)
            throw error("BOOKING_RECONCILE_INVALID_BINDING");
        return number;
    }

    static Target foreground(JSONArray inventory, int userId) throws Exception {
        JSONObject top = null;
        int visibleOpenTable = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject task = inventory.getJSONObject(i);
            if (task.getInt("displayId") != 0 || task.getInt("userId") != userId
                    || !Boolean.TRUE.equals(task.opt("visible"))) continue;
            if (top == null) top = task;
            if (PACKAGE.equals(task.opt("packageName")) || PACKAGE.equals(task.opt("topPackage")))
                visibleOpenTable++;
        }
        if (top == null || visibleOpenTable != 1 || !PACKAGE.equals(top.opt("packageName"))
                || !PACKAGE.equals(top.opt("topPackage")) || top.getInt("taskId") < 0)
            throw error("BOOKING_RECEIPT_NOT_VISIBLE");
        return new Target("", "", "", top.getInt("taskId"), 0, userId);
    }

    static JSONObject control(JSONObject input, DiningBookingInput.Request request) throws Exception {
        Target original = original(input.optJSONObject("binding"));
        File directory = new File(TaskBroker.TASKS, request.task());
        if (!new File(directory, "done").isFile()) throw error("BOOKING_RECONCILE_TASK_NOT_ENDED");
        JSONObject saved = new JSONObject(TaskBroker.read(new File(directory, "request.json")));
        DiningBookingInput.requireBooking(request, saved, saved.optString("conversationId"));
        requireIdle(request.thread());

        int user = PhoneAppCatalog.currentUser();
        WorkspacePolicy.requireAppAllowed(PACKAGE);
        JSONArray tasks = DiningPollInput.taskInventory();
        DedicatedScreens registry = DedicatedScreensOnDevice.shared();
        if (original != null && original.userId() == user
                && original.matchesLease(registry.describe(request.thread())) && visibleOriginal(original, tasks)) {
            return registry.withScreen(request.thread(), DedicatedScreens.Use.LOOK_HELD, PACKAGE, screen -> {
                if (!original.screenKey().equals(screen.screenKey) || !original.generation().equals(screen.generation)
                        || original.displayId() != screen.displayId || original.userId() != screen.userId)
                    throw error("BOOKING_RECEIPT_TARGET_CHANGED");
                PhoneExecutionScreens.Lease lease = registry.exact(request.thread(), screen.screenKey, screen.generation);
                return read(input, request, directory, original, registry, lease);
            });
        }
        return read(input, request, directory, foreground(tasks, user), null, null);
    }

    private static boolean visibleOriginal(Target original, JSONArray tasks) throws Exception {
        try {
            return DiningPollInput.visibleTask(tasks, original.displayId(), original.userId(), PACKAGE)
                    .getInt("taskId") == original.taskId();
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static JSONObject read(JSONObject input, DiningBookingInput.Request request, File directory, Target target,
            DedicatedScreens registry, PhoneExecutionScreens.Lease lease) throws Exception {
        var automation = PhoneTextInput.AUTOMATION_LOCK;
        if (!automation.tryLock(3, TimeUnit.SECONDS)) throw error("BOOKING_RECEIPT_BUSY");
        try {
            DiningPollInput.requireAutomationAvailable();
            verify(request, directory, target, registry, lease);
            JSONObject result = DiningPollInput.nativeCall(target.callback(input, request.task()));
            verify(request, directory, target, registry, lease);
            if (result.optBoolean("cancelled")) throw error("BOOKING_RECEIPT_TARGET_CHANGED");
            return result;
        } finally {
            automation.unlock();
        }
    }

    private static void verify(DiningBookingInput.Request request, File directory, Target target,
            DedicatedScreens registry, PhoneExecutionScreens.Lease lease) throws Exception {
        requireIdle(request.thread());
        if (!new File(directory, "done").isFile()) throw error("BOOKING_RECONCILE_TASK_NOT_ENDED");
        if (PhoneAppCatalog.currentUser() != target.userId()) throw error("ANDROID_USER_CHANGED");
        WorkspacePolicy.requireAppAllowed(PACKAGE);
        JSONArray tasks = DiningPollInput.taskInventory();
        if (target.foreground()) {
            if (!target.equals(foreground(tasks, target.userId()))) throw error("BOOKING_RECEIPT_TARGET_CHANGED");
        } else {
            registry.validate(lease);
            if (registry.exact(request.thread(), target.screenKey(), target.generation()) != lease
                    || !target.matchesLease(registry.describe(request.thread())) || !visibleOriginal(target, tasks))
                throw error("BOOKING_RECEIPT_TARGET_CHANGED");
        }
        PhoneGestures.requireIdle();
    }

    private static void requireIdle(String thread) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            if (SessionRemovalRuntime.pending(thread) || CodexAppServer.currentRequest != null
                    || !CodexAppServer.activeThread.isEmpty() || !CodexAppServer.activeTurn.isEmpty()
                    || !NativeBridge.phoneWorkers.isEmpty()) throw error("BOOKING_RECEIPT_BUSY");
            for (JSONObject state : NativeBridge.state.values()) {
                String active = state.optString("activeTurnId");
                if (!active.isEmpty() && !active.equals(state.optString("phoneStoppedTurnId")))
                    throw error("BOOKING_RECEIPT_BUSY");
            }
        }
    }

    private static IOException error(String code) { return new IOException(code); }
}
