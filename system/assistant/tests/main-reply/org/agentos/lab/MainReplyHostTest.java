package org.agentos.lab;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the production parser and the same ordered projection consumed by the phone. */
public final class MainReplyHostTest {
    private static int assertions;
    private static JSONObject contract;
    private static final String THREAD="10000000-0000-0000-0000-000000000001";
    private static final String TURN="20000000-0000-0000-0000-000000000001";
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
    private static JSONObject json(String value) {return new JSONObject(value);}
    private static String line(String value) {return value+"\n";}
    private static String begin() {return line(new JSONObject().put("n",1).put("type","begin").put("catalogVersion",contract.getString("catalogVersion")).put("mode","card").toString());}
    private static final String BLOCK="{\"n\":2,\"type\":\"block\",\"id\":\"forecast\",\"kind\":\"collection\",\"title\":\"Forecast\",\"pending\":[\"items\"]}";
    private static final String ROOT="{\"n\":3,\"type\":\"element\",\"id\":\"root\",\"parent\":null,\"component\":\"Card\",\"props\":{}}";
    private static final String COLLECTION="{\"n\":4,\"type\":\"element\",\"id\":\"forecast-view\",\"parent\":\"root\",\"component\":\"Collection\",\"props\":{\"title\":{\"$state\":\"/data/blocksById/forecast/title\"},\"items\":{\"$state\":\"/data/blocksById/forecast/items\"}}}";
    private static final String MONDAY="{\"n\":5,\"type\":\"item\",\"blockId\":\"forecast\",\"id\":\"monday\",\"label\":\"Monday 中文\",\"detail\":\"Known now\",\"value\":{\"type\":\"number\",\"value\":0,\"unit\":\"C\"},\"pending\":[\"detail\"]}";
    private static String prefix() {return "@taku.reply/1\n"+begin()+line(BLOCK)+line(ROOT)+line(COLLECTION)+line(MONDAY);}
    private static String done(int n) {return line("{\"n\":"+n+",\"type\":\"done\"}");}
    private static MainReplyStream stream() {return new MainReplyStream(contract,THREAD,TURN,0,"a".repeat(64));}
    private static JSONObject request(MainReplyStream stream) {return stream.snapshot().getJSONObject("card").getJSONObject("request");}
    private static JSONObject firstBlock(MainReplyStream stream) {return request(stream).getJSONArray("blocks").getJSONObject(0);}
    private static String phase(MainReplyStream stream) {return stream.snapshot().getJSONObject("lifecycle").getString("phase");}
    private static String code(MainReplyStream stream) {return stream.snapshot().getJSONObject("lifecycle").getString("code");}

    private static void incremental() {
        MainReplyStream stream=stream();
        stream.push("@taku.reply/1\n"+begin()+line(BLOCK)+line(ROOT));
        check(stream.snapshot().isNull("card"),"An empty Card root cannot publish invisible semantic content");
        stream.push(line(COLLECTION)+line(MONDAY));
        JSONObject before=stream.snapshot();
        check(phase(stream).equals("streaming") && before.getJSONObject("card")!=null,"A real bound subtree publishes before done");
        check(before.getString("readableText").equals("Forecast\nMonday 中文\nKnown now\n0 C"),"Readable projection preserves zero and Unicode from semantic data");
        JSONObject fields=before.getJSONObject("card").getJSONObject("assembly").getJSONObject("fields");
        check(fields.getString("blocks/forecast/items/monday/detail").equals("streaming"),"Explicit pending content is visible and streaming");
        check(fields.getString("blocks/forecast/items/monday/image").equals("absent"),"Unannounced image is absent");
        stream.push("{\"n\":6,\"type\":\"item\",\"blockId\":\"forecast\",\"id\":\"tuesday\",\"label\":\"Tue");
        check(stream.snapshot().getInt("revision")==before.getInt("revision"),"Incomplete lines do not publish revisions");
        stream.push("sday\"}\n");
        check(firstBlock(stream).getJSONArray("items").length()==2,"Later rows append to the same collection");
        check(stream.snapshot().getString("id").equals(before.getString("id")),"The card identity survives growing content");
        stream.push(line("{\"n\":7,\"type\":\"block\",\"id\":\"forecast\",\"items\":[]}"));
        check(firstBlock(stream).getJSONArray("items").length()==2,"Omitted or empty updates do not discard accepted rows");
        stream.push(done(8));
        check(phase(stream).equals("sealed"),"Protocol done is not native execution completion");
        check(firstBlock(stream).getJSONArray("items").length()==2,"Done retains every known row");
        fields=stream.snapshot().getJSONObject("card").getJSONObject("assembly").getJSONObject("fields");
        check(fields.getString("blocks/forecast/items/monday/detail").equals("ready"),"Done settles nonempty pending content to ready");
        stream.finish("completed");
        check(phase(stream).equals("completed"),"Successful native completion admits the sealed card");
        JSONObject metrics=stream.snapshot().getJSONObject("card").getJSONObject("view").getJSONObject("metrics");
        check(metrics.getString("level").equals("primary")&&metrics.isNull("inputTokens")&&metrics.isNull("llmUsage")&&metrics.getInt("ms")==0,"Primary presentation claims no extra inference");
        before.put("readableText","caller mutation");
        check(!stream.snapshot().getString("readableText").equals("caller mutation"),"Caller mutation does not corrupt canonical state");
    }

    private static void segmentation() {
        String wire=prefix()+done(6);
        for(int split=0;split<=wire.length();split++) {
            MainReplyStream stream=stream();stream.push(wire.substring(0,split));stream.push(wire.substring(split));stream.finish("completed");
            check(phase(stream).equals("completed"),"Every UTF-16 split accepts the same complete reply at "+split);
            check(stream.snapshot().getString("readableText").equals("Forecast\nMonday 中文\nKnown now\n0 C"),"Chunking cannot alter readable facts");
        }
        MainReplyStream stream=stream();for(int i=0;i<wire.length();i++)stream.push(wire.substring(i,i+1));stream.finish("completed");
        check(phase(stream).equals("completed"),"One-character delivery is a valid production stream");
    }

