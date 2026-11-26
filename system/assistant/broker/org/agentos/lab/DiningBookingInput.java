package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Authorizes typed native OpenTable checks in the exact user-confirmed booking task. */
final class DiningBookingInput {
    private static final String PACKAGE = "com.opentable";
    private static final Pattern UUID = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern WATCH = Pattern.compile("meal:[a-f0-9]{40}");
    private static final Pattern BOOKING = Pattern.compile("booking:[a-f0-9]{40}");
    private static final Pattern TASK = Pattern.compile("[0-9]{13,17}");
    private static final Set<String> ACTIONS = Set.of("booking-inspect", "booking-verify-date", "booking-begin-submit");
    private static final Set<String> FIELDS = Set.of("op", "action", "watchId", "intentId",
            "executionThreadId", "bookingTaskId", "bookingKey");

    private DiningBookingInput() {}

    static boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    record Request(String thread, String task, String bookingKey) {}

    static Request request(JSONObject input) throws IOException {
        if (input.length() != FIELDS.size() || !"diningPollInput".equals(input.opt("op"))
                || !handles(input.optString("action"))
                || !matches(input, "watchId", WATCH) || !matches(input, "intentId", UUID)
                || !matches(input, "executionThreadId", UUID) || !matches(input, "bookingTaskId", TASK)
                || !matches(input, "bookingKey", BOOKING))
            throw error("BOOKING_RECEIPT_INVALID_ARGUMENT");
        return new Request(input.optString("executionThreadId"), input.optString("bookingTaskId"),
                input.optString("bookingKey"));
    }

    private static boolean matches(JSONObject input, String field, Pattern pattern) {
        return input.opt(field) instanceof String text && pattern.matcher(text).matches();
    }

    static void requireBooking(Request request, JSONObject task, String activeThread) throws IOException {
        if (task == null || !request.thread().equals(activeThread)
                || !request.task().equals(task.opt("id")) || !request.bookingKey().equals(task.opt("diningBookingKey")))
            throw error("BOOKING_EXECUTION_CHANGED");
    }

    static JSONObject control(JSONObject input) throws Exception {
        Request request = request(input);
        String thread = request.thread();
        String turn;
        String workerKey;
        try (CleanupWork.Held held = NativeBridge.lock()) {
            turn = CodexAppServer.activeTurn;
            requireCurrent(request, turn);
            workerKey = thread + "\n" + turn;
            NativeBridge.phoneWorkers.computeIfAbsent(workerKey, ignored -> new HashSet<>()).add(Thread.currentThread());
        }
        try {
            DedicatedScreens registry = DedicatedScreensOnDevice.shared();
            JSONObject descriptor = registry.describe(thread);
            PhoneExecutionScreens.Lease expected = registry.exact(thread,
                    descriptor.getString("screenKey"), descriptor.getString("generation"));
            return registry.withScreen(thread, DedicatedScreens.Use.ACT_HELD, PACKAGE, screen -> {
                if (expected != registry.exact(thread, screen.screenKey, screen.generation))
                    throw error("BOOKING_RECEIPT_TARGET_CHANGED");
                var automation = PhoneTextInput.AUTOMATION_LOCK;
                if (!automation.tryLock(3, TimeUnit.SECONDS)) throw error("BOOKING_RECEIPT_BUSY");
                try {
                    DiningPollInput.requireAutomationAvailable();
                    int task = visibleTask(registry, request, turn, screen, expected);
                    PhoneGestures.prepare(screen);
                    PhoneGestures.requireIdle(screen.displayId);
                    JSONObject callback = new JSONObject(input.toString()).put("requestId", request.task())
                            .put("turnId", turn).put("screenKey", screen.screenKey).put("generation", screen.generation)
                            .put("displayId", screen.displayId).put("userId", screen.userId)
                            .put("taskId", task).put("packageName", PACKAGE).put("legacy", false);
                    JSONObject proof = DiningPollInput.nativeCall(callback);
                    if (proof.optBoolean("cancelled") || visibleTask(registry, request, turn, screen, expected) != task)
                        throw error("BOOKING_RECEIPT_TARGET_CHANGED");
                    return proof;
                } finally {
                    automation.unlock();
                }
            });
        } finally {
            try (CleanupWork.Held held = NativeBridge.lock()) {
                Set<Thread> workers = NativeBridge.phoneWorkers.get(workerKey);
                if (workers != null) {
                    workers.remove(Thread.currentThread());
                    if (workers.isEmpty()) NativeBridge.phoneWorkers.remove(workerKey);
                }
            }
        }
    }

    private static int visibleTask(DedicatedScreens registry, Request request, String turn,
            PhoneExecutionScreens.Screen screen, PhoneExecutionScreens.Lease lease) throws Exception {
        requireCurrent(request, turn);
        registry.validate(lease);
        JSONObject descriptor = registry.describe(request.thread());
        if (screen.legacy || !descriptor.optBoolean("admitted") || descriptor.optBoolean("stopping")
                || !"agent".equals(descriptor.opt("controlOwner"))
                || registry.exact(request.thread(), screen.screenKey, screen.generation) != lease)
            throw error("BOOKING_RECEIPT_TARGET_CHANGED");
        if (PhoneAppCatalog.currentUser() != screen.userId) throw error("ANDROID_USER_CHANGED");
        WorkspacePolicy.requireAppAllowed(PACKAGE);
        int task = DiningPollInput.visibleTask(DiningPollInput.taskInventory(),
                screen.displayId, screen.userId, PACKAGE).getInt("taskId");
        requireCurrent(request, turn);
        return task;
    }

    private static void requireCurrent(Request request, String turn) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            JSONObject task = CodexAppServer.currentRequest;
            String thread = request.thread();
            requireBooking(request, task, CodexAppServer.activeThread);
            if (!turn.equals(CodexAppServer.activeTurn) || !NativeBridge.phoneTurnActive(
                    new JSONObject().put("threadId", thread).put("turnId", turn))
                    || new File(new File(TaskBroker.TASKS, request.task()), "cancel").exists())
                throw error("BOOKING_EXECUTION_CHANGED");
            if (!"full".equals(NativeBridge.phonePermissions.getOrDefault(thread, "full"))
                    || !"full".equals(task.optString("permissionMode", "full")))
                throw error("BOOKING_PERMISSION_DENIED");
        }
    }

    private static IOException error(String code) { return new IOException(code); }
}
