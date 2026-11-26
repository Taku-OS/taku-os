package org.agentos.lab;

import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordered raw projection. Display repair and pacing never enter this state. */
final class AgentSessionReducer {
    /**
     * A model's public reasoning summary streams like its answer. Raw reasoning content
     * ({@code item/reasoning/textDelta}) is never relayed.
     */
    static final String REASONING_SUMMARY_DELTA = "item/reasoning/summaryTextDelta";
    private static final int MAX_SUMMARY_PARTS = 64;

    /**
     * A history read replaces completed turns, and the app-server keeps no record of when an item
     * ran. Keep the duration this relay observed for each reasoning item that is still known.
     */
    static void retainReasoningTiming(JSONObject snapshot, JSONObject known) throws Exception {
        JSONArray before = known == null ? null : known.optJSONArray("turns"), turns = snapshot.optJSONArray("turns");
        if (before == null || turns == null) return;
        java.util.HashMap<String, Object> durations = new java.util.HashMap<>();
        for (int i = 0; i < before.length(); i++) {
            JSONObject turn = before.optJSONObject(i);
            JSONArray items = turn == null ? null : turn.optJSONArray("items");
            if (items != null) for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item != null && "reasoning".equals(item.optString("type")) && item.has("durationMs"))
                    durations.put(turn.optString("id") + "/" + item.optString("id"), item.get("durationMs"));
            }
        }
        if (durations.isEmpty()) return;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i);
            JSONArray items = turn == null ? null : turn.optJSONArray("items");
            if (items != null) for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null || !"reasoning".equals(item.optString("type")) || item.has("durationMs")) continue;
                Object duration = durations.get(turn.optString("id") + "/" + item.optString("id"));
                if (duration != null) item.put("durationMs", duration);
            }
        }
    }

    static void retainActiveTurns(JSONObject snapshot, JSONObject live) throws Exception {
        if (live == null || live.optJSONArray("turns") == null) return;
        JSONArray next = snapshot.optJSONArray("turns");
        if (next == null) next = new JSONArray();
        else next = new JSONArray(next.toString());
        JSONArray previous = live.getJSONArray("turns");
        for (int i = 0; i < previous.length(); i++) {
            JSONObject turn = previous.getJSONObject(i);
            if (!"inProgress".equals(turn.optString("status"))) continue;
            int at = -1;
            for (int j = 0; j < next.length(); j++)
                if (turn.optString("id").equals(next.getJSONObject(j).optString("id"))) { at = j; break; }
            // A cold app-server history can rename live items and omit the current prefix.
            // Replace that entire active turn with the relay's authoritative live turn.
            if (at < 0) next.put(turn); else next.put(at, turn);
        }
        snapshot.put("turns", next);
    }

    static JSONObject turn(JSONObject thread, String id) throws Exception {
        JSONArray turns = thread.optJSONArray("turns");
        if (turns == null) { turns = new JSONArray(); thread.put("turns", turns); }
        for (int i = 0; i < turns.length(); i++)
            if (id.equals(turns.getJSONObject(i).optString("id"))) return turns.getJSONObject(i);
        JSONObject turn = new JSONObject().put("id", id).put("status", "inProgress").put("items", new JSONArray());
        turns.put(turn);
        return turn;
    }

    static void replaceItem(JSONArray items, JSONObject source, boolean completed, boolean started) throws Exception {
        int at = -1;
        for (int i = 0; i < items.length(); i++)
            if (source.getString("id").equals(items.getJSONObject(i).optString("id"))) { at = i; break; }
        JSONObject item = new JSONObject(source.toString());
        if (at >= 0) {
            JSONObject previous = items.getJSONObject(at);
            if (started) {
                // Recovery can deliver a delta before metadata. A late start must not erase raw text.
                if (previous.optBoolean("_streamCompleted")) return;
                String text = previous.optString("text");
                if (!text.isEmpty()) item.put("text", text);
                JSONArray summary = previous.optJSONArray("summary");
                if (summary != null && summary.length() > 0) item.put("summary", summary);
            }
            // Timing is this relay's own observation; no later copy of the item carries it.
            if ("reasoning".equals(item.optString("type")))
                for (String key : new String[] {"startedAtMs", "durationMs"})
                    if (previous.has(key) && !item.has(key)) item.put(key, previous.get(key));
        }
        item.put("_streamCompleted", completed);
        if (at < 0) items.put(item); else items.put(at, item);
    }

    private static JSONObject item(JSONArray items, String id) throws Exception {
        for (int i = 0; i < items.length(); i++)
            if (id.equals(items.getJSONObject(i).optString("id"))) return items.getJSONObject(i);
        return null;
    }

    /** The app-server says when an item ran only on its start and completion notifications. */
    private static void timeReasoning(JSONObject item, JSONObject params, boolean completed) throws Exception {
        if (item == null) return;
        if (!completed) {
            if (!item.has("startedAtMs") && params.optLong("startedAtMs") > 0) item.put("startedAtMs", params.getLong("startedAtMs"));
            return;
        }
        long started = item.optLong("startedAtMs"), ended = params.optLong("completedAtMs");
        if (started > 0 && ended >= started) item.put("durationMs", ended - started);
    }

    /** Appends to one summary part of a live reasoning item. False when nothing changed. */
    static boolean appendSummary(JSONObject item, JSONObject params) throws Exception {
        if (item.optBoolean("_streamCompleted") || !"reasoning".equals(item.optString("type"))) return false;
        int index = params.optInt("summaryIndex", 0);
        String delta = params.optString("delta");
        if (delta.isEmpty() || index < 0 || index >= MAX_SUMMARY_PARTS) return false;
        JSONArray summary = item.optJSONArray("summary");
        if (summary == null) { summary = new JSONArray(); item.put("summary", summary); }
        while (summary.length() <= index) summary.put("");
        summary.put(index, summary.optString(index) + delta);
        return true;
    }

    static boolean apply(JSONObject thread, JSONObject event) throws Exception {
        String method = event.optString("method");
        JSONObject params = event.getJSONObject("params");
        if ("thread/name/updated".equals(method))
            thread.put("name", params.optString("threadName", params.optString("name")));
        else if ("thread/status/changed".equals(method)) thread.put("status", params.get("status"));
        else {
            if (!method.equals("turn/started") && !method.equals("turn/completed")
                    && !method.equals("item/started") && !method.equals("item/completed")
                    && !method.equals("item/agentMessage/delta") && !method.equals(REASONING_SUMMARY_DELTA)) return false;
            String turnId = params.has("turn") ? params.getJSONObject("turn").getString("id") : params.optString("turnId");
            if (turnId.isEmpty()) return false;
            return applyTurn(turn(thread, turnId), event);
        }
        return true;
    }

    static boolean applyTurn(JSONObject target, JSONObject event) throws Exception {
        String method = event.optString("method");
        JSONObject params = event.getJSONObject("params");
        if ("turn/started".equals(method) || "turn/completed".equals(method)) {
            boolean completed = "turn/completed".equals(method);
            JSONObject source = params.getJSONObject("turn");
            for (Iterator<String> keys = source.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!key.equals("items")) target.put(key, source.get(key));
            }
            JSONArray received = source.optJSONArray("items"), retained = target.getJSONArray("items");
            // Terminal events can contain only the last message. Keep earlier tools/items.
            if (received != null) for (int i = 0; i < received.length(); i++)
                replaceItem(retained, received.getJSONObject(i), completed, !completed);
            if (completed) for (int i = 0; i < retained.length(); i++)
                retained.getJSONObject(i).put("_streamCompleted", true);
        } else if (method.equals("item/started") || method.equals("item/completed")
                || method.equals("item/agentMessage/delta")) {
            JSONArray items = target.getJSONArray("items");
            JSONObject source = params.optJSONObject("item");
            if (source != null) {
                boolean completed = method.equals("item/completed");
                replaceItem(items, source, completed, method.equals("item/started"));
                if ("reasoning".equals(source.optString("type"))) timeReasoning(item(items, source.getString("id")), params, completed);
            } else {
                String itemId = params.getString("itemId");
                JSONObject item = item(items, itemId);
                if (item == null) {
                    item = new JSONObject().put("id", itemId).put("type", "agentMessage").put("text", "")
                            .put("_streamCompleted", false);
                    items.put(item);
                }
                if (item.optBoolean("_streamCompleted") || !"agentMessage".equals(item.optString("type"))) return false;
                item.put("text", item.optString("text") + params.optString("delta"));
            }
        } else if (method.equals(REASONING_SUMMARY_DELTA)) {
            JSONArray items = target.getJSONArray("items");
            String itemId = params.getString("itemId");
            JSONObject item = item(items, itemId);
            if (item != null) return appendSummary(item, params);
            // Recovery can deliver a delta before item/started.
            item = new JSONObject().put("id", itemId).put("type", "reasoning").put("summary", new JSONArray())
                    .put("content", new JSONArray()).put("_streamCompleted", false);
            if (!appendSummary(item, params)) return false;
            items.put(item);
        } else return false;
        return true;
    }
}
