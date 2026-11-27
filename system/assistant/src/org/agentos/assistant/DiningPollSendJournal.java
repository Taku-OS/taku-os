package org.agentos.assistant;

import static org.agentos.assistant.DiningPollSendState.failure;

import android.content.SharedPreferences;

import org.json.JSONObject;

/** Serializes durable poll state changes without holding the journal during Android UI work. */
final class DiningPollSendJournal {
    private DiningPollSendJournal() {}

    static synchronized JSONObject read(SharedPreferences prefs) throws Exception {
        return new JSONObject(prefs.getString("intents", "{}"));
    }

    static synchronized JSONObject require(SharedPreferences prefs, String id) throws Exception {
        JSONObject intent = read(prefs).optJSONObject(id);
        if (intent == null) throw failure("POLL_SEND_INTENT_NOT_FOUND");
        return intent;
    }

    static synchronized JSONObject register(SharedPreferences prefs, JSONObject fresh)
            throws Exception {
        JSONObject all = read(prefs);
        JSONObject previous = all.optJSONObject(fresh.getString("intentId"));
        if (previous != null) {
            DiningPollSendState.sameIntent(previous, fresh);
            return previous;
        }
        JSONObject intent =
                DiningPollSendState.withQuestionCode(fresh, all)
                        .put("registeredAt", System.currentTimeMillis());
        save(prefs, intent);
        return intent;
    }

    static synchronized void save(SharedPreferences prefs, JSONObject intent) throws Exception {
        String previous = prefs.getString("intents", "{}");
        JSONObject all = new JSONObject(previous);
        all.put(intent.getString("intentId"), intent);
        if (!prefs.edit().putString("intents", all.toString()).commit()) {
            // SharedPreferences changes its in-memory values even if persistence fails.
            prefs.edit().putString("intents", previous).commit();
            throw failure("POLL_SEND_STATE_SAVE_FAILED");
        }
    }

    static synchronized JSONObject afterFailure(SharedPreferences prefs, String id, String code)
            throws Exception {
        JSONObject latest = require(prefs, id);
        if (!"sent".equals(latest.optString("status"))) save(prefs, latest.put("lastError", code));
        return latest;
    }
}