    private static void rejectedRecords() {
        String[] invalid={
            "{\"n\":6,\"type\":\"card\",\"title\":\"one\",\"title\":\"two\"}",
            "{\"n\":6,\"type\":\"card\",\"title\":\"one\",\"\\u0074itle\":\"two\"}",
            "{'n':6,'type':'done'}",
            "{\"n\":6,\"type\":\"card\",\"title\":\"one\",}",
            "{\"n\":6,\"type\":\"card\",\"title\":\"one\",\"capability\":\"invented\"}",
            "{\"n\":6,\"type\":\"action\",\"id\":\"go\",\"label\":\"Go\",\"reply\":\"Go\",\"style\":\"primary\",\"actionRef\":\"forged\"}",
            "{\"n\":6,\"type\":\"block\",\"id\":\"forecast\",\"kind\":\"text\"}",
            "{\"n\":6,\"type\":\"block\",\"id\":\"input\",\"kind\":\"input\",\"value\":{\"type\":\"number\",\"value\":3},\"config\":{\"editor\":\"range\"}}",
            "{\"n\":6,\"type\":\"element\",\"id\":\"bad\",\"parent\":\"root\",\"component\":\"Text\",\"props\":{\"text\":\"copied fact\"}}",
            "{\"n\":6,\"type\":\"element\",\"id\":\"bad\",\"parent\":\"root\",\"component\":\"Text\",\"props\":{\"text\":{\"$state\":\"/data/blocksById/missing/text\"}}}",
            "{\"n\":6,\"type\":\"element\",\"id\":\"bad\",\"parent\":\"root\",\"component\":\"TextInput\",\"props\":{\"value\":{\"$bindState\":\"/data/title\"}}}",
            "{\"n\":6,\"type\":\"element\",\"id\":\"root\",\"parent\":null,\"component\":\"Card\",\"props\":{\"title\":{\"$state\":\"/data/title\"}}}"
        };
        for(String record:invalid) {
            MainReplyStream stream=stream();stream.push(prefix());String known=stream.snapshot().getString("readableText");stream.push(line(record));
            check(phase(stream).equals("failed"),"Invalid record fails explicitly: "+record);
            check(stream.snapshot().getString("readableText").equals(known),"Failure retains the last accepted prefix");
            stream.finish("completed");check(phase(stream).equals("failed"),"Real execution success cannot repair malformed presentation");
        }
        MainReplyStream duplicate=stream();duplicate.push(prefix());int revision=duplicate.snapshot().getInt("revision");duplicate.push(line(MONDAY));
        check(duplicate.snapshot().getInt("revision")==revision,"Identical sequence replay is a no-op");
        duplicate.push(line(MONDAY.replace("Known now","conflict")));check(code(duplicate).equals("RECORD_SEQUENCE_CONFLICT"),"Conflicting sequence replay fails");
        MainReplyStream gap=stream();gap.push(prefix());gap.push(done(7));check(code(gap).equals("RECORD_SEQUENCE_CONFLICT"),"Sequence gaps cannot skip validation");
        MainReplyStream after=stream();after.push(prefix()+done(6)+line("{\"n\":7,\"type\":\"card\",\"title\":\"late\"}"));
        check(phase(after).equals("failed"),"Records after done fail instead of extending the sealed card");
        MainReplyStream missingView=stream();missingView.push("@taku.reply/1\n"+begin()+line(BLOCK)+line(ROOT)+done(4));
        check(code(missingView).equals("INVALID_REPLY_VIEW"),"Done requires semantic coverage in the supplied view");
        MainReplyStream truncated=stream();truncated.push(prefix()+"{\"n\":6,\"type\":");truncated.finish("completed");
        check(phase(truncated).equals("failed")&&firstBlock(truncated).getJSONArray("items").length()==1,"Truncation retains useful content without success");
        MainReplyStream missingDone=stream();missingDone.push(prefix());missingDone.finish("completed");check(code(missingDone).equals("INCOMPLETE_REPLY"),"Native completion requires protocol done");
        for(String status:new String[]{"failed","interrupted"}) {
            MainReplyStream stream=stream();stream.push(prefix()+done(6));stream.finish(status);
            check(phase(stream).equals(status),"Sealed reply follows actual native terminal status "+status);
        }
        for(String header:new String[]{"@taku.reply/2\n"," @taku.reply/1\n","@taku.reply/1\r\n"}) {
            MainReplyStream stream=stream();stream.push(header);check(stream.isFramed()&&phase(stream).equals("failed"),"Recognizable invalid framing cannot become prose");
        }
        for(String wire:new String[]{begin(),"```json\n@taku.reply/1\n"+begin(),"```json\n"+begin()+"```\n","{\"n\":1,\"type\":\"begin\",\"catalogVersion\":\"unfinished"}) {
            MainReplyStream framed=stream();for(int i=0;i<wire.length();i++)framed.push(wire.substring(i,i+1));framed.finish("completed");
            check(framed.isFramed()&&phase(framed).equals("failed")&&framed.snapshot().getString("readableText").isEmpty(),"Headerless or fenced primary records are quarantined without wire leakage");
        }
        for(String wire:new String[]{"{\"n\":1,\"type\":\"invoice\",\"total\":12}","{\"request\":{\"n\":1,\"type\":\"block\"}}","```json\n{\"answer\":42}\n```"}) {
            MainReplyStream ordinary=stream();ordinary.push(wire);ordinary.finish("completed");
            check(ordinary.isPlain()&&ordinary.snapshot()==null,"User-requested unrelated JSON remains ordinary content");
        }
        MainReplyStream plain=stream();plain.push("Normal prose");plain.finish("completed");check(plain.isPlain()&&plain.snapshot()==null,"Unframed prose remains plain without formatting");
        MainReplyStream limit=stream();limit.push(prefix());limit.push("x".repeat(16385));check(code(limit).equals("REPLY_LIMIT_EXCEEDED"),"Line limit fails while retaining accepted content");
        MainReplyStream numeric=stream();numeric.push("@taku.reply/1\n"+begin()+line("{\"n\":2,\"type\":\"block\",\"id\":\"x\",\"kind\":\"metric\",\"value\":{\"type\":\"number\",\"value\":1e999999999}}"));
        check(phase(numeric).equals("failed"),"Nonfinite exponent cannot allocate an expanded canonical number");
    }

