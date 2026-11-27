package org.agentos.assistant;

import static org.agentos.assistant.DiningPollSendState.failure;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native receipt identity based on the unique code in the confirmed poll question. */
final class DiningPollSendEvidence {
    private DiningPollSendEvidence() {}

    /** Identifies the real poll by the frozen visible code, not a recyclable Android node. */
    static JSONObject codedIdentity(
            JSONObject intent, String actualQuestion, JSONArray actualOptions) throws Exception {
        if (!DiningPollSendState.usesQuestionCode(intent)
                || !DiningPollSendState.wireQuestion(intent).equals(actualQuestion)
                || !intent.getJSONArray("options").toString().equals(actualOptions.toString()))
            throw failure("POLL_SEND_RECEIPT_UNATTRIBUTED");
        return new JSONObject()
                .put("kind", "question-code")
                .put("version", intent.getString("identityVersion"))
                .put("intentId", intent.getString("intentId"))
                .put("contentHash", intent.getString("contentHash"))
                .put("pollCode", intent.getString("pollCode"));
    }

    static String codedAnchor(JSONObject intent, JSONObject identity) throws Exception {
        JSONObject expected =
                codedIdentity(
                        intent,
                        DiningPollSendState.wireQuestion(intent),
                        intent.getJSONArray("options"));
        if (!sameMessage(expected, identity)) throw failure("POLL_MESSAGE_CHANGED");
        return "poll-send-code:"
                + DiningPollSendState.hash(
                        intent.getString("identityVersion")
                                + "\n"
                                + intent.getString("intentId")
                                + "\n"
                                + intent.getString("contentHash")
                                + "\n"
                                + intent.getString("pollCode"));
    }

    static boolean sameMessage(JSONObject expected, JSONObject actual) {
        return actual != null
                && "question-code".equals(actual.opt("kind"))
                && java.util.List.of("version", "intentId", "contentHash", "pollCode").stream()
                        .allMatch(
                                field ->
                                        expected.opt(field) instanceof String value
                                                && value.equals(actual.opt(field)));
    }
}
