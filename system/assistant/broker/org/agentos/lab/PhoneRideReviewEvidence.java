package org.agentos.lab;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;

/** Keeps observations scoped to the broker-verified task, screen and current turn. */
final class PhoneRideReviewEvidence {
    private PhoneRideReviewEvidence() {}

    static JSONObject merge(JSONObject owner, JSONObject previous, String kind, JSONObject value, long now)
            throws Exception {
        JSONObject saved = new JSONObject(owner.toString());
        if (previous != null) {
            for (String field : List.of("schema", "taskId", "threadId", "screenKey", "generation",
                    "diningRideKey", "displayId", "userId")) {
                if (!owner.get(field).equals(previous.opt(field))) {
                    throw new IOException("RIDE_EVIDENCE_BINDING_CHANGED");
                }
            }
            if (!(previous.opt("turnId") instanceof String previousTurn) || previousTurn.isBlank()) {
                throw new IOException("RIDE_EVIDENCE_BINDING_CHANGED");
            }
            if (owner.getString("turnId").equals(previousTurn)) {
                for (String field : List.of("upcoming", "handoff")) {
                    if (previous.has(field)) saved.put(field, previous.getJSONObject(field));
                }
            }
        }
        return saved.put(kind, value).put("updatedAt", now);
    }
}