    private static void typedAndSchema() throws Exception {
        MainReplyStream stream=stream();stream.push("@taku.reply/1\n"+begin()+line("{\"n\":2,\"type\":\"block\",\"id\":\"balance\",\"kind\":\"metric\",\"title\":\"Balance\",\"value\":{\"type\":\"money\",\"value\":\"123456789012345678901\",\"currency\":\"USD\",\"scale\":2}}")
            +line(ROOT)+line("{\"n\":4,\"type\":\"element\",\"id\":\"balance-view\",\"parent\":\"root\",\"component\":\"Metric\",\"props\":{\"title\":{\"$state\":\"/data/blocksById/balance/title\"},\"value\":{\"$state\":\"/data/blocksById/balance/value\"}}}")+done(5));stream.finish("completed");
        check(stream.snapshot().getString("readableText").equals("Balance\n1234567890123456789.01 USD"),"Money uses exact minor units beyond IEEE precision");
        MainReplyStream unknown=stream();unknown.push("@taku.reply/1\n"+begin()+line("{\"n\":2,\"type\":\"block\",\"id\":\"forecast\",\"kind\":\"metric\",\"title\":\"Unknown\",\"value\":{\"type\":\"number\",\"value\":null}}")+line(ROOT)
            +line("{\"n\":4,\"type\":\"element\",\"id\":\"value\",\"parent\":\"root\",\"component\":\"Metric\",\"props\":{\"title\":{\"$state\":\"/data/blocksById/forecast/title\"},\"value\":{\"$state\":\"/data/blocksById/forecast/value\"}}}"));
        check(unknown.snapshot().getString("readableText").equals("Unknown"),"Typed null is never projected as a fact or zero");
        check(unknown.snapshot().getJSONObject("card").getJSONObject("assembly").getJSONObject("fields").getString("blocks/forecast/value").equals("ready"),"Explicit typed unknown is not automatically waiting");
        JSONObject signature=request(stream);
        check(MainReplyStream.signature(signature).equals("{\"version\":2,\"mode\":\"card\",\"layout\":\"compose\",\"blocks\":[{\"id\":\"balance\",\"kind\":\"metric\",\"type\":\"money\",\"config\":{}}],\"actions\":[]}"),"Native signature matches the frontend JSON.stringify contract");
        UiCardRequest.validateSchema(new JSONObject().put("anything",new JSONArray().put(false)),new JSONObject());
        UiCardRequest.validateSchema(0.5,json("{\"type\":\"number\",\"exclusiveMinimum\":0}"));
        boolean rejected=false;try{UiCardRequest.validateSchema(0,json("{\"type\":\"number\",\"exclusiveMinimum\":0}"));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Exported exclusiveMinimum rejects zero");
        rejected=false;try{UiCardRequest.validateSchema("ok",json("{\"type\":\"string\",\"format\":\"invented\"}"));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Unknown schema constructs cannot silently weaken admission");
    }

    private static void actionsAndBounds() {
        MainReplyStream action=stream();action.push(prefix()
            +line("{\"n\":6,\"type\":\"action\",\"id\":\"more\",\"label\":\"More details\",\"reply\":\"Show more details\",\"style\":\"secondary\"}")
            +line("{\"n\":7,\"type\":\"element\",\"id\":\"more-button\",\"parent\":\"root\",\"component\":\"Button\",\"props\":{\"label\":{\"$state\":\"/data/actionsById/more/label\"},\"disabled\":{\"$state\":\"/data/actionsById/more/disabled\"}},\"on\":{\"press\":{\"action\":\"reply\",\"params\":{\"actionId\":\"more\",\"text\":{\"$state\":\"/data/actionsById/more/reply\"}}}}}")+done(8));
        check(phase(action).equals("sealed"),"Declared actions with bound current text and disabled state form a valid sealed view");
        check(!request(action).getJSONArray("actions").getJSONObject(0).has("actionRef"),"The main model never mints action execution authority");
        MainReplyStream source=stream();source.push(prefix());source.push("x".repeat(262144));
        check(code(source).equals("REPLY_LIMIT_EXCEEDED")&&firstBlock(source).getJSONArray("items").length()==1,"Source limit fails explicitly without truncating known facts");
        MainReplyStream items=stream();items.push(prefix());
        for(int i=0;i<20;i++)items.push(line(new JSONObject().put("n",i+6).put("type","item").put("blockId","forecast").put("id","row-"+i).put("label","Row "+i).toString()));
        check(code(items).equals("REPLY_LIMIT_EXCEEDED")&&firstBlock(items).getJSONArray("items").length()==20,"Item bound retains all twenty admitted rows");
        MainReplyStream nodes=stream();nodes.push(prefix());
        for(int i=0;i<95;i++)nodes.push(line(new JSONObject().put("n",i+6).put("type","element").put("id","space-"+i).put("parent","root").put("component","Spacer").put("props",new JSONObject()).toString()));
        check(code(nodes).equals("REPLY_LIMIT_EXCEEDED")&&nodes.snapshot().getJSONObject("card").getJSONObject("view").getJSONObject("spec").getJSONObject("elements").length()==96,"Element bound retains the last valid supplied tree");
        MainReplyStream depth=stream();depth.push(prefix());String parent="root";
        for(int i=0;i<32;i++) {String id="column-"+i;depth.push(line(new JSONObject().put("n",i+6).put("type","element").put("id",id).put("parent",parent).put("component","Column").put("props",new JSONObject()).toString()));parent=id;}
        check(code(depth).equals("REPLY_LIMIT_EXCEEDED"),"Deep trees fail at the declared depth bound");
    }

    private static JSONObject user(String id,String text) {return new JSONObject().put("id",id).put("type","userMessage").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));}
    private static JSONObject answer(String id,String text) {return new JSONObject().put("id",id).put("type","agentMessage").put("phase",JSONObject.NULL).put("text",text);}
    private static JSONObject turn(String status,JSONObject... items) {return new JSONObject().put("id",TURN).put("status",status).put("items",new JSONArray(items));}
    private static JSONObject thread(JSONObject turn) {return new JSONObject().put("id",THREAD).put("turns",new JSONArray().put(turn));}
    private static void event(String method,JSONObject params) throws Exception {AgentViews.event(new JSONObject().put("method",method).put("params",params.put("threadId",THREAD)));}
    private static void delta(String text) throws Exception {event("item/agentMessage/delta",new JSONObject().put("turnId",TURN).put("itemId","answer").put("delta",text));}
    private static JSONObject currentReply() throws Exception {JSONObject replies=AgentViews.snapshot(THREAD).getJSONArray("turns").getJSONObject(0).getJSONObject("mainReplies");return replies.getJSONObject(replies.keys().next());}
    private static List<JSONObject> drain(SessionEvents.Subscription events) throws Exception {List<JSONObject> result=new ArrayList<>();String line;while((line=events.next(1))!=null){check(!line.contains("@taku.reply"),"Public stream never leaks framed wire");result.add(json(line));}return result;}
    private static void reset() throws Exception {AgentViews.remove(THREAD);Files.deleteIfExists(new File(AgentViews.ROOT,THREAD+".json").toPath());}

