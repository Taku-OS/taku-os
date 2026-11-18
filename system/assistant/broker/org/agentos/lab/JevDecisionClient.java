package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Small adapter for TypeSafe Jev's typed Decisions API.
 *
 * <p>This class contains no provider transport or credential source. The caller uses
 * the phone's Magpie gateway, which owns provider credentials. Calls also
 * require an explicit per-request user authorization flag.
 */
final class JevDecisionClient {
    interface Transport {
        /** Posts to phone-local Magpie; provider keys never enter a decision request. */
        JSONObject post(JSONObject request) throws Exception;
    }

    private final Transport transport;

    JevDecisionClient(Transport transport) {
        if (transport == null) throw new IllegalArgumentException("gateway transport required");
        this.transport = transport;
    }

    JSONObject decide(String model, Object state, JSONObject questions, boolean userAuthorizedThisRequest)
            throws Exception {
        if (!userAuthorizedThisRequest) throw new SecurityException("Jev request requires user authorization");
        if (model == null || model.isBlank() || model.length() > 512)
            throw new IllegalArgumentException("a catalog decision model is required");
        if (!(state instanceof String) && !(state instanceof JSONArray) && !(state instanceof JSONObject))
            throw new IllegalArgumentException("state must be text, an object or image/text parts");
        if (state instanceof String && ((String) state).trim().isEmpty())
            throw new IllegalArgumentException("state is empty");
        if (state instanceof JSONArray && ((JSONArray) state).length() == 0)
            throw new IllegalArgumentException("state is empty");
        if (questions == null || questions.length() == 0)
            throw new IllegalArgumentException("at least one typed question is required");

        JSONObject request = new JSONObject()
                .put("model", model)
                .put("state", state)
                .put("questions", questions);
        JSONObject response = transport.post(request);
        JSONObject answers = response.optJSONObject("answers");
        if (answers == null) throw new IllegalStateException("Jev response has no answers");
        for (Iterator<String> it = questions.keys(); it.hasNext();) {
            String id = it.next();
            JSONObject expected = questions.optJSONObject(id);
            JSONObject answer = answers.optJSONObject(id);
            if (expected == null || answer == null || !expected.optString("type").equals(answer.optString("type")))
                throw new IllegalStateException("Jev response does not match requested question");
            validateAnswer(expected, answer);
        }
        return response;
    }

    private static void validateAnswer(JSONObject question, JSONObject answer) {
        String type = question.optString("type");
        if ("choice".equals(type)) {
            JSONObject criteria = question.optJSONObject("criteria");
            String choice = answer.optString("choice");
            if (criteria == null || !criteria.has(choice)) throw new IllegalStateException("Jev returned an unknown choice");
            requireProbability(answer, "confidence");
            JSONObject probabilities = answer.optJSONObject("probabilities");
            if (probabilities == null) throw new IllegalStateException("Jev choice probabilities are missing");
            for (Iterator<String> it = criteria.keys(); it.hasNext();) {
                String option = it.next();
                if (!probabilities.has(option)) throw new IllegalStateException("Jev omitted a choice probability");
                requireProbability(probabilities, option);
            }
        } else if ("noul".equals(type)) {
            requireProbability(answer, "noul");
        } else if ("score".equals(type) || "box".equals(type)) {
            requireProbability(answer, "confidence");
        } else {
            throw new IllegalArgumentException("unsupported Jev question type: " + type);
        }
    }

    private static void requireProbability(JSONObject object, String field) {
        if (!object.has(field)) throw new IllegalStateException("Jev probability is missing: " + field);
        double value = object.optDouble(field, Double.NaN);
        if (Double.isNaN(value) || value < 0 || value > 1)
            throw new IllegalStateException("Jev probability is out of range: " + field);
    }

}
