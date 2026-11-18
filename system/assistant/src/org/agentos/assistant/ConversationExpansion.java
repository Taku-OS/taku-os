package org.agentos.assistant;

import android.content.SharedPreferences;
import org.json.*;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded local UI preferences, separate from native conversation data. Collapsed by default. */
final class ConversationExpansion {
    private static final String KEY = "conversation-expansion";
    private final SharedPreferences preferences;
    private final String thread;
    private final Set<String> expanded = new LinkedHashSet<>();

    ConversationExpansion(SharedPreferences preferences, String thread) {
        this.preferences = preferences;
        this.thread = thread;
        JSONArray sessions = read();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null || !thread.equals(session.optString("thread"))) continue;
            JSONArray keys = session.optJSONArray("keys");
            if (keys != null) for (int j = 0; j < keys.length(); j++) expanded.add(keys.optString(j));
        }
    }

    boolean contains(String key) { return expanded.contains(key); }

    void toggle(String key) {
        if (!expanded.remove(key)) expanded.add(key);
        while (expanded.size() > 200) expanded.remove(expanded.iterator().next());
        try {
            JSONArray previous = read(), next = new JSONArray();
            // Keep at most 100 recently changed sessions, never store transcript text.
            for (int i = Math.max(0, previous.length() - 99); i < previous.length(); i++) {
                JSONObject session = previous.optJSONObject(i);
                if (session != null && !thread.equals(session.optString("thread"))) next.put(session);
            }
            if (!expanded.isEmpty()) next.put(new JSONObject().put("thread", thread)
                    .put("keys", new JSONArray(expanded)));
            preferences.edit().putString(KEY, next.toString()).apply();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private JSONArray read() {
        try { return new JSONArray(preferences.getString(KEY, "[]")); }
        catch (JSONException malformedPreference) { return new JSONArray(); }
    }
}
