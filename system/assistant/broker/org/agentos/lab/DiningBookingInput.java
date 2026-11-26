package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Authorizes typed native OpenTable checks in the exact user-confirmed booking task. */
final class DiningBookingInput {
    static final String PACKAGE = "com.opentable";
    private static final Pattern UUID = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern WATCH = Pattern.compile("watch:[a-f0-9]{40}");
    private static final Pattern BOOKING = Pattern.compile("booking:[a-f0-9]{40}");
    private static final Pattern TASK = Pattern.compile("[0-9]{13,17}");
    private static final Set<String> ACTIVE_ACTIONS =
            Set.of("booking-inspect", "booking-verify-date", "booking-begin-submit");
    private static final Set<String> RECOVERY_ACTIONS = Set.of("booking-reconcile", "booking-reconcile-date");
    private static final Set<String> SCOPED_INPUT = Set.of("tap", "touch", "swipe", "type_text");
    private static final Set<String> FIELDS = Set.of("op", "action", "watchId", "intentId",
            "executionThreadId", "bookingTaskId", "bookingKey");

    private DiningBookingInput() {}

    static boolean handles(String action) {
        return ACTIVE_ACTIONS.contains(action) || RECOVERY_ACTIONS.contains(action);
    }

    record Request(String thread, String task, String bookingKey) {}

    static Request request(JSONObject input) throws IOException {
        String action = input.optString("action");
        boolean hasBinding = input.has("binding");
        if (input.length() != FIELDS.size() + (hasBinding ? 1 : 0)
                || hasBinding && (!RECOVERY_ACTIONS.contains(action) || input.optJSONObject("binding") == null)
                || !"diningPollInput".equals(input.opt("op")) || !handles(action)
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
        if (RECOVERY_ACTIONS.contains(input.optString("action"))) {
            return DiningBookingReconciliation.control(input, request);
        }
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
                    JSONObject callback = target(new JSONObject(input.toString()), request, turn, screen, task);
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

    /** Capture the trusted task at dispatch so a late worker cannot fall back to raw input. */
    static Request scope(JSONObject call, JSONObject task, String activeThread) {
        if (task == null || !task.has("diningBookingKey")
                || !activeThread.equals(call.optString("threadId"))) return null;
        return new Request(activeThread, task.optString("id"), task.optString("diningBookingKey"));
    }

    /** A turn may still emit tools after the local task record was unloaded on an error. */
    static final class Turns {
        private record Entry(String turn, Request booking) {}

        private final ConcurrentHashMap<String, Entry> remembered = new ConcurrentHashMap<>();

        void started(String thread, String turn, JSONObject task, String activeThread) {
            if (!thread.equals(activeThread) || task == null || !task.has("diningBookingKey")) return;
            remembered.put(thread, new Entry(turn,
                    new Request(thread, task.optString("id"), task.optString("diningBookingKey"))));
        }

        Request scope(JSONObject call, JSONObject task, String activeThread) {
            Entry entry = remembered.get(call.optString("threadId"));
            if (entry != null && entry.turn().equals(call.optString("turnId"))) return entry.booking();
            return DiningBookingInput.scope(call, task, activeThread);
        }

        void clear() { remembered.clear(); }
    }

    static boolean guards(String tool, JSONObject args) {
        return SCOPED_INPUT.contains(tool)
                && !("touch".equals(tool) && "CANCEL".equals(args.opt("phase")));
    }

    static JSONObject applyInput(Request request, JSONObject call, JSONObject args, String tool,
            PhoneExecutionScreens.Screen screen) throws Exception {
        JSONObject input = safeInput(tool, args);
        String turn = call.getString("turnId");
        var automation = PhoneTextInput.AUTOMATION_LOCK;
        if (!automation.tryLock(3, TimeUnit.SECONDS)) throw error("BOOKING_RECEIPT_BUSY");
        try {
            DiningPollInput.requireAutomationAvailable();
            DedicatedScreens registry = DedicatedScreensOnDevice.shared();
            PhoneExecutionScreens.Lease lease = registry.exact(request.thread(), screen.screenKey, screen.generation);
            int task = visibleTask(registry, request, turn, screen, lease);
            PhoneGestures.requireIdle(screen.displayId);
            JSONObject callback = target(new JSONObject().put("action", "booking-safe-input")
                    .put("input", input).put("bookingTaskId", request.task())
                    .put("executionThreadId", request.thread()).put("bookingKey", request.bookingKey()),
                    request, turn, screen, task);
            JSONObject result = DiningPollInput.nativeCall(callback);
            if (result.optBoolean("cancelled") || !Boolean.TRUE.equals(result.opt("inputApplied"))
                    || visibleTask(registry, request, turn, screen, lease) != task)
                throw error("BOOKING_SAFE_INPUT_UNVERIFIED");
            return screen.json().put("action", tool).put("ok", true).put("inputApplied", true);
        } finally {
            automation.unlock();
        }
    }

    static JSONObject safeInput(String tool, JSONObject args) throws Exception {
        Set<String> fields = switch (tool) {
            case "tap" -> Set.of("x", "y");
            case "type_text" -> Set.of("text");
            case "swipe" -> Set.of("fromX", "fromY", "toX", "toY", "durationMs");
            default -> throw error("BOOKING_UNSAFE_INPUT");
        };
        if (args.length() != fields.size()) throw error("BOOKING_SAFE_INPUT_INVALID");
        JSONObject input = new JSONObject().put("tool", tool);
        for (String field : fields) {
            Object value = args.opt(field);
            if ("text".equals(field)) {
                if (!(value instanceof String text) || text.length() > 4000)
                    throw error("BOOKING_SAFE_INPUT_INVALID");
            } else if (!(value instanceof Integer number) || !validNumber(field, number)) {
                throw error("BOOKING_SAFE_INPUT_INVALID");
            }
            input.put(field, value);
        }
        return input;
    }

    private static boolean validNumber(String field, int number) {
        if ("durationMs".equals(field)) return number >= 100 && number <= 5000;
        return number >= 0 && number < (field.equals("x") || field.endsWith("X") ? 720 : 1280);
    }

    private static JSONObject target(JSONObject payload, Request request, String turn,
            PhoneExecutionScreens.Screen screen, int task) throws Exception {
        return payload.put("requestId", request.task()).put("turnId", turn)
                .put("screenKey", screen.screenKey).put("generation", screen.generation)
                .put("displayId", screen.displayId).put("userId", screen.userId)
                .put("taskId", task).put("packageName", PACKAGE).put("legacy", false);
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

    static void requireCurrent(Request request, String turn) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            JSONObject task = CodexAppServer.currentRequest;
            String thread = request.thread();
            requireBooking(request, task, CodexAppServer.activeThread);
            if (!TASK.matcher(request.task()).matches() || !BOOKING.matcher(request.bookingKey()).matches()
                    || !turn.equals(CodexAppServer.activeTurn) || !NativeBridge.phoneTurnActive(
                            new JSONObject().put("threadId", thread).put("turnId", turn)))
                throw error("BOOKING_EXECUTION_CHANGED");
            File directory = new File(TaskBroker.TASKS, request.task());
            if (!new File(directory, "ready").isFile() || new File(directory, "done").exists()
                    || new File(directory, "cancel").exists())
                throw error("BOOKING_EXECUTION_CHANGED");
            if (!"full".equals(NativeBridge.phonePermissions.getOrDefault(thread, "full"))
                    || !"full".equals(task.optString("permissionMode", "full")))
                throw error("BOOKING_PERMISSION_DENIED");
        }
    }

    private static IOException error(String code) { return new IOException(code); }
}