    private static void lifecycle() throws Exception {
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"))));
        AgentViews.prepareReply(THREAD,contract,true);
        try(SessionEvents.Subscription events=AgentViews.subscribe(new JSONObject().put("threadId",THREAD))) {
            drain(events);
            event("turn/started",new JSONObject().put("turn",turn("inProgress",user("user","Forecast"))));
            event("item/started",new JSONObject().put("turnId",TURN).put("item",answer("answer","")));
            drain(events);
            for(int i=0;i<"@taku.reply/1\n".length();i++){delta("@taku.reply/1\n".substring(i,i+1));check(drain(events).isEmpty(),"Header fragments are not public text");}
            delta(begin()+line(BLOCK)+line(ROOT)+line(COLLECTION)+line(MONDAY));
            JSONObject early=currentReply();
            check(early.getJSONObject("lifecycle").getString("phase").equals("streaming")&&early.getJSONObject("card")!=null,"Production reducer publishes a useful card while turn is running");
            List<JSONObject> published=drain(events);
            check(published.stream().anyMatch(e->e.getString("method").equals("session/reply")),"Canonical reply uses the existing ordered session event");
            for(JSONObject e:published)if(e.getString("method").equals("session/reply")) {
                JSONObject p=e.getJSONObject("params"),reply=p.getJSONObject("reply");JSONArray items=p.getJSONArray("items");
                check(items.length()==1&&items.getJSONObject(0).getString("replyId").equals(reply.getString("id")),"Reply and sanitized source projection publish atomically");
                check(items.getJSONObject(0).getString("text").equals(reply.getString("readableText")),"GUI and text consumers see exactly the same native projection");
            }
            delta("{\"n\":6");check(drain(events).isEmpty(),"An unfinished JSON record produces no public event");
            delta(",\"type\":\"done\"}\n");
            check(currentReply().getJSONObject("lifecycle").getString("phase").equals("sealed"),"Reducer cannot guess native success from done");
            event("item/completed",new JSONObject().put("turnId",TURN).put("item",answer("final-answer",prefix()+done(6))));
            event("turn/completed",new JSONObject().put("turn",turn("completed",answer("final-answer",prefix()+done(6)))));
            JSONObject completed=currentReply();check(completed.getJSONObject("lifecycle").getString("phase").equals("completed"),"Authoritative native completion seals the same reply");
            check(completed.getString("id").equals(early.getString("id")),"Completion does not replace card identity");drain(events);
            JSONObject read=AgentViews.mainReplyRead(new JSONObject().put("action","read").put("threadId",THREAD).put("turnId",TURN).put("replyId",completed.getString("id")));
            check(read.getBoolean("ok")&&read.getJSONObject("reply").similar(completed),"Open-access read returns the exact GUI snapshot");
            check(AgentViews.readableTurn(THREAD,TURN).equals(completed.getString("readableText")),"Chat result text uses the canonical readable projection");
            JSONObject persisted=json(Files.readString(new File(AgentViews.ROOT,THREAD+".json").toPath()));
            check(persisted.getJSONArray("turns").getJSONObject(0).getJSONObject("mainReplies").getJSONObject(completed.getString("id")).similar(completed),"Terminal checkpoint is durable immediately");
            AgentViews.save(thread(turn("completed",user("history-user","Forecast"),answer("history-answer",prefix()+done(6)))));
            JSONObject renamed=currentReply();check(renamed.getString("id").equals(completed.getString("id"))&&renamed.getJSONObject("provenance").getJSONArray("sourceItemIds").getString(0).equals("history-answer"),"Canonical item renames update aliases without replacing reply identity");
            drain(events);
        }
        reset();
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"),answer("answer",prefix()))));AgentViews.prepareReply(THREAD,contract,true);
        JSONObject before=currentReply();
        AgentViews.save(thread(turn("inProgress",user("history-user","Forecast"),answer("history-answer","@taku.reply/1\n"+begin()))));
        check(currentReply().getString("readableText").equals(before.getString("readableText")),"A canonical read cannot rewind a live accepted prefix");
        event("item/completed",new JSONObject().put("turnId",TURN).put("item",user("steer","Different request")));
        check(currentReply().getJSONObject("lifecycle").getString("code").equals("USER_ANCHOR_CHANGED"),"Steering interrupts the prior anchor honestly");
        delta(line("{\"n\":6,\"type\":\"card\",\"title\":\"late old answer\"}"));
        check(!currentReply().getString("readableText").contains("late old answer"),"Late old-source deltas cannot enter the new user anchor");
        event("item/completed",new JSONObject().put("turnId",TURN).put("item",answer("new-answer","New ordinary answer")));
        check(AgentViews.readableTurn(THREAD,TURN).equals("New ordinary answer"),"A later plain answer is not replaced by an older primary card");
        reset();
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"),answer("answer",prefix()))));AgentViews.prepareReply(THREAD,contract,true);
        String retained=currentReply().getString("readableText");AgentViews.runtimeClosed();
        JSONObject restored=AgentViews.mainReplyRead(new JSONObject().put("action","read").put("threadId",THREAD).put("turnId",TURN));
        check(restored.getJSONArray("replies").getJSONObject(0).getJSONObject("lifecycle").getString("code").equals("RUNTIME_LOST"),"Runtime loss persists an honest interrupted reply");
        AgentViews.save(thread(turn("inProgress",user("history-user","Forecast"),answer("history-answer",prefix()))));AgentViews.prepareReply(THREAD,contract,true);
        check(currentReply().getJSONObject("lifecycle").getString("phase").equals("interrupted")&&currentReply().getString("readableText").equals(retained),"Restart does not turn an unfinished checkpoint back into live success");
        AgentViews.save(thread(turn("completed",user("history-user","Forecast"),answer("history-answer",prefix()+done(6)))));
        check(currentReply().getJSONObject("lifecycle").getString("phase").equals("completed"),"Raw authoritative history can establish completed outcome after restart");
        reset();
        AgentViews.save(thread(turn("inProgress",user("user","Structured"))));AgentViews.prepareReply(THREAD,contract,false);
        event("turn/started",new JSONObject().put("turn",turn("inProgress",user("user","Structured"))));
        event("item/completed",new JSONObject().put("turnId",TURN).put("item",answer("answer","{\"status\":\"completed\"}")));
        check(!AgentViews.snapshot(THREAD).getJSONArray("turns").getJSONObject(0).has("mainReplies"),"Explicit structured mode never creates a primary reply");
        reset();
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"),answer("answer",prefix()))));AgentViews.prepareReply(THREAD,contract,true);
        AgentViews.interruptReply(THREAD,TURN);
        check(currentReply().getJSONObject("lifecycle").getString("code").equals("TURN_INTERRUPTED"),"Actual interrupt control settles the existing presentation");
        reset();
    }

