package org.agentos.lab;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DiningBrokerTest {
    private static final String WHATSAPP = "com.whatsapp";
    private static final String INTENT = "11111111-1111-4111-8111-111111111111";
    private static final String THREAD = "22222222-2222-4222-8222-222222222222";

    @FunctionalInterface interface Operation { void run() throws Exception; }

    private static void check(boolean holds, String detail) {
        if (!holds) throw new AssertionError(detail);
    }

    private static void rejects(String code, Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("expected " + code);
        } catch (IOException expected) {
            check(code.equals(expected.getMessage()), "wrong error: " + expected.getMessage());
        }
    }

    private static JSONObject appTask(int id, int user, int display, String pkg) throws Exception {
        return new JSONObject().put("taskId", id).put("userId", user).put("displayId", display)
                .put("packageName", pkg).put("topPackage", pkg).put("visible", true);
    }

    private static void pollRequestUsesConfirmedTaskOnly() throws Exception {
        JSONObject input = new JSONObject().put("op", "diningPollInput").put("action", "bind")
                .put("intentId", INTENT);
        DiningPollInput.Request request = DiningPollInput.request(input);
        JSONObject confirmedTask = new JSONObject().put("pollSendIntentId", INTENT);
        DiningPollInput.requireIntent(request, confirmedTask, THREAD);
        rejects("DINING_POLL_EXECUTION_OWNER_CHANGED",
                () -> DiningPollInput.requireIntent(request, new JSONObject(), THREAD));
        rejects("DINING_POLL_EXECUTION_OWNER_CHANGED",
                () -> DiningPollInput.requireIntent(request, confirmedTask, ""));
        DiningPollInput.Request explicitlyScoped = DiningPollInput.request(input.put("executionThreadId", THREAD));
        rejects("DINING_POLL_EXECUTION_OWNER_CHANGED",
                () -> DiningPollInput.requireIntent(explicitlyScoped, confirmedTask, INTENT));
        rejects("DINING_POLL_INPUT_INVALID",
                () -> DiningPollInput.request(new JSONObject(input.toString()).put("taskId", 8)));
        rejects("DINING_POLL_INPUT_INVALID",
                () -> DiningPollInput.request(new JSONObject(input.toString()).put("action", "observe")));
    }

    private static void acceptsOnlyExactVisibleWhatsAppTask() throws Exception {
        JSONObject correct = appTask(51, 1, 6, WHATSAPP);
        JSONArray tasks = new JSONArray().put(correct);
        check(DiningPollInput.visibleTask(tasks, 6, 1).getInt("taskId") == 51, "only visible held task qualifies");
        rejects("DINING_POLL_SCREEN_UNAVAILABLE", () -> DiningPollInput.visibleTask(tasks, 0, 1));
        rejects("DINING_POLL_SCREEN_UNAVAILABLE", () -> DiningPollInput.visibleTask(tasks, 6, 7));
        rejects("DINING_POLL_SCREEN_UNAVAILABLE", () -> DiningPollInput.visibleTask(
                new JSONArray().put(appTask(49, 1, 6, "com.example.other")).put(correct), 6, 1));
        rejects("DINING_POLL_SCREEN_UNAVAILABLE", () -> DiningPollInput.visibleTask(
                new JSONArray().put(correct).put(appTask(52, 1, 6, WHATSAPP)), 6, 1));
        rejects("DINING_POLL_SCREEN_UNAVAILABLE", () -> DiningPollInput.visibleTask(
                new JSONArray().put(appTask(51, 1, 6, WHATSAPP).put("visible", false)), 6, 1));
    }

    private static void yelpUriEncodesExactUntrustedFields() throws Exception {
        JSONObject query = new JSONObject().put("query", "Tacos & noodles").put("location", "New York, NY");
        check(PhoneYelpSearchRoute.uri(query).equals(
                "https://www.yelp.com/search?find_desc=Tacos%20%26%20noodles&find_loc=New%20York%2C%20NY"),
                "fixed destination with both inputs encoded");
        rejects("YELP_SEARCH_ARGUMENTS_INVALID",
                () -> PhoneYelpSearchRoute.uri(new JSONObject(query.toString()).put("packageName", "com.other")));
        rejects("YELP_SEARCH_ARGUMENTS_INVALID",
                () -> PhoneYelpSearchRoute.uri(new JSONObject(query.toString()).put("location", "Paris\nFrance")));
    }

    private static void cleanupEvidenceBelongsToTheCurrentOperation() throws Exception {
        Path receipt = Files.createTempFile("dining-broker-cleanup-", ".json");
        try {
            JSONObject wrongToken = new JSONObject().put("operationToken", THREAD).put("cleanupConfirmed", true);
            Files.writeString(receipt, wrongToken.toString());
            check(!DiningPollInput.cleanupConfirmed(receipt.toFile(), INTENT), "another operation cannot release the input fence");

            JSONObject current = new JSONObject().put("operationToken", INTENT).put("cleanupConfirmed", true);
            Files.writeString(receipt, current.toString());
            check(DiningPollInput.cleanupConfirmed(receipt.toFile(), INTENT), "the matching cleanup confirms release");
            check(DiningPollInput.parseReply("Result: Bundle[{result=" + current + "}]")
                    .getString("operationToken").equals(INTENT), "only the CLI envelope is decoded");
            rejects("DINING_POLL_NATIVE_REPLY_INVALID", () -> DiningPollInput.parseReply(
                    "Result: Bundle[{result=" + current + " trailing bytes}]"));
        } finally {
            Files.deleteIfExists(receipt);
        }
    }

    private static void operationFenceNeedsAReleasedLock() throws Exception {
        Path lockPath = Files.createTempFile("dining-broker-fence-", ".lock");
        try {
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                    FileLock lock = channel.lock()) {
                check(!DiningPollInput.operationUnlocked(lockPath.toFile()), "live input keeps the file lock");
            }
            check(DiningPollInput.operationUnlocked(lockPath.toFile()), "released input admits a cleanup probe");
        } finally {
            Files.deleteIfExists(lockPath);
        }
        check(!DiningPollInput.operationUnlocked(lockPath.toFile()), "missing lock file does not prove cleanup");
    }

    private static void passiveObservationNeverAdoptsAnotherVisibleTaskForInput() throws Exception {
        JSONObject binding = new JSONObject().put("screenKey", "task-" + "a".repeat(20))
                .put("generation", INTENT).put("turnId", "original-turn")
                .put("taskId", 51).put("displayId", 6).put("userId", 1);
        JSONObject input = new JSONObject().put("op", "diningPollInput").put("action", "observe")
                .put("intentId", INTENT).put("executionThreadId", THREAD).put("binding", binding);
        DiningPollObservationInput.Binding original = DiningPollObservationInput.decode(input);
        check(DiningPollObservationInput.foregroundTask(original,
                        new JSONArray().put(appTask(51, 1, 0, WHATSAPP))) == 51,
                "original task can be recognized on the foreground display");
        JSONObject foreground = original.callback(51, true);
        check(foreground.getBoolean("readOnlyForeground") && !foreground.has("readOnlyRebound")
                        && foreground.getInt("originalDisplayId") == 6,
                "original task still receives foreground read-only scope");

        JSONArray replacement = new JSONArray().put(appTask(92, 1, 0, WHATSAPP));
        check(DiningPollObservationInput.foregroundTask(original, replacement) == 92,
                "replacement may be proposed only when the old task disappeared");
        JSONObject rebound = original.callback(92, true);
        check(rebound.getBoolean("readOnlyForeground") && rebound.getBoolean("readOnlyRebound")
                        && rebound.getInt("originalTaskId") == 51
                        && "observe".equals(rebound.getString("action")),
                "replacement is strictly passively reidentified by the native poll receipt");
        replacement.put(appTask(51, 1, 6, WHATSAPP).put("visible", false));
        check(DiningPollObservationInput.foregroundTask(original, replacement) == null,
                "even a hidden original dedicated task prevents rebound");
        rejects("DINING_POLL_SCREEN_UNAVAILABLE", () -> DiningPollObservationInput.foregroundTask(original,
                new JSONArray().put(appTask(92, 1, 0, WHATSAPP)).put(appTask(93, 1, 0, WHATSAPP))));
        rejects("DINING_POLL_TARGET_CHANGED", () -> DiningPollObservationInput.foregroundTask(original,
                new JSONArray().put(appTask(51, 5, 0, WHATSAPP)).put(appTask(92, 1, 0, WHATSAPP))));
        rejects("DINING_POLL_OBSERVATION_INVALID", () -> DiningPollObservationInput.decode(
                new JSONObject(input.toString()).put("readOnlyRebound", true)));
        rejects("DINING_POLL_OBSERVATION_INVALID", () -> DiningPollObservationInput.decode(
                new JSONObject(input.toString()).put("binding", new JSONObject(binding.toString()).put("displayId", "6"))));
    }

    private static void bookingEvidenceRequiresTheMatchingConfirmedTask() throws Exception {
        String key = "booking:" + "a".repeat(40), taskId = "1795450000001";
        JSONObject input = new JSONObject().put("op", "diningPollInput").put("action", "booking-begin-submit")
                .put("watchId", "meal:" + "b".repeat(40)).put("intentId", INTENT).put("bookingKey", key)
                .put("bookingTaskId", taskId).put("executionThreadId", THREAD);
        DiningBookingInput.Request request = DiningBookingInput.request(input);
        JSONObject confirmed = new JSONObject().put("id", taskId).put("diningBookingKey", key);
        DiningBookingInput.requireBooking(request, confirmed, THREAD);
        rejects("BOOKING_EXECUTION_CHANGED", () -> DiningBookingInput.requireBooking(request, confirmed, INTENT));
        rejects("BOOKING_EXECUTION_CHANGED", () -> DiningBookingInput.requireBooking(request,
                new JSONObject().put("id", taskId), THREAD));
        rejects("BOOKING_EXECUTION_CHANGED", () -> DiningBookingInput.requireBooking(request,
                new JSONObject(confirmed.toString()).put("id", "1795450000002"), THREAD));
        rejects("BOOKING_RECEIPT_INVALID_ARGUMENT", () -> DiningBookingInput.request(
                new JSONObject(input.toString()).put("operationToken", INTENT)));
        rejects("BOOKING_RECEIPT_INVALID_ARGUMENT", () -> DiningBookingInput.request(
                new JSONObject(input.toString()).put("bookingTaskId", 1795450000001L)));
        JSONArray screen = new JSONArray().put(appTask(81, 1, 6, "com.opentable"));
        check(DiningPollInput.visibleTask(screen, 6, 1, "com.opentable").getInt("taskId") == 81,
                "receipt reads use the real visible OpenTable task");
        rejects("DINING_POLL_SCREEN_UNAVAILABLE",
                () -> DiningPollInput.visibleTask(screen, 6, 1, WHATSAPP));
    }

    private static void uberEvidenceRequiresNativeRideDispatch() throws Exception {
        JSONObject nativeDispatch = new JSONObject().put("diningRideKey", INTENT).put("composerRequestId", INTENT);
        check(PhoneRideReview.authorizedRideRequest(nativeDispatch), "native ride task carries the exact dispatch key");
        check(!PhoneRideReview.authorizedRideRequest(new JSONObject().put("composerRequestId", INTENT)),
                "an ordinary composer request cannot inspect or hand off a ride");
        check(!PhoneRideReview.authorizedRideRequest(new JSONObject(nativeDispatch.toString()).put("composerRequestId", THREAD)),
                "another task cannot reuse the ride authorization");
        check(!PhoneRideReview.authorizedRideRequest(new JSONObject().put("diningRideKey", "invalid")
                .put("composerRequestId", "invalid")), "a malformed dispatch key grants no ride access");
    }

    public static void main(String[] args) throws Exception {
        pollRequestUsesConfirmedTaskOnly();
        acceptsOnlyExactVisibleWhatsAppTask();
        yelpUriEncodesExactUntrustedFields();
        cleanupEvidenceBelongsToTheCurrentOperation();
        operationFenceNeedsAReleasedLock();
        passiveObservationNeverAdoptsAnotherVisibleTaskForInput();
        bookingEvidenceRequiresTheMatchingConfirmedTask();
        uberEvidenceRequiresNativeRideDispatch();
        System.out.println("DiningBrokerTest: Yelp routing, poll ownership and observation, OpenTable binding, Uber dispatch, and cleanup passed");
    }
}
