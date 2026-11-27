package org.agentos.lab;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;

public final class PhoneRideReviewEvidenceTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JSONObject firstTurn = new JSONObject().put("schema", "taku.dining-ride-evidence/1")
                .put("taskId", "1795781991000").put("threadId", "thread").put("turnId", "first")
                .put("screenKey", "task-screen").put("generation", "generation").put("diningRideKey", "ride")
                .put("displayId", 17).put("userId", 0);
        JSONObject secondTurn = new JSONObject(firstTurn.toString()).put("turnId", "second");
        JSONObject upcoming = new JSONObject().put("status", "upcoming_empty");
        JSONObject handoff = new JSONObject().put("status", "awaiting_review");

        JSONObject inspected = PhoneRideReviewEvidence.merge(firstTurn, null, "upcoming", upcoming, 1);
        JSONObject sameTurn = PhoneRideReviewEvidence.merge(firstTurn, inspected, "handoff", handoff, 2);
        check(sameTurn.has("upcoming") && sameTurn.has("handoff"), "same-turn evidence can be combined");

        JSONObject continued = PhoneRideReviewEvidence.merge(secondTurn, inspected, "handoff", handoff, 3);
        check("second".equals(continued.getString("turnId")) && continued.getLong("updatedAt") == 3,
                "the handoff is bound to the current turn");
        check(!continued.has("upcoming") && continued.has("handoff"), "prior-turn inspection is not copied");
        JSONObject reinspected = PhoneRideReviewEvidence.merge(secondTurn, sameTurn, "upcoming", upcoming, 4);
        check(!reinspected.has("handoff") && reinspected.has("upcoming"), "prior-turn handoff is not copied");

        for (String field : List.of("schema", "taskId", "threadId", "screenKey", "generation", "diningRideKey",
                "displayId", "userId", "turnId")) {
            JSONObject unbound = new JSONObject(inspected.toString());
            unbound.remove(field);
            try {
                PhoneRideReviewEvidence.merge(secondTurn, unbound, "handoff", handoff, 5);
                throw new AssertionError("evidence without " + field + " was accepted");
            } catch (IOException expected) {
                check("RIDE_EVIDENCE_BINDING_CHANGED".equals(expected.getMessage()), "stable binding is required: " + field);
            }
        }
        System.out.println("PhoneRideReviewEvidenceTest: " + checks + " checks passed");
    }

    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
}