    private static void liveHistory() throws Exception {
        JSONArray turns=new JSONArray();
        for(int i=0;i<24;i++)turns.put(new JSONObject().put("id","history-"+i).put("status","completed").put("items",new JSONArray().put(user("u-"+i,"Earlier request "+i)).put(answer("a-"+i,"Earlier answer "+i))));
        turns.put(turn("inProgress",user("user","Forecast"),answer("planning","Checking the weather"),new JSONObject().put("id","tool").put("type","commandExecution").put("status","completed"),answer("answer","")));
        AgentViews.save(new JSONObject().put("id",THREAD).put("turns",turns));AgentViews.prepareReply(THREAD,contract,true);
        try(SessionEvents.Subscription events=AgentViews.subscribe(new JSONObject().put("threadId",THREAD))) {
            drain(events);
            for(int i=0;i<prefix().length();i++)delta(prefix().substring(i,i+1));
            JSONObject active=AgentViews.snapshot(THREAD).getJSONArray("turns").getJSONObject(24);
            JSONObject reply=active.getJSONObject("mainReplies").getJSONObject(active.getJSONObject("mainReplies").keys().next());
            String id=reply.getString("id");int revision=reply.getInt("revision");drain(events);
            check(reply.getString("readableText").contains("Monday"),"Nullable answer after the last tool publishes through the live fragment path");
            delta("{\"n\":6,\"type\":\"item\",\"blockId\":\"forecast\",\"id\":\"tuesday\",\"label\":\"Tues");
            check(drain(events).isEmpty(),"Incomplete live records stay private with retained history");
            JSONObject read=AgentViews.mainReplyRead(new JSONObject().put("action","read").put("threadId",THREAD).put("turnId",TURN).put("replyId",id));
            check(read.getJSONObject("reply").getInt("revision")==revision,"Native reads retain the accepted revision during unfinished fragments");
            delta("day\"}\n");
            JSONObject after=AgentViews.snapshot(THREAD);JSONObject current=after.getJSONArray("turns").getJSONObject(24).getJSONObject("mainReplies").getJSONObject(id);
            check(current.getString("readableText").contains("Tuesday")&&current.getInt("revision")>revision,"The completed record advances the same active reply");
            for(int i=0;i<24;i++)check(after.getJSONArray("turns").getJSONObject(i).getJSONArray("items").getJSONObject(1).getString("text").equals("Earlier answer "+i),"Live updates retain historical visible answers");
            drain(events);int accepted=current.getInt("revision");
            delta(line("{\"n\":6,\"type\":\"item\",\"blockId\":\"forecast\",\"id\":\"tuesday\",\"label\":\"Tuesday\"}"));
            check(drain(events).isEmpty(),"Exact replay does not republish a live card");
            check(AgentViews.mainReplyRead(new JSONObject().put("action","read").put("threadId",THREAD).put("turnId",TURN).put("replyId",id)).getJSONObject("reply").getInt("revision")==accepted,"Replay preserves the canonical revision");
        }
        reset();
    }

