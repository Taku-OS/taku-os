package org.agentos.lab;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Routing-only tests opt into this double; projection tests compile the production AgentViews. */
final class AgentViews {
    interface EventHook { void accept(JSONObject event) throws Exception; }
    static EventHook eventHook;
    static final java.io.File ROOT=new java.io.File(System.getProperty("java.io.tmpdir"),"unused-agent-view");
    static void event(JSONObject event) throws Exception {if(eventHook!=null)eventHook.accept(event);}
    static String readableTurn(String thread,String turn) {return "Completed reply";}
    static JSONObject panelCompletion(String thread,String turn) {return new JSONObject().put("replies",new JSONArray());}
    static final Map<String,JSONObject> threads=new HashMap<>();
    static final SessionEvents events=new SessionEvents();
    static void save(JSONObject value) {}
    static void saveMetadata(JSONObject value) { save(value); }
    static void mergeTurnPage(String id,JSONArray turns,boolean descending,boolean hasOlder,boolean full) {
        throw new AssertionError("History paging is outside this routing test");
    }
    static void index() {}
    static JSONObject snapshot(String id) {return threads.get(id);}
    static void interruptReply(String thread,String turn) {}
    static void prepareReply(String thread,JSONObject contract,boolean enabled) {}
    static JSONObject mainReplyRead(JSONObject input) {throw new AssertionError("Reply reads are outside this routing test");}
    static void threadAvailability(String thread,String state) {}
    static SessionEvents.Subscription subscribe(JSONObject request) throws Exception {
        String id=request.getString("threadId");
        JSONObject thread=threads.get(id);
        if(thread==null)throw new IOException("THREAD_UNAVAILABLE");
        return events.subscribe(id,thread,request.optString("brokerEpoch"),request.optLong("afterSeq",-1));
    }
    static void replaceTurns(String id,JSONArray turns) {JSONObject thread=threads.get(id);if(thread!=null)thread.put("turns",turns);}
    static void remove(String id) {threads.remove(id);}
    static void runtimeClosed() {threads.clear();try{events.reset();}catch(Exception failure){throw new AssertionError(failure);}}
}
