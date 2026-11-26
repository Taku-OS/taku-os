package org.agentos.assistant;

import org.json.*;

import java.io.IOException;

/** The durable decision journal. A preflight failure is retryable; a submitted booking never is. */
final class DiningPollConfirmationState {
    private DiningPollConfirmationState() {}

    static boolean submitted(JSONObject watch) {
        JSONObject state = watch.optJSONObject("state");
        if (state != null && state.optJSONObject("booking") != null) return true;
        if (watch.optBoolean("dispatching") || !watch.optString("taskId").isEmpty()) return true;
        JSONArray effects = watch.optJSONArray("outbox");
        if (effects != null)
            for (int i = 0; i < effects.length(); i++)
                if ("START_BOOKING".equals(effects.optJSONObject(i).optString("kind"))) return true;
        return false;
    }

    static String offer(JSONObject watch) {
        JSONObject state = watch.optJSONObject("state"),
                offer = state == null ? null : state.optJSONObject("offer");
        return state != null && "OFFERED".equals(state.optString("phase")) && offer != null
                ? offer.optString("key")
                : "";
    }

    static void validate(JSONObject watch, String key, boolean retry) throws Exception {
        if (submitted(watch)) throw new IOException("BOOKING_ALREADY_STARTED");
        if (!watch.optBoolean("active", true) || key.isEmpty() || !key.equals(offer(watch)))
            throw new IOException("REQUEST_EXPIRED");
        JSONObject current = watch.optJSONObject("confirmation");
        if (current != null && key.equals(current.optString("key"))) {
            if ("checking".equals(current.optString("status")))
                throw new IOException("CONFIRMATION_IN_PROGRESS");
            if ("failed".equals(current.optString("status")) && !retry) {
                throw new IOException("CONFIRMATION_RETRY_REQUIRED");
            }
        }
        if (retry && !refreshableFailure(watch)) throw new IOException("REQUEST_EXPIRED");
    }

    static JSONObject checking(String key, String operation, long now) throws Exception {
        return new JSONObject()
                .put("key", key)
                .put("operationId", operation)
                .put("status", "checking")
                .put("startedAt", now)
                .put("updatedAt", now)
                .put("retryable", false)
                .put("message", "Checking the poll…");
    }

    static JSONObject failed(JSONObject watch, JSONObject current, String code, long now)
            throws Exception {
        boolean submitted = submitted(watch);
        String phase = watch.getJSONObject("state").optString("phase");
        String status =
                submitted
                        ? ("UNKNOWN".equals(phase) || "STARTING".equals(phase)
                                ? "unknown"
                                : "started")
                        : "failed";
        return new JSONObject(current.toString())
                .put("status", status)
                .put("errorCode", code)
                .put("updatedAt", now)
                .put(
                        "retryable",
                        !submitted
                                && watch.optBoolean("active", true)
                                && current.optString("key").equals(offer(watch)))
                .put(
                        "message",
                        "RESTAURANT_STREET_ADDRESS_REQUIRED".equals(code)
                                ? "The restaurant street address could not be verified. No"
                                      + " reservation was started."
                                : submitted
                                        ? ("unknown".equals(status)
                                                ? "The reservation result is unconfirmed. Do not"
                                                      + " submit again."
                                                : "The reservation task has started.")
                                        : ("REQUEST_EXPIRED".equals(code)
                                                ? "The poll or prompt changed. The reservation has"
                                                      + " not started."
                                                : code.contains("BUSY")
                                                        ? "The poll is busy. The reservation has"
                                                              + " not started."
                                                        : "The original group poll could not be"
                                                              + " verified. The reservation has not"
                                                              + " started."));
    }

    static String uiRevision(JSONObject record) {
        return record.optString("operationId")
                + ":"
                + record.optLong("updatedAt")
                + ":"
                + record.optString("status");
    }

    static boolean recover(JSONObject watch, boolean live, long now) throws Exception {
        JSONObject record = watch.optJSONObject("confirmation");
        if (record == null || !"checking".equals(record.optString("status")) || live) return false;
        watch.put("confirmation", failed(watch, record, "CONFIRMATION_INTERRUPTED", now));
        return true;
    }

    static JSONObject finished(JSONObject watch, JSONObject current, long now) throws Exception {
        String phase = watch.getJSONObject("state").optString("phase");
        return new JSONObject(current.toString())
                .put("status", "UNKNOWN".equals(phase) ? "unknown" : "started")
                .put("updatedAt", now)
                .put("retryable", false)
                .put(
                        "message",
                        "UNKNOWN".equals(phase)
                                ? "The reservation result is unconfirmed. Do not submit again."
                                : "The reservation task has started.");
    }

    static boolean mayOffer(JSONObject watch, String expectedKey) {
        return !expectedKey.isEmpty()
                && watch.optBoolean("active", true)
                && !submitted(watch)
                && !blocks(watch)
                && expectedKey.equals(offer(watch));
    }

    static boolean refreshableFailure(JSONObject watch) {
        JSONObject record = watch.optJSONObject("confirmation");
        return record != null
                && "failed".equals(record.optString("status"))
                && !record.optBoolean("dismissed")
                && record.optBoolean("retryable")
                && !offer(watch).isEmpty()
                && record.optString("key").equals(offer(watch))
                && watch.optBoolean("active", true)
                && !submitted(watch);
    }

    static boolean blocks(JSONObject watch) {
        JSONObject current = watch.optJSONObject("confirmation");
        return current != null
                && current.optString("key").equals(offer(watch))
                && ("checking".equals(current.optString("status"))
                        || "failed".equals(current.optString("status")));
    }
}