    private static void canonicalCatchup() throws Exception {
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"),answer("answer",prefix()))));AgentViews.prepareReply(THREAD,contract,true);
        String id=currentReply().getString("id"),shorter="@taku.reply/1\n"+begin();
        AgentViews.save(thread(turn("inProgress",user("renamed-user","Forecast"),answer("renamed-answer",shorter))));
        try(SessionEvents.Subscription events=AgentViews.subscribe(new JSONObject().put("threadId",THREAD))) {
            drain(events);int revision=currentReply().getInt("revision");
            String catchup=prefix().substring(shorter.length());
            for(int i=0;i<catchup.length();i++)event("item/agentMessage/delta",new JSONObject().put("turnId",TURN).put("itemId","renamed-answer").put("delta",catchup.substring(i,i+1)));
            check(drain(events).isEmpty()&&currentReply().getInt("revision")==revision,"Canonical prefix catchup consumes overlap without replaying accepted records");
            event("item/started",new JSONObject().put("turnId",TURN).put("item",answer("renamed-answer","")));
            check(currentReply().getString("readableText").contains("Monday"),"Late metadata retains buffered raw source and the accepted card");
            event("item/agentMessage/delta",new JSONObject().put("turnId",TURN).put("itemId","renamed-answer").put("delta",done(6)));
            check(currentReply().getString("id").equals(id)&&currentReply().getJSONObject("lifecycle").getString("phase").equals("sealed"),"New suffix after canonical catchup advances the original reply");
            event("turn/completed",new JSONObject().put("turn",turn("completed",answer("final-answer",prefix()+done(6)))));
            check(currentReply().getJSONObject("lifecycle").getString("phase").equals("completed"),"Final replacement can reconcile buffered fragments and finish honestly");
            drain(events);
        }
        reset();
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"),answer("answer",prefix()))));AgentViews.prepareReply(THREAD,contract,true);
        String next="20000000-0000-0000-0000-000000000002";
        event("turn/started",new JSONObject().put("turn",new JSONObject().put("id",next).put("status","inProgress").put("items",new JSONArray().put(user("next-user","Another request")))));
        check(currentReply().getJSONObject("lifecycle").getString("code").equals("USER_ANCHOR_CHANGED"),"A new turn publishes interruption of the prior active anchor without replaying history");
        reset();
    }

    private static void reviewedSources() throws Exception {
        AgentViews.save(thread(turn("inProgress",user("user","Forecast"),answer("planning",""))));AgentViews.prepareReply(THREAD,contract,true);
        try(SessionEvents.Subscription events=AgentViews.subscribe(new JSONObject().put("threadId",THREAD))) {
            drain(events);
            event("item/agentMessage/delta",new JSONObject().put("turnId",TURN).put("itemId","planning").put("delta","Checking the weather"));
            event("item/started",new JSONObject().put("turnId",TURN).put("item",new JSONObject().put("id","tool").put("type","commandExecution")));
            event("item/started",new JSONObject().put("turnId",TURN).put("item",answer("answer","")));
            delta(prefix()+done(6));
            JSONObject active=AgentViews.snapshot(THREAD).getJSONArray("turns").getJSONObject(0);
            check(active.optJSONObject("mainReplies")!=null,"A later tool reclassifies nullable planning without disabling the subsequent primary answer");
            check(currentReply().getString("readableText").contains("Monday"),"The actual answer, rather than the earlier planning text, owns the card");
            drain(events);
            event("item/completed",new JSONObject().put("turnId",TURN).put("item",answer("final-answer",prefix()+done(6))));
            event("turn/completed",new JSONObject().put("turn",turn("completed",answer("final-answer",prefix()+done(6)))));
            JSONObject completed=currentReply();String replyId=completed.getString("id");
            JSONArray items=AgentViews.snapshot(THREAD).getJSONArray("turns").getJSONObject(0).getJSONArray("items");
            int carriers=0;
            for(int i=0;i<items.length();i++)if(replyId.equals(items.getJSONObject(i).optString("replyId"))){carriers++;check(items.getJSONObject(i).getString("text").equals(completed.getString("readableText")),"The tagged source item carries exactly the readable answer");}
            check(carriers==1,"Multiple SDK source aliases have exactly one readable-text carrier");
            check(completed.getJSONObject("provenance").getJSONArray("sourceItemIds").length()==2,"Sanitizing duplicate carriers retains both authoritative source identities");
            for(JSONObject published:drain(events))if(published.getString("method").equals("session/reply")) {
                JSONArray projected=published.getJSONObject("params").getJSONArray("items");
                check(projected.length()==1&&replyId.equals(projected.getJSONObject(0).getString("replyId")),"Every alias update satisfies the client's one-carrier stream contract");
            }
            int revision=completed.getInt("revision");String readable=completed.getString("readableText");
            AgentViews.save(thread(turn("completed",user("user","Forecast"),answer("answer",(prefix()+done(6)).replace("Monday 中文","Changed Monday")))));
            JSONObject invalidated=currentReply();
            check(invalidated.getJSONObject("lifecycle").getString("code").equals("SOURCE_CHANGED")&&invalidated.getJSONObject("lifecycle").getString("phase").equals("failed"),"Divergent authoritative history invalidates a completed reply");
            check(invalidated.getInt("revision")>revision&&invalidated.getString("readableText").equals(readable),"Invalidation advances authority while retaining the last accepted content");
            JSONObject persisted=json(Files.readString(new File(AgentViews.ROOT,THREAD+".json").toPath()));
            check(persisted.getJSONArray("turns").getJSONObject(0).getJSONObject("mainReplies").getJSONObject(replyId).getJSONObject("lifecycle").getString("phase").equals("failed"),"Completed-source invalidation is checkpointed for capability consumers");
            AgentViews.save(thread(turn("completed",user("user","Forecast"),answer("answer",prefix()+done(6)))));
            check(currentReply().getJSONObject("lifecycle").getString("phase").equals("failed"),"An older source cannot restore completed authority after invalidation");
            drain(events);
        }
        reset();
    }

