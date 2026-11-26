package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DiningPollIslandTest {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    interface Work {
        void run() throws Exception;
    }

    static void rejects(String code, Work work) throws Exception {
        try {
            work.run();
            throw new AssertionError("Accepted " + code);
        } catch (java.io.IOException error) {
            check(code.equals(error.getMessage()), error.getMessage());
        }
    }

    static JSONObject watch() throws Exception {
        return new JSONObject()
                .put("id", "meal:test")
                .put("active", true)
                .put("outbox", new JSONArray())
                .put(
                        "state",
                        new JSONObject()
                                .put("phase", "OFFERED")
                                .put(
                                        "offer",
                                        new JSONObject()
                                                .put("key", "decision:test")
                                                .put("restaurant", "r1"))
                                .put(
                                        "meal",
                                        new JSONObject()
                                                .put(
                                                        "sourceThreadId",
                                                        "11111111-1111-4111-8111-111111111111")
                                                .put(
                                                        "when",
                                                        "2026-11-29T19:15-05:00[America/New_York]")
                                                .put("people", 3)
                                                .put(
                                                        "restaurants",
                                                        new JSONArray()
                                                                .put(
                                                                        new JSONObject()
                                                                                .put("id", "r1")
                                                                                .put(
                                                                                        "name",
                                                                                        "Harbor"
                                                                                            + " Cafe")))));
    }

    public static void main(String[] args) throws Exception {
        JSONObject w = watch();
        String original = w.toString();
        JSONObject p = DiningPollIslandModel.presentation(w, false),
                payload = p.getJSONObject("payload"),
                expanded = payload.getJSONObject("expanded");
        check(original.equals(w.toString()), "Presentation changed authoritative watch");
        check(
                "assistant".equals(payload.getString("category"))
                        && "assistant".equals(expanded.getString("template")),
                "Existing native assistant template");
        check(
                !expanded.has("content") && !payload.has("background") && !payload.has("position"),
                "No remote view, color or geometry override");
        check(
                expanded.getString("body")
                        .equals("Book Harbor Cafe?\n2026-11-29 19:15 America/New_York · 3 guests"),
                "Exact restaurant, date/time and party");
        check(
                expanded.getJSONObject("assistant")
                        .getJSONArray("suggestions")
                        .toString()
                        .equals("[\"book\",\"decline\"]"),
                "Stable original action order");
        DiningPollIslandModel.validateAction(w, "decision:test", "book", "");
        checks++;
        DiningPollIslandModel.validateAction(w, "decision:test", "decline", "");
        checks++;
        final JSONObject offered = w;
        rejects(
                "REQUEST_EXPIRED",
                () -> DiningPollIslandModel.validateAction(offered, "decision:old", "book", ""));
        w.getJSONObject("state").put("phase", "WATCHING");
        rejects(
                "REQUEST_EXPIRED",
                () -> DiningPollIslandModel.validateAction(offered, "decision:test", "book", ""));
        w = watch();
        final JSONObject working = w;
        w.getJSONObject("state").put("booking", new JSONObject().put("taskId", "123"));
        rejects(
                "BOOKING_ALREADY_STARTED",
                () -> DiningPollIslandModel.validateAction(working, "decision:test", "book", ""));
        w = watch();
        JSONObject record =
                DiningPollConfirmationState.checking("decision:test", "operation:1", 1000);
        w.put("confirmation", DiningPollConfirmationState.failed(w, record, "THREAD_BUSY", 1100));
        final JSONObject failed = w;
        check(
                DiningPollConfirmationState.refreshableFailure(w),
                "Failed decision permits passive refresh");
        w.put("lastObservedAt", 1);
        check(
                DiningPollConfirmationState.refreshableFailure(w),
                "Retry refresh can recover expired observations");
        check(
                !DiningPollConfirmationState.mayOffer(w, "decision:test"),
                "Passive refresh never turns Retry into Book");
        JSONObject failure = DiningPollIslandModel.presentation(w, true);
        String revision = failure.getString("revision");
        check(
                failure.getJSONObject("actions").has("retry")
                        && failure.getJSONObject("actions").has("dismiss"),
                "Known preflight failure has Retry and Dismiss");
        rejects(
                "CONFIRMATION_RETRY_REQUIRED",
                () -> DiningPollIslandModel.validateAction(failed, "decision:test", "book", ""));
        DiningPollIslandModel.validateAction(w, "decision:test", "retry", revision);
        checks++;
        DiningPollIslandModel.validateAction(w, "decision:test", "dismiss", revision);
        checks++;
        rejects(
                "REQUEST_EXPIRED",
                () ->
                        DiningPollIslandModel.validateAction(
                                failed, "decision:test", "retry", "old-revision"));
        w.getJSONObject("confirmation").put("retryable", false);
        check(
                !DiningPollIslandModel.presentation(w, true).getJSONObject("actions").has("retry"),
                "Uncertain/blocked result has no Retry action");
        rejects(
                "CONFIRMATION_RETRY_UNAVAILABLE",
                () ->
                        DiningPollIslandModel.validateAction(
                                failed, "decision:test", "retry", revision));
        check(
                !DiningPollConfirmationState.refreshableFailure(w),
                "Uncertain outcomes cannot reacquire Retry");
        w.getJSONObject("confirmation").put("retryable", true).put("dismissed", true);
        check(
                !DiningPollConfirmationState.refreshableFailure(w),
                "Dismissed Retry stays dismissed");
        rejects(
                "REQUEST_EXPIRED",
                () -> DiningPollConfirmationState.validate(failed, "decision:test", true));
        rejects(
                "REQUEST_EXPIRED",
                () ->
                        DiningPollIslandModel.validateAction(
                                failed, "decision:test", "dismiss", revision));
        JSONObject longName = watch();
        longName.getJSONObject("state")
                .getJSONObject("meal")
                .getJSONArray("restaurants")
                .getJSONObject(0)
                .put("name", "店😀".repeat(60));
        JSONObject longBody =
                DiningPollIslandModel.presentation(longName, false)
                        .getJSONObject("payload")
                        .getJSONObject("expanded");
        String title = longBody.getString("title");
        check(
                title.length() <= 80
                        && !Character.isHighSurrogate(title.charAt(title.length() - 1)),
                "Shared template title bound preserves surrogate pair");
        check(
                longBody.getString("body").contains("店😀".repeat(60)),
                "Full restaurant identity remains in body");
        System.out.println("Dining island: " + checks + " checks passed");
    }
}
