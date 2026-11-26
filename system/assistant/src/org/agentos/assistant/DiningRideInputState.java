package org.agentos.assistant;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Durable native input belongs to a ride operation, never to an expiring model tool call. */
final class DiningRideInputState {
    private DiningRideInputState() {}

    static JSONObject begin(JSONObject entry, String thread, String mode, long now)
            throws Exception {
        JSONObject input = entry.optJSONObject("input");
        if (input != null && "editing".equals(input.optString("status"))) {
            require(entry, input.getString("key"));
            if (!thread.equals(input.getString("sourceThreadId")))
                throw new IOException("REQUEST_EXPIRED");
            return input;
        }
        if (!Set.of("offered", "failed", "input_cancelled", "input_required", "ended")
                .contains(entry.optString("phase"))) throw new IOException("REQUEST_EXPIRED");
        JSONObject plan = entry.optJSONObject("ridePlan");
        if (plan == null) plan = DiningRidePlan.draft(entry.getJSONObject("meal"));
        if (input != null && "cancelled".equals(input.optString("status")))
            plan =
                    new JSONObject(plan.toString())
                            .put("pickup", input.optString("pickup"))
                            .put("departureLocal", input.optString("departureLocal"));
        long revision = entry.optLong("inputRevision") + 1;
        input =
                new JSONObject()
                        .put("key", UUID.randomUUID().toString())
                        .put("revision", revision)
                        .put("fingerprint", entry.getString("fingerprint"))
                        .put("sourceThreadId", thread)
                        .put("status", "editing")
                        .put("mode", mode)
                        .put("createdAt", now)
                        .put("pickup", plan.optString("pickup"))
                        .put("departureLocal", plan.optString("departureLocal"));
        entry.put("input", input)
                .put("inputRevision", revision)
                .put("phase", "collecting_input")
                .put("sourceThreadId", thread)
                .put("key", UUID.randomUUID().toString());
        return input;
    }

    static JSONObject require(JSONObject entry, String key) throws Exception {
        JSONObject input = entry.optJSONObject("input");
        if (input == null
                || !input.optString("key").equals(key)
                || !entry.getString("fingerprint").equals(input.optString("fingerprint"))
                || !entry.getString("sourceThreadId").equals(input.optString("sourceThreadId")))
            throw new IOException("REQUEST_EXPIRED");
        return input;
    }

    static void draft(JSONObject entry, String key, String pickup, String departure)
            throws Exception {
        JSONObject input = require(entry, key);
        if (!"collecting_input".equals(entry.optString("phase"))
                || !"editing".equals(input.optString("status")))
            throw new IOException("REQUEST_EXPIRED");
        if (pickup == null
                || departure == null
                || pickup.length() > 240
                || departure.length() > 40
                || pickup.codePoints().anyMatch(Character::isISOControl)
                || departure.codePoints().anyMatch(Character::isISOControl))
            throw new IOException("INVALID_ARGUMENT");
        input.put("pickup", pickup).put("departureLocal", departure);
    }

    /**
     * True only for a new, actual native form submission; repeated delivery retains the same
     * request ID.
     */
    static boolean accept(JSONObject entry, String key, String pickup, String departure, long now)
            throws Exception {
        JSONObject input = require(entry, key);
        if ("submitted".equals(input.optString("status"))) {
            if (!DiningRidePlan.sameInput(entry.getJSONObject("ridePlan"), pickup, departure))
                throw new IOException("REQUEST_ID_REUSED");
            return false;
        }
        draft(entry, key, pickup, departure);
        JSONObject plan =
                DiningRidePlan.validate(entry.getJSONObject("meal"), pickup, departure, now);
        String request =
                UUID.nameUUIDFromBytes(
                                ("dining-ride/2:"
                                                + entry.getString("fingerprint")
                                                + ":"
                                                + input.getLong("revision")
                                                + ":"
                                                + key)
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        input.put("pickup", plan.getString("pickup"))
                .put("departureLocal", plan.getString("departureLocal"))
                .put("status", "submitted")
                .put("submittedAt", now);
        entry.put("ridePlan", plan)
                .put("planValidatedAt", now)
                .put("planInputKey", key)
                .put("requestId", request)
                .put("phase", "input_ready")
                .put("attempt", entry.optLong("attempt") + 1);
        return true;
    }

    static void cancel(JSONObject entry, String key) throws Exception {
        JSONObject input = require(entry, key);
        if (!"editing".equals(input.optString("status"))) throw new IOException("REQUEST_EXPIRED");
        input.put("status", "cancelled");
        entry.put("phase", "input_cancelled").put("key", UUID.randomUUID().toString());
    }
}