    private static JSONObject thought(String id,String... summary) {return new JSONObject().put("id",id).put("type","reasoning").put("summary",new JSONArray(summary)).put("content",new JSONArray());}
    private static void thoughtDelta(String text) throws Exception {event("item/reasoning/summaryTextDelta",new JSONObject().put("turnId",TURN).put("itemId","thought").put("summaryIndex",0).put("delta",text));}
    private static JSONObject viewItem(int index) throws Exception {return AgentViews.snapshot(THREAD).getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(index);}

    /** The model's public reasoning summary reaches subscribers as it is written and stays readable afterwards. */
    private static void reasoningRelay() throws Exception {
        AgentViews.save(thread(turn("inProgress",user("user","Plan my week"))));AgentViews.prepareReply(THREAD,contract,true);
        try(SessionEvents.Subscription events=AgentViews.subscribe(new JSONObject().put("threadId",THREAD))) {
            drain(events);
            event("turn/started",new JSONObject().put("turn",turn("inProgress",user("user","Plan my week"))));
            event("item/started",new JSONObject().put("turnId",TURN).put("item",thought("thought")).put("startedAtMs",1_000L));
            List<JSONObject> started=drain(events);JSONObject first=started.get(started.size()-1);
            check(first.getString("method").equals("item/started")&&first.getJSONObject("params").getJSONObject("item").getLong("startedAtMs")==1_000L,"A reasoning item starts with the time the runtime gave it");
            thoughtDelta("**Weighing the week** 中");thoughtDelta("文🙂");
            List<JSONObject> streamed=drain(events);
            check(streamed.size()==2&&streamed.stream().allMatch(e->e.getString("method").equals("item/reasoning/summaryTextDelta")),"Each summary delta is one ordered stream event");
            check(streamed.get(1).getJSONObject("params").getString("delta").equals("文🙂")&&streamed.get(1).getJSONObject("params").getInt("summaryIndex")==0
                &&streamed.get(1).getLong("seq")==streamed.get(0).getLong("seq")+1,"Deltas keep their text, part and relay order");
            check(viewItem(1).getJSONArray("summary").getString(0).equals("**Weighing the week** 中文🙂"),"A reader that attaches late sees the summary so far");
            event("item/reasoning/textDelta",new JSONObject().put("turnId",TURN).put("itemId","thought").put("contentIndex",0).put("delta","raw chain of thought"));
            event("item/reasoning/summaryPartAdded",new JSONObject().put("turnId",TURN).put("itemId","thought").put("summaryIndex",1));
            check(drain(events).isEmpty()&&viewItem(1).getJSONArray("content").length()==0,"Raw reasoning content and part markers are never relayed");
            thoughtDelta("");check(drain(events).isEmpty(),"An empty delta is no event");
            event("item/completed",new JSONObject().put("turnId",TURN).put("item",thought("thought","**Weighing the week** 中文🙂")).put("completedAtMs",22_500L));
            List<JSONObject> completed=drain(events);JSONObject done=completed.get(completed.size()-1).getJSONObject("params").getJSONObject("item");
            check(done.getLong("durationMs")==21_500L&&done.getJSONArray("summary").getString(0).endsWith("中文🙂"),"Completion carries the whole summary and how long it took");
            thoughtDelta("late");check(drain(events).isEmpty(),"A completed summary takes no further text");
            event("item/started",new JSONObject().put("turnId",TURN).put("item",answer("answer","")));delta("Here is your week.");
            event("item/completed",new JSONObject().put("turnId",TURN).put("item",answer("answer","Here is your week.")));
            event("turn/completed",new JSONObject().put("turn",turn("completed",answer("answer","Here is your week."))));
            check(AgentViews.readableTurn(THREAD,TURN).equals("Here is your week."),"The answer text never includes the reasoning");
            drain(events);
            // The end of a chat turn re-projects the thread through the bounded display projection.
            AgentViews.saveMetadata(new JSONObject().put("id",THREAD).put("name","Week plan"));
            check(viewItem(1).getJSONArray("summary").getString(0).endsWith("中文🙂")&&viewItem(1).getLong("durationMs")==21_500L,"The bounded projection keeps the summary and its duration");
            JSONObject persisted=json(Files.readString(new File(AgentViews.ROOT,THREAD+".json").toPath())).getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(1);
            check(persisted.getJSONArray("summary").length()==1&&persisted.getLong("durationMs")==21_500L,"The checkpoint keeps them across a relay restart");
            // A full history read has the text but no timing: the duration this relay observed stays.
            AgentViews.save(thread(turn("completed",user("user","Plan my week"),thought("thought","**Weighing the week** 中文🙂"),answer("answer","Here is your week."))));
            check(viewItem(1).getLong("durationMs")==21_500L&&viewItem(1).getJSONArray("summary").length()==1,"History reads keep the observed duration");
            drain(events);
        }
        reset();
        // Subscribing after the summary began: the first delta makes its item, and a late start keeps the text.
        AgentViews.save(thread(turn("inProgress",user("user","Plan my week"))));AgentViews.prepareReply(THREAD,contract,true);
        try(SessionEvents.Subscription events=AgentViews.subscribe(new JSONObject().put("threadId",THREAD))) {
            drain(events);thoughtDelta("Already thinking");
            List<JSONObject> published=drain(events);
            check(published.size()==1&&published.get(0).getString("method").equals("item/reasoning/summaryTextDelta"),"A delta before its item is still relayed once");
            check(viewItem(1).getString("type").equals("reasoning")&&viewItem(1).getJSONArray("summary").getString(0).equals("Already thinking"),"The item is made from its first delta");
            event("item/started",new JSONObject().put("turnId",TURN).put("item",thought("thought")).put("startedAtMs",7L));
            check(viewItem(1).getJSONArray("summary").getString(0).equals("Already thinking")&&viewItem(1).getLong("startedAtMs")==7L,"A late start keeps the text and adds the start time");
            drain(events);
        }
        reset();
        // A long train of thought is bounded by its own budget and never folds the answer beside it.
        String longThought="Weigh the options. ".repeat(474),longAnswer="Plan the day. ".repeat(785);
        JSONArray turns=new JSONArray();
        for(int i=0;i<5;i++)turns.put(new JSONObject().put("id","t-"+i).put("status","completed").put("items",new JSONArray().put(user("u-"+i,"Q"+i)).put(thought("r-"+i,longThought)).put(answer("a-"+i,longAnswer))));
        AgentViews.save(new JSONObject().put("id",THREAD).put("turns",turns));
        AgentViews.saveMetadata(new JSONObject().put("id",THREAD).put("name","Long thoughts"));
        JSONObject bounded=AgentViews.snapshot(THREAD);int kept=0;
        for(int i=0;i<5;i++) {
            JSONArray items=bounded.getJSONArray("turns").getJSONObject(i).getJSONArray("items");
            check(items.getJSONObject(2).getString("text").equals(longAnswer),"Reasoning never spends the answer's display budget");
            JSONObject item=items.getJSONObject(1);check(item.getString("type").equals("reasoning"),"A reasoning item stays in place when its text is not projected");
            if(item.has("summary")) {kept+=item.getJSONArray("summary").getString(0).length();check(item.getJSONArray("summary").getString(0).length()<=8_000,"One summary part is bounded");}
        }
        check(kept>0&&kept<=24_000,"Reasoning summaries share one bounded budget");
        check(bounded.getJSONArray("turns").getJSONObject(4).getJSONArray("items").getJSONObject(1).has("summary")
            &&!bounded.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(1).has("summary"),"The newest reasoning keeps its text first");
        check(bounded.optInt("historyOmittedText")==0,"Bounding reasoning is not reported as omitted conversation text");
        reset();
    }

