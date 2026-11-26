package org.agentos.assistant;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Set;

/** A new receipt fingerprint never authorizes replaying a prior uncertain submission. */
final class DiningRidePreparationHistory {
    private DiningRidePreparationHistory() {}

    static boolean relevant(JSONObject previous, JSONObject current) {
        if (previous.optString("id").equals(current.optString("id"))) return false;
        boolean sameWatch =
                !current.optString("watchId").isBlank()
                        && current.optString("watchId").equals(previous.optString("watchId"));
        boolean sameEvent =
                current.optLong("eventId") > 0
                        && current.optLong("calendarId") > 0
                        && current.optLong("eventId") == previous.optLong("eventId")
                        && current.optLong("calendarId") == previous.optLong("calendarId");
        return (sameWatch || sameEvent)
                && (previous.has("taskId")
                        || previous.has("requestId")
                        || Set.of("submitting", "queued", "unknown")
                                .contains(previous.optString("phase")));
    }

    static String task(JSONObject previous) throws IOException {
        if (previous.optBoolean("rideRequested"))
            throw new IOException("DINING_RIDE_PRIOR_ORDER_REVIEW_REQUIRED");
        if (!Set.of("queued", "running", "ended", "awaiting_review")
                        .contains(previous.optString("phase"))
                || !previous.optString("taskId").matches("[0-9]{1,20}")
                || !previous.optString("requestId")
                        .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw blocked();
        }
        return previous.optString("taskId");
    }

    static JSONObject audit(
            JSONObject previous, JSONObject job, JSONObject state, String terminal, String thread)
            throws Exception {
        String task = task(new JSONObject(previous.toString()).put("phase", "queued"));
        if (!Set.of("completed", "failed", "cancelled", "needs_user").contains(terminal)
                || !task.equals(job.optString("id"))
                || !task.equals(state.optString("id"))
                || !thread.equals(job.optString("conversationId"))
                || !thread.equals(previous.optString("sourceThreadId"))
                || !previous.optString("requestId").equals(job.optString("composerRequestId"))
                || !terminal.equals(state.optString("state"))
                || job.has("kind")
                || job.has("panelUpdate")
                || !previous.optString("prompt").equals(job.optString("prompt"))
                || !job.optString("prompt").equals(state.optString("prompt"))) throw blocked();
        return new JSONObject()
                .put("entryId", previous.optString("id"))
                .put("taskId", task)
                .put("requestId", previous.optString("requestId"))
                .put("sourceThreadId", thread)
                .put("terminalStatus", terminal)
                .put("orderStatus", "not-attested")
                .put("newPreparationRequiresCurrentConsent", true);
    }

    /** A callback persisted taskId before the publication marker: all identities must agree. */
    static JSONObject unpublished(
            JSONObject entry,
            JSONObject receipt,
            JSONObject job,
            JSONObject state,
            boolean ready,
            boolean runtime,
            boolean done,
            boolean cancelled)
            throws Exception {
        String task = entry.optString("taskId"),
                request = entry.optString("requestId"),
                thread = entry.optString("sourceThreadId");
        if (ready
                || runtime
                || done
                || cancelled
                || !task.matches("[0-9]{1,20}")
                || entry.optJSONObject("input") == null
                || !"submitted".equals(entry.getJSONObject("input").optString("status"))
                || !Set.of("deliveryUnknown", "publishing").contains(receipt.optString("state"))
                || !task.equals(receipt.optString("taskId"))
                || !request.equals(receipt.optString("requestId"))
                || !thread.equals(receipt.optString("threadId"))
                || !task.equals(job.optString("id"))
                || !thread.equals(job.optString("conversationId"))
                || !request.equals(job.optString("composerRequestId"))
                || !entry.optString("prompt").equals(job.optString("prompt"))
                || job.has("kind")
                || job.has("panelUpdate")
                || !task.equals(state.optString("id"))
                || !"queued".equals(state.optString("state"))
                || !job.optString("prompt").equals(state.optString("prompt"))) throw blocked();
        return new JSONObject()
                .put("taskId", task)
                .put("requestId", request)
                .put("sourceThreadId", thread)
                .put("publicationStatus", "not-published")
                .put("orderStatus", "not-attested")
                .put("newPreparationRequiresCurrentConsent", true);
    }

    static JSONObject handoffAvailability(JSONObject evidence, org.json.JSONArray screens, long now)
            throws Exception {
        JSONObject result =
                new JSONObject()
                        .put("checkedAt", now)
                        .put("previewAvailable", false)
                        .put("quoteCurrent", false)
                        .put("status", "released");
        for (int i = 0; i < screens.length(); i++) {
            JSONObject screen = screens.getJSONObject(i);
            if (!evidence.optString("threadId").equals(screen.optString("threadId"))
                    || !evidence.optString("screenKey").equals(screen.optString("screenKey"))
                    || !evidence.optString("generation").equals(screen.optString("generation")))
                continue;
            boolean available =
                    screen.optBoolean("admitted")
                            && !screen.optBoolean("stopping")
                            && screen.optInt("displayId", -1) == evidence.optInt("displayId", -2)
                            && screen.optInt("userId", -1) == evidence.optInt("userId", -2)
                            && Set.of("awaiting_review", "paused", "user_control")
                                    .contains(screen.optString("status"))
                            && "com.ubercab".equals(screen.optString("packageName"));
            return result.put("status", available ? "available" : "unavailable")
                    .put("previewAvailable", available);
        }
        return result;
    }

    static IOException blocked() {
        return new IOException("DINING_RIDE_PRIOR_PREPARATION_UNRESOLVED");
    }
}
