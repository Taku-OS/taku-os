package org.agentos.assistant;

import org.json.JSONObject;

/** UID-scoped in-memory bridge to phone-local Magpie. No provider HTTP or credentials. */
final class JevApiClient {
    JSONObject models() throws Exception {
        return NativeClient.exchange(new JSONObject().put("op", "decision").put("action", "models"));
    }
    JSONObject importLegacy(String key) throws Exception {
        return NativeClient.exchange(new JSONObject().put("op", "decision")
                .put("action", "importLegacy").put("key", key));
    }
    JSONObject decide(String model, Object state, JSONObject questions, boolean authorized) throws Exception {
        if (!authorized) throw new SecurityException("Confirm this page analysis first.");
        return NativeClient.exchange(new JSONObject().put("op", "decision").put("action", "decide")
                .put("model", model).put("state", state).put("questions", questions).put("authorized", true));
    }
    JSONObject testConnection(String model) throws Exception {
        JSONObject questions = new JSONObject().put("reachable", new JSONObject().put("type", "noul")
                .put("instructions", "Does this synthetic connection test mention Jev?"));
        return decide(model, new JSONObject().put("text", "Jev connection test."), questions, true);
    }
}