    private static MainReplyStream groupedView(boolean nested,boolean timeFirst,boolean expanded) {
        MainReplyStream stream=stream();
        String metric="{\"n\":2,\"type\":\"block\",\"id\":\"score\",\"kind\":\"metric\",\"value\":{\"type\":\"number\",\"value\":42},\"config\":{\"group\":\"weather\",\"size\":\"medium_size\"}}";
        String time="{\"n\":3,\"type\":\"block\",\"id\":\"elapsed\",\"kind\":\"metric\",\"value\":{\"type\":\"duration\",\"value\":1000},\"config\":{\"group\":\"weather\"}}";
        if(timeFirst){metric=json(metric).put("n",3).toString();time=json(time).put("n",2).toString();}
        JSONObject props=new JSONObject().put("value",new JSONObject().put("$state","/data/blocksById/score/value"));
        if(expanded)props.put("expanded",new JSONObject().put("$bindState","/ui/views/weather/expanded"));
        stream.push("@taku.reply/1\n"+begin()+(timeFirst?line(time)+line(metric):line(metric)+line(time))
            +line("{\"n\":4,\"type\":\"element\",\"id\":\"root\",\"parent\":null,\"component\":\"Card\",\"props\":{}}")
            +line(new JSONObject().put("n",5).put("type","element").put("id","score-view").put("parent","root").put("component","Metric").put("props",props).toString())
            +line(new JSONObject().put("n",6).put("type","element").put("id","time-view").put("parent",nested?"score-view":"root").put("component","TimeDisplay").put("props",new JSONObject().put("value",new JSONObject().put("$state","/data/blocksById/elapsed/value"))).toString())+done(7));
        stream.finish("completed");return stream;
    }

    private static void groupMembership() {
        check(phase(groupedView(true,false,true)).equals("completed"),"Grouped duration details render inside their metric owner");
        check(phase(groupedView(false,false,true)).equals("failed"),"A grouped duration cannot escape the primary metric's expandable descendants");
        check(phase(groupedView(false,true,false)).equals("completed"),"A preceding time block does not create a later metric expansion owner");
        check(phase(groupedView(false,true,true)).equals("failed"),"Expansion writes require the same first group member accepted by the client");
    }

    public static void main(String[] args) throws Exception {
        contract=json(Files.readString(new File(args[0]).toPath()));
        JSONObject hashed=new JSONObject(contract.toString());hashed.remove("contractHash");hashed.remove("prompt");
        check(MainReplyStream.manifestHash(hashed).equals(contract.getString("contractHash")),"Native canonical encoding matches the real exporter manifest hash");
        incremental();segmentation();rejectedRecords();typedAndSchema();actionsAndBounds();lifecycle();liveHistory();canonicalCatchup();reviewedSources();reasoningRelay();groupMembership();
        System.out.println("MainReplyHostTest: "+assertions+" assertions passed");
    }
}
