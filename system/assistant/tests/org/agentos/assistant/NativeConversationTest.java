package org.agentos.assistant;

import org.json.*;

/** Protocol fixtures only. Never writes conversations or simulates a live model response. */
public final class NativeConversationTest {
    static int checks;
    static JSONObject object(String json) throws Exception { return new JSONObject(json); }
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        JSONObject thread = object("{id:'thread',name:'原生会话',turns:[{id:'turn',status:'inProgress',items:["
                + "{id:'u',type:'userMessage',content:[{type:'text',text:'保留 **原文**'},"
                + "{type:'localImage',path:'/fixtures/screenshot.png'},{type:'image',url:'data:image/png;base64,unused'}]},"
                + "{id:'a',type:'agentMessage',text:'正在检查'},"
                + "{id:'c',type:'commandExecution',command:'printf test',status:'inProgress'},"
                + "{id:'r',type:'reasoning',content:['private']},"
                + "{id:'p',type:'plan',text:'真实公开计划'},"
                + "{id:'f',type:'fileChange',status:'failed',changes:[{path:'file.txt',diff:'-old\\n+new'}]}]}]}");
        AgentConversation conversation = AgentConversation.read(thread);
        check(conversation.running, "running is supplied by the turn");
        check(conversation.entries.size() == 5, "preserve visible items; no invented steps or private reasoning");
        AgentConversation.Entry user = conversation.entries.get(0);
        check(user.text.equals("保留 **原文**") && user.attachments.size() == 2, "text and images remain one native message");
        check(user.attachments.get(0).equals("screenshot.png"), "local image label comes from input");
        AgentConversation.Entry command = conversation.entries.get(2);
        check(command.key().equals("turn/c"), "expansion key is stable across deltas");
        check(NativeToolPresentation.status(command.item).equals("In progress"), "native running status");
        check(NativeToolPresentation.details(command.item, "streamed output").contains("streamed output"), "output delta renders under its item");
        command.item.put("aggregatedOutput", JSONObject.NULL);
        check(NativeToolPresentation.details(command.item, "live delta").contains("live delta"), "nullable output must not hide live deltas");
        check(!NativeToolPresentation.details(command.item, "live delta").contains("null"), "JSON null never becomes display text");
        command.item.put("status", "completed").put("aggregatedOutput", "final output").put("exitCode", 0);
        check(NativeToolPresentation.status(command.item).equals("Ended"), "completed status");
        String detail = NativeToolPresentation.details(command.item, "old delta");
        check(detail.contains("final output") && !detail.contains("old delta"), "authoritative completed output replaces deltas");
        check(detail.contains("Exit code: 0"), "actual exit code retained");
        check(NativeToolPresentation.status(conversation.entries.get(4).item).equals("Failed"), "failed change cannot look successful");
        check(NativeToolPresentation.details(conversation.entries.get(4).item, "").contains("+new"), "actual diff retained");
        for (String[] state : new String[][]{{"failed","Failed"},{"declined","Declined"},{"interrupted","Interrupted"},
                {"pending","Waiting"},{"futureStatus",""},{"",""}}) {
            check(NativeToolPresentation.status(new JSONObject().put("status", state[0])).equals(state[1]), "honor " + state[0]);
        }
        check(NativeToolPresentation.status(object("{status:'inProgress',success:null}")).equals("In progress"), "nullable dynamic success is not failure");
        check(NativeToolPresentation.status(object("{status:'completed',success:false}")).equals("Failed"), "dynamic tool false success takes precedence");
        check(NativeToolPresentation.title(object("{type:'dynamicToolCall',tool:'taku_phone.open_app'}")).equals("Open app"), "known phone primitive label");
        check(NativeToolPresentation.title(object("{type:'dynamicToolCall',tool:'other.open_app'}")).equals("other.open_app"), "unrelated tool not mislabeled");
        thread.getJSONArray("turns").getJSONObject(0).put("status", "interrupted");
        check(!AgentConversation.read(thread).running, "interruption clears turn running state");
        JSONObject empty = object("{id:'empty',turns:[]}");
        check(AgentConversation.read(empty).entries.isEmpty(), "empty thread has no synthetic content");
        String attachment = "用户选择的附件：notes.txt\n本机路径：/data/user/0/org.agentos.assistant/files/composer-attachments/one/notes.txt";
        thread.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(0)
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", attachment)));
        user = AgentConversation.read(thread).entries.get(0);
        check(user.text.isEmpty() && user.attachments.get(0).equals("notes.txt"), "existing broker file envelope becomes attachment label");
        String englishAttachment = "User-selected attachment: notes.txt\nLocal path: /data/user/0/org.agentos.assistant/files/composer-attachments/one/notes.txt";
        thread.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(0)
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", englishAttachment)));
        user = AgentConversation.read(thread).entries.get(0);
        check(user.text.isEmpty() && user.attachments.get(0).equals("notes.txt"), "English broker file envelope also becomes an attachment label");
        turnPresentation();
        primaryProjection();
        staleTaskDoesNotBecomeLiveTurn();
        System.out.println("PASS NativeConversationTest: " + checks + " assertions (protocol fixtures only)");
    }
    static void staleTaskDoesNotBecomeLiveTurn() throws Exception {
        AgentConversation stale = AgentConversation.read(object("{id:'thread',status:{type:'idle'},turns:[{id:'old-turn',status:'inProgress',items:[]}]}"));
        JSONObject snapshot = object("{task:{id:'1',state:'completed',published:true},request:{id:'1',conversationId:'thread'},runtime:{threadId:'thread',turnId:'old-turn',turnStarted:true},native:{}}");
        for (String state : new String[]{"completed", "failed", "cancelled", "needs_user", "unknown"}) {
            snapshot.getJSONObject("task").put("state", state);
            check(stale.activeTurn(snapshot).isEmpty() && stale.activeTask(snapshot).isEmpty(),
                    "terminal or unknown task cannot expose a stale stop/steer target: " + state);
        }
        snapshot.getJSONObject("task").put("state", "running");
        check(stale.activeTurn(snapshot).equals("old-turn") && stale.activeTask(snapshot).equals("1"),
                "a matching published running task with a confirmed turn supports fallback");
        snapshot.getJSONObject("runtime").put("turnStarted", false);
        check(stale.activeTurn(snapshot).isEmpty(), "queued preparation is not a started turn");
        snapshot.getJSONObject("runtime").put("turnStarted", true).put("threadId", "other");
        check(stale.activeTurn(snapshot).isEmpty(), "runtime must belong to the current conversation");
        snapshot.getJSONObject("runtime").put("threadId", "thread");
        snapshot.getJSONObject("request").put("conversationId", "other");
        check(stale.activeTurn(snapshot).isEmpty() && stale.activeTask(snapshot).isEmpty(),
                "task request must belong to the displayed conversation");
        snapshot.getJSONObject("request").put("conversationId", "thread").put("id", "2");
        check(stale.activeTurn(snapshot).isEmpty(), "task and request ids must match");
        snapshot.getJSONObject("native").put("activeTurnId", "live-turn");
        check(stale.activeTurn(snapshot).equals("live-turn"), "actual live native state remains authoritative");
        check(AgentConversation.read(object("{id:'cold',historyNotLoaded:true,turns:[]}")).historyNotice
                        .contains("History is not fully loaded"), "cold metadata is never presented as loaded full history");
    }

    static void turnPresentation() throws Exception {
        JSONObject record = object("{id:'grouping',turns:[{id:'native-turn',status:'inProgress',items:["
                + "{id:'u',type:'userMessage',content:[{type:'text',text:'开始'}]},"
                + "{id:'c',type:'agentMessage',phase:'commentary',text:'检查中文输入'},"
                + "{id:'a',type:'agentMessage',phase:'final_answer',text:'正文第一段'},"
                + "{id:'x',type:'commandExecution',status:'completed',exitCode:0},"
                + "{id:'c2',type:'agentMessage',phase:'commentary',text:'继续处理'},"
                + "{id:'a2',type:'agentMessage',phase:'final_answer',text:'正文第二段'}]}]}");
        java.util.List<NativeTurnPresentation.Block> blocks = NativeTurnPresentation.blocks(AgentConversation.read(record));
        check(blocks.size() == 2, "one native user and one assistant group, no workflow");
        NativeTurnPresentation.Block block = blocks.get(1);
        check(block.activities.size() == 3 && block.answers.size() == 2, "native phases separate commentary and final answer");
        check(block.answerText().equals("正文第一段\n\n正文第二段"), "answer segments stay in order across tools");
        check(block.active && !block.terminal() && block.stateLabel().equals("Replying…"), "tool completion is not turn completion");
        check(block.key.equals("turn/native-turn/after/u"), "native identity anchors stable group");
        JSONObject turn = record.getJSONArray("turns").getJSONObject(0);
        JSONArray items = turn.getJSONArray("items");
        items.remove(5); items.remove(2);
        block = NativeTurnPresentation.blocks(AgentConversation.read(record)).get(1);
        check(block.stateLabel().equals("Waiting for reply…"), "tool-to-answer gap stays active");
        items.put(object("{id:'steer',type:'userMessage',content:[{type:'text',text:'同轮追加'}]}"));
        items.put(object("{id:'tail',type:'agentMessage',phase:'final_answer',text:'追加后的答复'}"));
        blocks = NativeTurnPresentation.blocks(AgentConversation.read(record));
        check(blocks.size() == 4 && blocks.get(2).user.text.equals("同轮追加"), "steer retains its native position");
        check(!blocks.get(1).active && blocks.get(3).active, "only last segment awaits new content");
        check(blocks.get(3).key.equals("turn/native-turn/after/steer"), "steer group has stable native anchor");
        turn.put("status", "interrupted");
        block = NativeTurnPresentation.blocks(AgentConversation.read(record)).get(3);
        check(block.terminal() && !block.active && block.stateLabel().equals("This turn stopped"), "interrupted partial answer remains readable");
        JSONObject legacy = object("{id:'legacy',turns:[{id:'t',status:'completed',items:["
                + "{id:'a',type:'agentMessage',text:'旧过程'},"
                + "{id:'b',type:'agentMessage',phase:'future_phase',text:'保留未知公开阶段'},"
                + "{id:'tool',type:'commandExecution',status:'completed',exitCode:2},"
                + "{id:'c',type:'agentMessage',text:'旧正文'}]}]}");
        block = NativeTurnPresentation.blocks(AgentConversation.read(legacy)).get(0);
        check(block.activities.size() == 2 && block.answers.size() == 2, "legacy fallback keeps trailing and unknown-phase text visible");
        check(block.failures() == 1 && block.terminal(), "nonzero command exit remains a failure in collapsed summary");
        for (String status : new String[]{"interrupted", "failed", "cancelled"}) {
            JSONObject emptyTurn = object("{id:'early-stop',turns:[{id:'t',items:[{id:'u',type:'userMessage',content:[{type:'text',text:'开始'}]}]}]}");
            emptyTurn.getJSONArray("turns").getJSONObject(0).put("status", status);
            java.util.List<NativeTurnPresentation.Block> stopped = NativeTurnPresentation.blocks(AgentConversation.read(emptyTurn));
            check(stopped.size() == 2 && stopped.get(0).user != null && stopped.get(1).terminal(),
                    "early " + status + " retains native end state without a phantom block before user");
        }
        check(NativeTurnPresentation.visibleText("中", true).equals("中"), "first Chinese character has no word threshold");
        check(NativeTurnPresentation.visibleText("中文\uD83D", true).equals("中文"), "hold unfinished emoji pair");
        check(NativeTurnPresentation.visibleText("中文😊", true).equals("中文😊"), "complete emoji appears with Chinese immediately");
        check(NativeTurnPresentation.blocks(AgentConversation.read(object("{id:'empty',turns:[]}"))).isEmpty(), "no fixed process for empty conversation");
    }

    static void primaryProjection() throws Exception {
        String readable = "消费明细\n咖啡\n12.50 USD\n继续";
        JSONObject reply = new JSONObject().put("schema", "taku.main-reply/1").put("id", "reply-one").put("readableText", readable)
                .put("provenance", new JSONObject().put("anchorOrdinal", 0));
        JSONArray items = new JSONArray()
                .put(new JSONObject().put("id", "user").put("type", "userMessage").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "查看明细"))))
                .put(new JSONObject().put("id", "before").put("type", "agentMessage").put("phase", JSONObject.NULL).put("text", "正在读取"))
                .put(new JSONObject().put("id", "tool").put("type", "commandExecution").put("status", "completed"))
                .put(new JSONObject().put("id", "source-one").put("type", "agentMessage").put("replyId", "reply-one").put("phase", JSONObject.NULL).put("text", readable))
                .put(new JSONObject().put("id", "source-two").put("type", "agentMessage").put("text", ""))
                .put(new JSONObject().put("id", "after").put("type", "agentMessage").put("phase", JSONObject.NULL).put("text", "公开执行记录"))
                .put(new JSONObject().put("id", "trailing-tool").put("type", "commandExecution").put("status", "completed"));
        JSONObject turn = new JSONObject().put("id", "turn").put("status", "completed")
                .put("mainReplies", new JSONObject().put("reply-one", reply)).put("items", items);
        JSONObject record = new JSONObject().put("id", "thread").put("turns", new JSONArray().put(turn));
        AgentConversation conversation = AgentConversation.read(record);
        NativeTurnPresentation.Block primary = NativeTurnPresentation.blocks(conversation).get(1);
        check(primary.answers.size() == 1 && primary.answerText().equals(readable), "native human answer and Copy use only the canonical primary projection");
        check(primary.activities.size() == 4, "same-anchor commentary and tools remain visible outside the primary answer");
        check(AgentConversation.lastReply(record).equals(readable), "later commentary and empty source aliases cannot replace the primary background summary");
        check(items.getJSONObject(3).isNull("phase") && items.getJSONObject(5).isNull("phase"), "presentation classification does not mutate native source phases");
        reply.put("readableText", "");
        check(AgentConversation.lastReply(record).isEmpty(), "an empty failed primary reply cannot fall back to commentary as its result");
        reply.put("readableText", readable);
        items.put(new JSONObject().put("id", "steer").put("type", "userMessage").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "普通追问"))));
        check(AgentConversation.lastReply(record).isEmpty(), "a newer user anchor cannot inherit the preceding primary answer");
        items.put(new JSONObject().put("id", "ordinary").put("type", "agentMessage").put("phase", "final_answer").put("text", "普通文本答案"));
        check(AgentConversation.lastReply(record).equals("普通文本答案"), "ordinary prose at a later user anchor remains the background answer");
        java.util.List<NativeTurnPresentation.Block> blocks = NativeTurnPresentation.blocks(AgentConversation.read(record));
        check(blocks.get(3).answerText().equals("普通文本答案"), "a preceding primary reply cannot hide a later ordinary answer");
        record.getJSONArray("turns").put(new JSONObject().put("id", "next").put("status", "failed").put("items", new JSONArray()
                .put(new JSONObject().put("id", "tool").put("type", "commandExecution").put("status", "failed"))));
        check(AgentConversation.lastReply(record).isEmpty(), "a new turn without an answer cannot report the preceding reply as its result");
    }

}
