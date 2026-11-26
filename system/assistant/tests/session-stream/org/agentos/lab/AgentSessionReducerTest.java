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
        reasoning();
        System.out.println("AgentSessionReducerTest: " + checks + " assertions passed");
    }

    private static JSONObject reasoningItem(String id, String... summary) throws Exception {
        return new JSONObject().put("id", id).put("type", "reasoning").put("summary", new JSONArray(summary)).put("content", new JSONArray());
    }
    private static JSONObject summaryDelta(String itemId, int index, String delta) throws Exception {
        return event("item/reasoning/summaryTextDelta", new JSONObject().put("itemId", itemId).put("summaryIndex", index).put("delta", delta));
    }

    /** The public reasoning summary streams and is timed; raw reasoning content never enters the thread. */
    private static void reasoning() throws Exception {
        JSONObject thread = new JSONObject().put("id", "thread").put("turns", new JSONArray());
        check(AgentSessionReducer.apply(thread, event("item/started", new JSONObject().put("item", reasoningItem("r")).put("startedAtMs", 1_000L))));
        check(itemAt(thread, 0).getLong("startedAtMs") == 1_000L && itemAt(thread, 0).getJSONArray("summary").length() == 0);
        check(AgentSessionReducer.apply(thread, summaryDelta("r", 0, "**Weighing** 中")));
        check(AgentSessionReducer.apply(thread, summaryDelta("r", 0, "文🙂")));
        check(AgentSessionReducer.apply(thread, summaryDelta("r", 1, "Next part")));
        check(itemAt(thread, 0).getJSONArray("summary").getString(0).equals("**Weighing** 中文🙂")
                && itemAt(thread, 0).getJSONArray("summary").getString(1).equals("Next part"));
        // Nothing to show is no event; neither is a part marker, raw content, or text for another kind of item.
        check(!AgentSessionReducer.apply(thread, summaryDelta("r", 0, "")));
        check(!AgentSessionReducer.apply(thread, summaryDelta("r", 64, "out of range")));
        check(!AgentSessionReducer.apply(thread, event("item/reasoning/summaryPartAdded", new JSONObject().put("itemId", "r").put("summaryIndex", 2))));
        check(!AgentSessionReducer.apply(thread, event("item/reasoning/textDelta", new JSONObject().put("itemId", "r").put("contentIndex", 0).put("delta", "raw"))));
        check(itemAt(thread, 0).getJSONArray("content").length() == 0 && itemAt(thread, 0).getJSONArray("summary").length() == 2);
        AgentSessionReducer.apply(thread, event("item/started", new JSONObject().put("item", item("answer", "agentMessage", ""))));
        check(!AgentSessionReducer.apply(thread, summaryDelta("answer", 0, "not an answer")));
        check(itemAt(thread, 1).getString("text").isEmpty() && !itemAt(thread, 1).has("summary"));
        // Completion is authoritative for the text and gives the duration; a later copy of the item keeps it.
        check(AgentSessionReducer.apply(thread, event("item/completed", new JSONObject().put("item", reasoningItem("r", "Final thought")).put("completedAtMs", 22_500L))));
        check(itemAt(thread, 0).getJSONArray("summary").length() == 1 && itemAt(thread, 0).getJSONArray("summary").getString(0).equals("Final thought"));
        check(itemAt(thread, 0).getLong("durationMs") == 21_500L && itemAt(thread, 0).getBoolean("_streamCompleted"));
        check(!AgentSessionReducer.apply(thread, summaryDelta("r", 0, "late")));
        AgentSessionReducer.apply(thread, event("turn/completed", new JSONObject().put("turn", new JSONObject().put("id", "turn")
                .put("status", "completed").put("items", new JSONArray().put(reasoningItem("r", "Final thought"))))));
        check(itemAt(thread, 0).getLong("durationMs") == 21_500L && itemAt(thread, 0).getJSONArray("summary").getString(0).equals("Final thought"));
        // History has the text but not when it was thought: the observed duration stays with the same item only.
        JSONObject history = new JSONObject().put("id", "thread").put("turns", new JSONArray().put(new JSONObject().put("id", "turn")
                .put("status", "completed").put("items", new JSONArray().put(reasoningItem("r", "Final thought")).put(reasoningItem("other", "Unseen")))));
        AgentSessionReducer.retainReasoningTiming(history, thread);
        check(itemAt(history, 0).getLong("durationMs") == 21_500L && !itemAt(history, 1).has("durationMs"));
        AgentSessionReducer.retainReasoningTiming(history, null);
        // A delta can arrive before its item during recovery; the late start keeps what was already shown.
        JSONObject recovered = new JSONObject().put("id", "thread").put("turns", new JSONArray());
        check(AgentSessionReducer.apply(recovered, summaryDelta("late", 0, "Already thinking")));
        check(itemAt(recovered, 0).getString("type").equals("reasoning") && !itemAt(recovered, 0).getBoolean("_streamCompleted"));
        AgentSessionReducer.apply(recovered, event("item/started", new JSONObject().put("item", reasoningItem("late")).put("startedAtMs", 5L)));
        check(itemAt(recovered, 0).getJSONArray("summary").getString(0).equals("Already thinking") && itemAt(recovered, 0).getLong("startedAtMs") == 5L);
        // Without a start time there is no duration to claim.
        JSONObject untimed = new JSONObject().put("id", "thread").put("turns", new JSONArray());
        AgentSessionReducer.apply(untimed, event("item/started", new JSONObject().put("item", reasoningItem("u"))));
        AgentSessionReducer.apply(untimed, event("item/completed", new JSONObject().put("item", reasoningItem("u", "Done")).put("completedAtMs", 9L)));
        check(!itemAt(untimed, 0).has("durationMs") && !itemAt(untimed, 0).has("startedAtMs"));
    }
}
