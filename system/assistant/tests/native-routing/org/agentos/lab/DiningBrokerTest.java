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

    public static void main(String[] args) throws Exception {
        pollRequestUsesConfirmedTaskOnly();
        acceptsOnlyExactVisibleWhatsAppTask();
        yelpUriEncodesExactUntrustedFields();
        cleanupEvidenceBelongsToTheCurrentOperation();
        operationFenceNeedsAReleasedLock();
        System.out.println("DiningBrokerTest: poll authorization, exact visible task, and Yelp route passed");
    }
}
