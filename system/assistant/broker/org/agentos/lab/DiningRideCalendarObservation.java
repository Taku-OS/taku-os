package org.agentos.lab;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Serializes the read-only Calendar detail probe behind the broker's global UiAutomation lock so
 * background ride eligibility checks never contend with active phone automation.
 */
final class DiningRideCalendarObservation {
    static final String ACTION = "ride-calendar-observe";
    static final String PACKAGE = "com.google.android.calendar";
    private static final int MAX_MEAL_CHARS = 4096;

    private DiningRideCalendarObservation() {}

    record Request(int taskId, long eventId, boolean exactEvent, boolean clock24, JSONObject meal) {}

    static boolean handles(String action) {
        return ACTION.equals(action);
    }

    static Request decode(JSONObject input) throws Exception {
        Set<String> fields = Set.of("op", "action", "taskId", "eventId", "exactEvent", "clock24", "meal");
        if (input == null || input.length() != fields.size()) throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        for (String field : fields) if (!input.has(field)) throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        if (!"diningPollInput".equals(input.opt("op")) || !ACTION.equals(input.opt("action")))
            throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        long taskId = positiveLong(input.opt("taskId"));
        if (taskId > Integer.MAX_VALUE) throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        long eventId = positiveLong(input.opt("eventId"));
        if (!(input.opt("exactEvent") instanceof Boolean exactEvent)
                || !(input.opt("clock24") instanceof Boolean clock24)
                || !(input.opt("meal") instanceof JSONObject meal)
                || meal.length() == 0
                || meal.toString().length() > MAX_MEAL_CHARS)
            throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        return new Request((int) taskId, eventId, exactEvent, clock24, new JSONObject(meal.toString()));
    }

    private static long positiveLong(Object raw) throws IOException {
        long value;
        if (raw instanceof Integer integer) value = integer;
        else if (raw instanceof Long wide) value = wide;
        else throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        if (value <= 0) throw error("DINING_RIDE_CALENDAR_OBSERVATION_INVALID");
        return value;
    }

    static int foregroundTask(JSONArray inventory, int userId, int expectedTaskId) throws Exception {
        JSONObject top = null;
        int visibleCalendar = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject task = inventory.getJSONObject(i);
            if (task.getInt("userId") != userId || task.getInt("displayId") != 0
                    || !Boolean.TRUE.equals(task.opt("visible"))) continue;
            if (top == null) top = task;
            if (PACKAGE.equals(task.opt("packageName")) || PACKAGE.equals(task.opt("topPackage"))) visibleCalendar++;
        }
        if (visibleCalendar != 1 || top == null || top.getInt("taskId") != expectedTaskId
                || !PACKAGE.equals(top.opt("packageName")) || !PACKAGE.equals(top.opt("topPackage")))
            throw error("DINING_RIDE_CALENDAR_NOT_VISIBLE");
        return top.getInt("taskId");
    }

    static JSONObject callback(Request request, int userId) throws Exception {
        return new JSONObject()
                .put("action", ACTION)
                .put("displayId", 0)
                .put("userId", userId)
                .put("taskId", request.taskId())
                .put("packageName", PACKAGE)
                .put("readOnlyForeground", true)
                .put("eventId", request.eventId())
                .put("exactEvent", request.exactEvent())
                .put("clock24", request.clock24())
                .put("meal", new JSONObject(request.meal().toString()));
    }

    static JSONObject control(JSONObject input) throws Exception {
        Request request = decode(input);
        requireIdle();
        int userId = PhoneAppCatalog.currentUser();
        foregroundTask(DiningPollInput.taskInventory(), userId, request.taskId());
        var automation = PhoneTextInput.AUTOMATION_LOCK;
        if (!automation.tryLock(3, TimeUnit.SECONDS)) throw error("DINING_RIDE_CALENDAR_BUSY");
        try {
            DiningPollInput.requireAutomationAvailable();
            verify(userId, request.taskId());
            JSONObject result = DiningPollInput.nativeCall(callback(request, userId));
            verify(userId, request.taskId());
            if (result.optBoolean("cancelled")) throw error("DINING_RIDE_CALENDAR_TARGET_CHANGED");
            return result;
        } finally {
            automation.unlock();
        }
    }

    private static void verify(int userId, int expectedTaskId) throws Exception {
        requireIdle();
        if (PhoneAppCatalog.currentUser() != userId) throw error("ANDROID_USER_CHANGED");
        foregroundTask(DiningPollInput.taskInventory(), userId, expectedTaskId);
        PhoneGestures.requireIdle();
    }

    private static void requireIdle() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            if (CodexAppServer.currentRequest != null
                    || !CodexAppServer.activeThread.isEmpty()
                    || !CodexAppServer.activeTurn.isEmpty()
                    || !NativeBridge.phoneWorkers.isEmpty())
                throw error("DINING_RIDE_CALENDAR_BUSY");
            for (JSONObject state : NativeBridge.state.values()) {
                String active = state.optString("activeTurnId");
                if (!active.isEmpty() && !active.equals(state.optString("phoneStoppedTurnId")))
                    throw error("DINING_RIDE_CALENDAR_BUSY");
            }
        }
    }

    private static IOException error(String code) {
        return new IOException(code);
    }
}
