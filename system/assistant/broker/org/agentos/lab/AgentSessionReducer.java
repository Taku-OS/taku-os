package org.agentos.lab;

import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordered raw projection. Display repair and pacing never enter this state. */
final class AgentSessionReducer {
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
        if (started && at >= 0) {
            JSONObject previous = items.getJSONObject(at);
            // Recovery can deliver a delta before metadata. A late start must not erase raw text.
            if (previous.optBoolean("_streamCompleted")) return;
            String text = previous.optString("text");
            if (!text.isEmpty()) item.put("text", text);
        }
        item.put("_streamCompleted", completed);
        if (at < 0) items.put(item); else items.put(at, item);
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
                    && !method.equals("item/agentMessage/delta")) return false;
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
            if (source != null) replaceItem(items, source, method.equals("item/completed"), method.equals("item/started"));
            else {
                String itemId = params.getString("itemId");
                JSONObject item = null;
                for (int i = 0; i < items.length(); i++)
                    if (itemId.equals(items.getJSONObject(i).optString("id"))) { item = items.getJSONObject(i); break; }
                if (item == null) {
                    item = new JSONObject().put("id", itemId).put("type", "agentMessage").put("text", "")
                            .put("_streamCompleted", false);
                    items.put(item);
                }
                if (item.optBoolean("_streamCompleted") || !"agentMessage".equals(item.optString("type"))) return false;
                item.put("text", item.optString("text") + params.optString("delta"));
            }
        } else return false;
        return true;
    }
}
