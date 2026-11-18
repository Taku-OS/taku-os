package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

public final class AgentSessionReducerTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static JSONObject event(String method, JSONObject params) throws Exception {
        return new JSONObject().put("method", method).put("params", params.put("threadId", "thread").put("turnId", "turn"));
    }
    private static JSONObject item(String id, String type, String text) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("text", text);
    }
    private static JSONObject itemAt(JSONObject thread, int index) throws Exception {
        return thread.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(index);
    }
    public static void main(String[] args) throws Exception {
        JSONObject thread = new JSONObject().put("id", "thread").put("turns", new JSONArray());
        AgentSessionReducer.apply(thread, event("item/agentMessage/delta", new JSONObject().put("itemId", "a").put("delta", "Hello 中")));
        AgentSessionReducer.apply(thread, event("item/started", new JSONObject().put("item", item("a", "agentMessage", "").put("phase", "final_answer"))));
        check(itemAt(thread, 0).getString("text").equals("Hello 中"));
        check(itemAt(thread, 0).getString("phase").equals("final_answer"));
        AgentSessionReducer.apply(thread, event("item/agentMessage/delta", new JSONObject().put("itemId", "a").put("delta", "文🙂")));
        check(itemAt(thread, 0).getString("text").equals("Hello 中文🙂"));
        AgentSessionReducer.apply(thread, event("item/completed", new JSONObject().put("item", item("a", "agentMessage", "Corrected 中文🙂"))));
        check(itemAt(thread, 0).getString("text").equals("Corrected 中文🙂") && itemAt(thread, 0).getBoolean("_streamCompleted"));
        check(!AgentSessionReducer.apply(thread, event("item/agentMessage/delta", new JSONObject().put("itemId", "a").put("delta", "late"))));
        AgentSessionReducer.apply(thread, event("item/started", new JSONObject().put("item", item("a", "agentMessage", ""))));
        check(itemAt(thread, 0).getString("text").equals("Corrected 中文🙂"));
        AgentSessionReducer.apply(thread, event("item/completed", new JSONObject().put("item", item("cmd", "commandExecution", "tool"))));
        AgentSessionReducer.apply(thread, event("item/completed", new JSONObject().put("item", item("b", "agentMessage", "no deltas").put("phase", "commentary"))));
        check(itemAt(thread, 2).getString("text").equals("no deltas") && itemAt(thread, 2).getString("phase").equals("commentary"));
        JSONObject cold = new JSONObject().put("id", "thread").put("turns", new JSONArray().put(new JSONObject()
                .put("id", "turn").put("status", "inProgress").put("items", new JSONArray().put(item("item-0", "agentMessage", "old")))));
        AgentSessionReducer.retainActiveTurns(cold, thread);
        check(itemAt(cold, 0).getString("id").equals("a") && cold.getJSONArray("turns").length() == 1);
        AgentSessionReducer.apply(thread, event("turn/completed", new JSONObject().put("turn", new JSONObject().put("id", "turn")
                .put("status", "interrupted").put("items", new JSONArray().put(item("b", "agentMessage", "final correction"))))));
        check(thread.getJSONArray("turns").getJSONObject(0).getJSONArray("items").length() == 3);
        check(itemAt(thread, 1).getString("type").equals("commandExecution") && itemAt(thread, 2).getString("text").equals("final correction"));
        check(thread.getJSONArray("turns").getJSONObject(0).getString("status").equals("interrupted"));
        JSONObject history = new JSONObject().put("id", "thread").put("turns", new JSONArray().put(new JSONObject().put("id", "turn")
                .put("status", "completed").put("items", new JSONArray().put(item("item-0", "agentMessage", "full history")))));
        AgentSessionReducer.retainActiveTurns(history, thread);
        check(itemAt(history, 0).getString("id").equals("item-0"));
        check(!AgentSessionReducer.apply(thread, event("item/reasoning/textDelta", new JSONObject().put("itemId", "a").put("delta", "hidden"))));
        System.out.println("AgentSessionReducerTest: " + checks + " assertions passed");
    }
}
