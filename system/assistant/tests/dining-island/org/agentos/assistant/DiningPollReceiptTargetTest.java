package org.agentos.assistant;

import static org.agentos.assistant.DiningPollIslandTest.*;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DiningPollReceiptTargetTest {
    private static final String THREAD = "11111111-1111-4111-8111-111111111111";
    private static final String TURN = "turn-1";
    private static final String REPLY = "reply-1";
    private static final String PLACE = "Harbor Cafe";
    private static final String STREET = "23 King Street, New York, NY 10001";

    private static JSONObject source() {
        return new JSONObject()
                .put("kind", "primary")
                .put("turnId", TURN)
                .put("replyId", REPLY)
                .put("revision", 1)
                .put("contractHash", "contract");
    }

    private static JSONObject reply(String address) {
        JSONArray blocks =
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("kind", "collection")
                                        .put(
                                                "items",
                                                new JSONArray()
                                                        .put(
                                                                new JSONObject()
                                                                        .put("id", "place")
                                                                        .put("label", PLACE))))
                        .put(
                                new JSONObject()
                                        .put("kind", "collection")
                                        .put("config", new JSONObject().put("group", "place"))
                                        .put(
                                                "items",
                                                new JSONArray()
                                                        .put(
                                                                new JSONObject()
                                                                        .put("id", "address")
                                                                        .put(
                                                                                "value",
                                                                                new JSONObject()
                                                                                        .put(
                                                                                                "type",
                                                                                                "text")
                                                                                        .put(
                                                                                                "value",
                                                                                                address)))));
        return new JSONObject()
                .put("schema", "taku.main-reply/1")
                .put("id", REPLY)
                .put("revision", 1)
                .put(
                        "provenance",
                        new JSONObject()
                                .put("kind", "primary-assistant-reply")
                                .put("threadId", THREAD)
                                .put("turnId", TURN)
                                .put("contractHash", "contract"))
                .put("lifecycle", new JSONObject().put("phase", "completed"))
                .put(
                        "card",
                        new JSONObject().put("request", new JSONObject().put("blocks", blocks)));
    }

    public static void main(String[] args) throws Exception {
        String frozen =
                DiningPollReceiptTarget.candidateAddress(THREAD, PLACE, source(), reply(STREET));
        check(STREET.equals(frozen), "Candidate's exact original street address is frozen");
        DiningPollReceiptTarget.verifyAddress(frozen, "23 King St., New York, NY 10001-4023");
        checks++;
        rejects(
                "RECEIPT_TARGET_CHANGED",
                () ->
                        DiningPollReceiptTarget.verifyAddress(
                                frozen, "50 King Street, New York, NY 10001"));
        rejects(
                "RECEIPT_TARGET_CHANGED",
                () ->
                        DiningPollReceiptTarget.verifyAddress(
                                frozen, "23 King Street, Rochester, NY 10001"));
        rejects(
                "RESTAURANT_STREET_ADDRESS_REQUIRED",
                () ->
                        DiningPollReceiptTarget.candidateAddress(
                                THREAD, PLACE, source(), reply("New York, NY")));
        rejects(
                "RECEIPT_TARGET_CHANGED",
                () ->
                        DiningPollReceiptTarget.candidateAddress(
                                THREAD, PLACE, source().put("revision", 2), reply(STREET)));
        System.out.println("Dining receipt target: " + checks + " checks passed");
    }
}
