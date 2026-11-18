package org.agentos.assistant;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MatterRuntimeConcurrencyTest {
    private static final String THREAD=id("thread");
    private static int assertions;
    private static String id(String text) {return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();}
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
    private static void await(CountDownLatch latch) throws Exception {if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Controlled interleaving blocked");}
    private static <T> FutureTask<T> fork(Callable<T> action) {FutureTask<T> task=new FutureTask<>(action);Thread worker=new Thread(task,"matter-host-worker");worker.setDaemon(true);worker.start();return task;}
    private static <T> T result(FutureTask<T> task) throws Exception {return task.get(5,TimeUnit.SECONDS);}
    private static final class Fixture {
        final Context context;
        final MatterStore store;
        final SourceAdapters adapters;
        final ManualExecutor io=new ManualExecutor();
        final MatterRuntime runtime;
        final PanelRegistry panels;
        Fixture() throws Exception {this(new Context(Files.createTempDirectory("matter-runtime-").toFile()));}
        Fixture(Context context) {this.context=context;store=new MatterStore(context);adapters=new SourceAdapters(context);runtime=new MatterRuntime(context,store,adapters,io);panels=new PanelRegistry(context,runtime,store);}
        void present(String key,String title) throws Exception {runtime.observe(THREAD,key,request(key,title),"presentation-"+title,new JSONObject().put("kind","host-evidence"));}
        JSONObject snapshot(String key) throws Exception {return runtime.read(THREAD+"/"+key).getJSONObject("snapshot");}
        JSONObject pin(String key) throws Exception {return panels.call("pin",new JSONObject().put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/"+key)).put("requestId",UUID.randomUUID().toString()));}
        void unpin(String key) throws Exception {JSONObject panel=store.panelForMatter(THREAD+"/"+key);panels.call("unpin",new JSONObject().put("panelId",panel.getString("panelId")).put("expectedListRevision",panels.call("list",new JSONObject()).getLong("listRevision")).put("requestId",UUID.randomUUID().toString()));}
        JSONObject command(String key,String name) throws Exception {
            JSONObject snapshot=snapshot(key);return new JSONObject().put("matterId",THREAD+"/"+key).put("requestId",id(name))
                    .put("expectedRevision",snapshot.getLong("revision")).put("actionRef",snapshot.getJSONObject("request").getJSONArray("actions").getJSONObject(0).getString("actionRef")).put("values",new JSONObject());
        }
    }
    private static JSONObject request(String key,String title) throws Exception {
        JSONObject block=new JSONObject().put("id","body").put("kind","text").put("title","").put("text",title)
                .put("value",new JSONObject().put("type","text").put("value",title)).put("image","").put("url","").put("items",new JSONArray()).put("config",new JSONObject());
        return new JSONObject().put("version",2).put("key",key).put("mode","card").put("title",title).put("summary","")
                .put("blocks",new JSONArray().put(block)).put("actions",new JSONArray().put(new JSONObject().put("id","followup").put("label","Continue").put("reply","Continue").put("style","primary")));
    }
    private static JSONObject primary(String key,long revision,String phase,String title) throws Exception {
        JSONObject request=request(key,title);
        JSONObject view=new JSONObject().put("catalogVersion","taku-ui-1").put("signature","native-signature")
                .put("spec",new JSONObject().put("root","root").put("elements",new JSONObject().put("root",new JSONObject()
                        .put("type","Card").put("props",new JSONObject()).put("children",new JSONArray()))))
                .put("metrics",new JSONObject().put("level","primary").put("ms",0).put("inputTokens",JSONObject.NULL).put("llmUsage",JSONObject.NULL));
        return new JSONObject().put("schema","taku.main-reply/1").put("id",key.matches("reply-[a-f0-9]{32}")?key:"reply-"+id(key).replace("-","")).put("key",key).put("revision",revision)
                .put("readableText",title+"\nContinue").put("lifecycle",new JSONObject().put("phase",phase))
                .put("provenance",new JSONObject().put("kind","primary-assistant-reply").put("threadId",THREAD).put("turnId","turn")
                        .put("anchorOrdinal",1).put("anchorHash","b".repeat(64)).put("contractHash","a".repeat(64)).put("sourceItemIds",new JSONArray().put("item-"+key)))
                .put("card",new JSONObject().put("request",request).put("view",view).put("assembly",new JSONObject()));
    }
    private static JSONObject primarySource(JSONObject reply) throws Exception {
        return new JSONObject().put("kind","primary").put("threadId",THREAD).put("replyId",reply.getString("id")).put("turnId",reply.getJSONObject("provenance").getString("turnId"))
                .put("revision",reply.getLong("revision")).put("contractHash",reply.getJSONObject("provenance").getString("contractHash"));
    }
    private interface Checked {void run() throws Exception;}
    private static void rejects(String code,Checked action) throws Exception {
        try {action.run();throw new AssertionError("Expected "+code);}catch(IOException expected) {check(code.equals(expected.getMessage()),"Expected "+code+", got "+expected.getMessage());}
    }
    private static void primaryAdmissionAndPreferences() throws Exception {
        Fixture f=new Fixture();JSONObject partial=primary("primary",1,"streaming","Known first row");
        f.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",partial));
        f.runtime.observeReply(THREAD,"turn",partial);
        check(f.store.get("matters",THREAD+"/primary")==null,"A streaming primary reply minted executable Matter");
        JSONObject pin=new JSONObject().put("source",primarySource(partial)).put("requestId",id("partial-pin"));
        rejects("REPLY_INCOMPLETE",()->f.panels.call("pin",pin));
        check(f.panels.call("list",new JSONObject()).getJSONArray("items").length()==0,"Incomplete Pin changed the directory");
        rejects("INVALID_ARGUMENT",()->f.panels.call("pin",new JSONObject(pin.toString()).put("reply",partial)));
        rejects("INVALID_ARGUMENT",()->f.panels.call("pin",new JSONObject(pin.toString()).put("source",new JSONObject().put("kind","primary").put("request",request("primary","Forged facts")))));
        rejects("PRIMARY_REPLY_SOURCE_OWNED",()->f.present("primary","An ordinary card cannot claim a pending primary key"));
        for(String phase:List.of("sealed","failed","interrupted")) {
            JSONObject incomplete=primary("state-"+phase,2,phase,"Retained prefix");f.runtime.observeReply(THREAD,"turn",incomplete);
            check(f.store.get("matters",THREAD+"/state-"+phase)==null,"Non-completed "+phase+" reply minted capabilities");
            f.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",incomplete));
            rejects("REPLY_INCOMPLETE",()->f.panels.call("pin",new JSONObject().put("source",primarySource(incomplete)).put("requestId",id("pin-"+phase))));
        }
        JSONObject completed=primary("primary",4,"completed","Canonical completed facts");
        JSONObject thread=new JSONObject().put("id",THREAD).put("turns",new JSONArray().put(new JSONObject().put("id","turn")
                .put("mainReplies",new JSONObject().put(completed.getString("id"),completed))));
        f.runtime.observeThread(thread);JSONObject admitted=f.snapshot("primary");
        JSONObject provenance=admitted.getJSONObject("meta").getJSONObject("provenance");
        check(provenance.getString("kind").equals("primary-assistant-reply")&&provenance.getString("replyId").equals(completed.getString("id"))
                &&provenance.getLong("replyRevision")==4&&provenance.getString("contractHash").equals("a".repeat(64)),"Capabilities lost native primary provenance");
        String actionRef=admitted.getJSONObject("request").getJSONArray("actions").getJSONObject(0).getString("actionRef");
        check(!completed.getJSONObject("card").getJSONObject("request").getJSONArray("actions").getJSONObject(0).has("actionRef"),"Admission mutated the canonical primary snapshot");
        f.runtime.observeThread(thread);f.runtime.observeReply(THREAD,"turn",partial);
        JSONObject replayed=f.snapshot("primary");
        check(replayed.getLong("revision")==admitted.getLong("revision")&&replayed.getJSONObject("request").getJSONArray("actions").getJSONObject(0).getString("actionRef").equals(actionRef),"Duplicate or stale native reply renewed capabilities");
        f.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",completed));
        rejects("SOURCE_CHANGED",()->f.panels.call("pin",pin));
        rejects("SOURCE_CHANGED",()->f.panels.call("pin",new JSONObject().put("source",primarySource(completed).put("contractHash","c".repeat(64))).put("requestId",id("wrong-hash"))));
        JSONObject pinned=f.pin("primary");
        check(pinned.getBoolean("created")&&f.snapshot("primary").getJSONObject("pin").getString("panelId").equals(pinned.getJSONObject("panel").getString("panelId")),"Pin did not publish native ownership");
        check(f.panels.call("list",new JSONObject()).getJSONArray("items").getJSONObject(0).getString("title").equals("Canonical completed facts"),"Pinned primary lost its native title");
        Fixture restarted=new Fixture(f.context);
        check(restarted.snapshot("primary").getJSONObject("presentation").getJSONObject("reply").similar(completed),"Trusted completed primary did not restore offline");
        check(!restarted.pin("primary").getBoolean("created"),"Offline retained Pin duplicated ownership");
        f.adapters.reader=source->new JSONObject().put("state","available").put("text","External source must not replace main facts").put("provenance",f.adapters.identity("memory"));
        f.runtime.nativeAction(new JSONObject().put("threadId",THREAD).put("key","primary").put("action","attachSource")
                .put("source",new JSONObject().put("kind","memory").put("resourceId","record").put("blockId","body")));f.io.take().run();
        JSONObject overlay=f.snapshot("primary");
        check(overlay.getJSONObject("request").getJSONArray("blocks").getJSONObject(0).getString("text").equals("Canonical completed facts")
                &&overlay.getJSONObject("presentation").getJSONObject("reply").similar(completed),"A Matter source replaced primary facts or its supplied view");
        rejects("PRIMARY_REPLY_SOURCE_OWNED",()->f.present("primary","Ordinary replacement"));
        JSONObject newer=primary("primary",5,"completed","New native revision");f.runtime.observeReply(THREAD,"turn",newer);
        JSONObject updated=f.snapshot("primary");
        check(updated.getJSONObject("meta").getJSONObject("provenance").getLong("replyRevision")==5
                &&!updated.getJSONObject("request").getJSONArray("actions").getJSONObject(0).getString("actionRef").equals(actionRef),"A new native revision retained stale capability provenance");
        f.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",completed));
        rejects("SOURCE_CHANGED",()->f.panels.call("pin",new JSONObject().put("source",primarySource(completed)).put("requestId",id("stale-native-read"))));
        f.present("ordinary","Ordinary tool card");f.pin("ordinary");
        check(f.snapshot("ordinary").getJSONObject("request").getString("title").equals("Ordinary tool card"),"Primary admission changed ordinary Matter behavior");
        Fixture direct=new Fixture();direct.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",completed));
        JSONObject created=direct.panels.call("pin",new JSONObject().put("source",primarySource(completed)).put("requestId",id("direct-native-admission")));
        check(created.getBoolean("created")&&direct.store.get("matters",THREAD+"/primary")!=null,"Pin required a GUI to admit the native reply first");
    }
    private static void oldPrimarySnapshotBackfill() throws Exception {
        Fixture original=new Fixture();String key="pre-v2-primary",matterId=THREAD+"/"+key;
        JSONObject trusted=primary(key,4,"completed","Original native presentation");
        trusted.getJSONObject("card").getJSONObject("view").getJSONObject("spec").getJSONObject("elements").getJSONObject("root")
                .getJSONObject("props").put("title","Original supplied layout");
        original.runtime.observeReply(THREAD,"turn",trusted);
        JSONObject old=original.store.get("matters",matterId);old.getJSONObject("meta").getJSONObject("provenance").remove("replyPhase");
        original.store.put("matters",matterId,old);original.store.remove("primary_snapshots",matterId);
        Fixture upgraded=new Fixture(original.context);JSONObject before=upgraded.snapshot(key);
        check(!new File(original.context.getFilesDir(),"agent-ui-panels").exists(),"Old-Matter regression accidentally relies on a legacy card file");
        rejects("SOURCE_UNAVAILABLE",()->upgraded.pin(key));
        for(String mismatch:List.of("request","turnId","anchorHash","contractHash","id","revision","phase")) {
            JSONObject changed=new JSONObject(trusted.toString());
            if(mismatch.equals("request"))changed.getJSONObject("card").getJSONObject("request").put("title","Changed facts");
            else if(mismatch.equals("turnId"))changed.getJSONObject("provenance").put("turnId","changed-turn");
            else if(mismatch.endsWith("Hash"))changed.getJSONObject("provenance").put(mismatch,"c".repeat(64));
            else if(mismatch.equals("id"))changed.put("id","reply-"+id("other-primary-reply").replace("-",""));
            else if(mismatch.equals("revision"))changed.put("revision",3);
            else changed.getJSONObject("lifecycle").put("phase","streaming");
            upgraded.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",changed));
            rejects(mismatch.equals("phase")?"REPLY_INCOMPLETE":"SOURCE_CHANGED",()->upgraded.panels.call("pin",new JSONObject()
                    .put("source",primarySource(changed)).put("requestId",id("old-primary-"+mismatch))));
            check(upgraded.store.get("primary_snapshots",matterId)==null&&upgraded.store.panelForMatter(matterId)==null,"Rejected source backfilled a snapshot or Panel");
        }
        upgraded.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",trusted));
        JSONObject pinned=upgraded.pin(key).getJSONObject("panel"),after=upgraded.snapshot(key);
        check(after.getJSONObject("presentation").getString("kind").equals("primary")&&after.getJSONObject("presentation").getJSONObject("reply").similar(trusted),"Equal-revision Pin lost the original primary presentation");
        check(after.getLong("revision")==before.getLong("revision")&&after.getJSONObject("request").similar(before.getJSONObject("request")),"Snapshot backfill renewed capabilities or rewrote the Matter");
        Fixture reopened=new Fixture(original.context);
        check(reopened.snapshot(key).getJSONObject("presentation").getJSONObject("reply").similar(trusted),"Backfilled primary presentation did not survive reopen");
        check(reopened.pin(key).getJSONObject("panel").getString("panelId").equals(pinned.getString("panelId")),"Offline Pin failed to reuse the backfilled source");
        upgraded.runtime.observeReply(THREAD,"turn",primary(key,3,"completed","Older facts"));
        check(upgraded.snapshot(key).getJSONObject("presentation").getJSONObject("reply").similar(trusted),"Older observation replaced the backfilled presentation");
    }
    private static void rejectsPendingPrimary(Fixture f,JSONObject pending) throws Exception {
        String key=pending.getString("key");
        rejects("INVALID_ARGUMENT",()->f.panels.call("pin",new JSONObject().put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/"+key).put("request",request(key,"Client replacement"))).put("requestId",id("forged-"+key))));
        rejects("SOURCE_UNAVAILABLE",()->f.panels.call("pin",new JSONObject().put("source",primarySource(pending)).put("requestId",id("missing-"+key))));
        rejects("PRIMARY_REPLY_SOURCE_OWNED",()->f.present(key,"Client replacement"));
        JSONObject forged=new JSONObject(pending.getJSONObject("provenance").toString()).put("replyId",pending.getString("id")).put("replyRevision",1).put("replyPhase","completed");
        rejects("INVALID_PRIMARY_SOURCE",()->f.runtime.observe(THREAD,key,request(key,"Forged completed source"),"client",forged));
        rejects("MATTER_UNAVAILABLE",()->f.runtime.read(THREAD+"/"+key));
        check(f.store.get("matters",THREAD+"/"+key)==null&&f.panels.call("list",new JSONObject()).getJSONArray("items").length()==0,"A lost primary cache admitted client content or capabilities");
    }
    private static void reservedPrimaryNamespace() throws Exception {
        Fixture f=new Fixture();String key="reply-"+"f".repeat(32);JSONObject pending=primary(key,1,"streaming","Pending native reply");
        f.runtime.observeReply(THREAD,"turn",pending);
        rejectsPendingPrimary(new Fixture(f.context),pending);
        for(int i=0;i<=200;i++) {
            String other="reply-"+String.format("%032x",i);
            f.runtime.observeReply(THREAD,"turn",primary(other,1,"streaming","Unkept native prefix"));
        }
        rejectsPendingPrimary(f,pending);
        f.present("reply-human-note","Ordinary matter outside native namespace");f.pin("reply-human-note");
        check(f.snapshot("reply-human-note").getJSONObject("request").getString("title").equals("Ordinary matter outside native namespace"),"Reserving native primary IDs rejected an ordinary key");
    }
    private static void primaryCapabilityRevocation() throws Exception {
        for(String phase:List.of("failed","interrupted")) {
            Fixture f=new Fixture();String key="reply-"+(phase.equals("failed")?"a":"b").repeat(32);
            JSONObject completed=primary(key,4,"completed","Admitted native facts");f.runtime.observeReply(THREAD,"turn",completed);
            f.pin(key);
            AtomicInteger publishes=new AtomicInteger();
            f.runtime.setSubmit(input->{publishes.incrementAndGet();return new JSONObject().put("requestId",input.getString("requestId")).put("state","notSent");});
            JSONObject sent=f.command(key,phase+"-receipt");f.runtime.dispatch(sent);
            JSONObject old=f.command(key,phase+"-blocked");String oldRef=old.getString("actionRef");
            JSONObject failed=primary(key,5,phase,"Native failure prefix");failed.getJSONObject("lifecycle").put("code","SOURCE_CHANGED");
            f.runtime.observeReply(THREAD,"turn",failed);JSONObject revoked=f.snapshot(key);
            JSONObject source=revoked.getJSONObject("meta").getJSONObject("provenance");
            check(source.getLong("replyRevision")==5&&source.getString("replyPhase").equals(phase)&&source.getString("replyCode").equals("SOURCE_CHANGED"),"Newer native lifecycle/revision was not persisted");
            check(!revoked.getJSONObject("request").getJSONArray("actions").getJSONObject(0).has("actionRef"),"A revoked primary read reminted capabilities");
            check(revoked.getJSONObject("request").getString("title").equals("Admitted native facts")&&revoked.getJSONObject("commands").has(sent.getString("requestId")),"Revocation discarded admitted facts or receipts");
            rejects("REPLY_INCOMPLETE",()->f.runtime.dispatch(old));
            check(f.runtime.dispatch(sent).getString("state").equals("notSent")&&publishes.get()==1,"Revocation lost an existing receipt or replayed its effect");
            check(f.store.panelForMatter(THREAD+"/"+key)!=null,"Revocation discarded existing Pin ownership");
            check(f.snapshot(key).getJSONObject("presentation").getJSONObject("reply").getLong("revision")==4,"Revocation discarded the last completed trusted content");
            Fixture restarted=new Fixture(f.context);JSONObject durable=restarted.snapshot(key);
            check(durable.getJSONObject("meta").getJSONObject("provenance").getLong("replyRevision")==5
                    &&!durable.getJSONObject("request").getJSONArray("actions").getJSONObject(0).has("actionRef"),"Restart restored revoked primary capabilities");
            rejects("REPLY_INCOMPLETE",()->restarted.runtime.dispatch(old));
            check(restarted.store.panelForMatter(THREAD+"/"+key)!=null&&!durable.getJSONObject("pin").getBoolean("canPin"),"An older snapshot overrode durable revocation");
            restarted.unpin(key);
            check(restarted.store.panelForMatter(THREAD+"/"+key)==null,"Unpin required unavailable source admission");
            rejects("SOURCE_CHANGED",()->restarted.pin(key));
            restarted.runtime.observeReply(THREAD,"turn",completed);
            check(!restarted.snapshot(key).getJSONObject("request").getJSONArray("actions").getJSONObject(0).has("actionRef"),"A stale native completion restored revoked capabilities after restart");
            long revision=restarted.snapshot(key).getLong("revision");restarted.runtime.observeReply(THREAD,"turn",failed);
            check(restarted.snapshot(key).getLong("revision")==revision,"Replaying durable revocation changed its revision");
            rejects("SOURCE_CHANGED",()->restarted.pin(key));
            JSONObject revalidated=primary(key,6,"completed","Revalidated native facts");restarted.runtime.observeReply(THREAD,"turn",revalidated);
            JSONObject current=restarted.snapshot(key);
            check(current.getJSONObject("meta").getJSONObject("provenance").getLong("replyRevision")==6&&current.getJSONObject("meta").getJSONObject("provenance").getString("replyPhase").equals("completed")
                    &&!current.getJSONObject("request").getJSONArray("actions").getJSONObject(0).getString("actionRef").equals(oldRef),"A later native completion did not mint fresh capabilities");
            JSONObject oldAtCurrent=new JSONObject(old.toString()).put("requestId",id(phase+"-old-ref-after-completion")).put("expectedRevision",current.getLong("revision"));
            rejects("UNKNOWN_ACTION_REF",()->restarted.runtime.dispatch(oldAtCurrent));
            restarted.runtime.setSubmit(input->{publishes.incrementAndGet();return new JSONObject().put("requestId",input.getString("requestId")).put("state","notSent");});
            check(restarted.runtime.dispatch(restarted.command(key,phase+"-new-completion")).getString("state").equals("notSent")&&publishes.get()==2,"A later completed native reply could not dispatch");
            check(restarted.runtime.dispatch(sent).getString("state").equals("notSent")&&publishes.get()==2,"Restored native completion lost durable receipt idempotence");
            restarted.pin(key);
            JSONObject offline=new Fixture(f.context).snapshot(key);
            check(offline.getJSONObject("presentation").getJSONObject("reply").getLong("revision")==6&&offline.getJSONObject("pin").getString("panelId").length()==36,"A revalidated completion did not restore retained content offline");
        }
    }
    private static void dispatchInterleaving() throws Exception {
        Fixture f=new Fixture();f.present("card","Before");
        CountDownLatch publishing=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger publishes=new AtomicInteger();
        AgentGatewaySubmissions submissions=new AgentGatewaySubmissions(new File(f.context.getFilesDir(),"agent-ui-receipts"));
        f.runtime.setSubmit(input->{
            check(!Thread.holdsLock(f.runtime),"Dispatch called Submit under the runtime monitor");
            return submissions.submit(input.getString("requestId"),THREAD,input.getString("text"),()->check(!Thread.holdsLock(submissions),"Submission preflight holds its monitor"),before->{check(!Thread.holdsLock(submissions),"Submission publisher holds its monitor");publishes.incrementAndGet();publishing.countDown();await(release);return "";});
        });
        JSONObject command=f.command("card","dispatch");FutureTask<JSONObject> sending=fork(()->f.runtime.dispatch(command));await(publishing);
        JSONObject replay=result(fork(()->f.runtime.dispatch(command)));
        check("deliveryUnknown".equals(replay.getString("state")),"In-flight dispatch lost its durable fence");
        JSONObject sameEffect=new JSONObject(command.toString()).put("requestId",id("same-effect"));
        check(result(fork(()->f.runtime.dispatch(sameEffect))).getString("requestId").equals(command.getString("requestId")),"Effect deduplication published twice");
        JSONObject changed=new JSONObject(command.toString()).put("values",new JSONObject().put("body","invalid"));
        try {f.runtime.dispatch(changed);throw new AssertionError("Payload reuse allowed");}catch(IOException expected) {check(expected.getMessage().equals("REQUEST_ID_REUSED"),"Payload mismatch lost its error");}
        // The same-thread audio operation waits for publication without monopolizing MatterRuntime.
        FutureTask<JSONObject> audio=fork(()->submissions.steer(id("audio"),THREAD,"local-media-session","start",()->f.runtime.read(THREAD+"/card"),JSONObject::new));
        result(fork(()->{f.present("card","Concurrent source revision");return null;}));
        release.countDown();check(result(sending).getString("state").equals("queued"),"Dispatch receipt missing");
        check(result(audio).getString("state").equals("accepted"),"Sequenced audio preflight and dispatch could not finish");
        JSONObject finalSnapshot=f.snapshot("card");
        check(finalSnapshot.getJSONObject("request").getString("title").equals("Concurrent source revision"),"Dispatch restored a stale projection");
        check(finalSnapshot.getJSONObject("commands").getJSONObject(command.getString("requestId")).getString("state").equals("queued")&&publishes.get()==1,"Dispatch finalization lost current command state");
        Fixture restarted=new Fixture(f.context);
        restarted.runtime.setSubmit(input->{throw new AssertionError("A restarted dispatch replayed");});
        check(restarted.runtime.dispatch(command).getString("state").equals("queued"),"Restart did not reuse durable receipt");
    }
    private static void sequencedPreflightInterleaving() throws Exception {
        Fixture f=new Fixture();f.present("lock-cycle","Lock cycle");
        AgentGatewaySubmissions submissions=new AgentGatewaySubmissions(new File(f.context.getFilesDir(),"agent-ui-receipts"));
        CountDownLatch audioPreflight=new CountDownLatch(1),dispatchPublishing=new CountDownLatch(1);
        f.runtime.setSubmit(input->{
            check(!Thread.holdsLock(f.runtime),"Dispatch waited for thread sequencing under its runtime monitor");
            dispatchPublishing.countDown();
            return submissions.submit(input.getString("requestId"),THREAD,input.getString("text"),()->{},before->"");
        });
        FutureTask<JSONObject> audio=fork(()->submissions.steer(id("sequenced-audio"),THREAD,"local-media-session","start",()->{
            check(!Thread.holdsLock(submissions),"Sequenced audio preflight holds the global submissions monitor");
            audioPreflight.countDown();await(dispatchPublishing);f.runtime.read(THREAD+"/lock-cycle");
        },JSONObject::new));
        await(audioPreflight);JSONObject command=f.command("lock-cycle","sequenced-dispatch");
        FutureTask<JSONObject> dispatch=fork(()->f.runtime.dispatch(command));
        check(result(audio).getString("state").equals("accepted")&&result(dispatch).getString("state").equals("queued"),"Per-thread sequencing recreated the runtime/preflight lock cycle");
    }
    private static void scheduledInterleaving() throws Exception {
        Fixture f=new Fixture();f.present("scheduled","Before schedule");String matter=THREAD+"/scheduled",ruleId=matter+"/daily";
        JSONObject rule=new JSONObject().put("id","daily").put("storageId",ruleId).put("matterId",matter).put("kind","price-below").put("reply","Check current information").put("paused",false);
        f.store.put("rules",ruleId,rule);
        String occurrenceId=ruleId+"/episode",request=id("occurrence");
        f.store.put("occurrences",occurrenceId,new JSONObject().put("id",occurrenceId).put("ruleId",ruleId).put("matterId",matter).put("requestId",request)
                .put("payload",rule).put("createdAt",System.currentTimeMillis()).put("expectedAt",System.currentTimeMillis()).put("state","claimed").put("terminal",false));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger publishes=new AtomicInteger();
        f.runtime.setSubmit(input->{check(!Thread.holdsLock(f.runtime),"Scheduled Submit held the runtime monitor");publishes.incrementAndGet();entered.countDown();await(release);return new JSONObject().put("requestId",request).put("state","notSent");});
        f.io.take().run(); // Recovery queues a claimed occurrence after committing its scan.
        FutureTask<Void> delivery=fork(()->{f.io.take().run();return null;});await(entered);
        check(f.store.get("commands",request).getJSONObject("receipt").getString("state").equals("deliveryUnknown"),"Schedule has no durable command reservation");
        f.runtime.recover();f.io.take().run(); // Must not reconcile or requeue a live publication.
        f.present("scheduled","Updated during delivery");
        JSONObject live=f.store.get("rules",ruleId);live.put("paused",true);f.store.put("rules",ruleId,live);
        release.countDown();result(delivery);
        check(publishes.get()==1&&f.store.get("occurrences",occurrenceId).getString("state").equals("delivered"),"Occurrence published twice");
        check(f.store.get("rules",ruleId).getBoolean("paused"),"Occurrence overwrote a concurrent pause");
        check(f.snapshot("scheduled").getJSONObject("request").getString("title").equals("Updated during delivery"),"Scheduled receipt restored a stale matter");
        Fixture crashed=new Fixture();crashed.present("unknown","Unknown publication");String unknownMatter=THREAD+"/unknown",unknownRule=unknownMatter+"/daily";
        JSONObject payload=new JSONObject(rule.toString()).put("matterId",unknownMatter).put("storageId",unknownRule);crashed.store.put("rules",unknownRule,payload);
        crashed.store.put("occurrences",unknownRule+"/episode",new JSONObject().put("id",unknownRule+"/episode").put("ruleId",unknownRule).put("matterId",unknownMatter).put("requestId",id("unknown-occurrence"))
                .put("payload",payload).put("createdAt",System.currentTimeMillis()).put("state","publishing").put("terminal",false));
        crashed.runtime.setSubmit(input->{throw new AssertionError("Ambiguous schedule replayed");});crashed.io.drain();
        check(crashed.store.get("occurrences",unknownRule+"/episode").getString("state").equals("unknown-outcome"),"Restart did not preserve unknown delivery");
    }
    private static void retentionAndReplay() throws Exception {
        Fixture f=new Fixture();f.present("old","Old transient card");AtomicInteger publishes=new AtomicInteger();
        f.runtime.setSubmit(input->{publishes.incrementAndGet();return new JSONObject().put("requestId",input.getString("requestId")).put("state","notSent");});
        JSONObject old=f.command("old","old-request");f.runtime.dispatch(old);
        f.present("kept","Pinned panel");f.pin("kept");
        JSONObject draftRequest=inputRequest("draft","Edited draft");
        f.runtime.observe(THREAD,"draft",draftRequest,"native-draft",new JSONObject().put("kind","host-evidence"));
        f.panels.cardView("apply",new JSONObject().put("matterId",THREAD+"/draft").put("expectedUiRevision",0).put("requestId",id("retain-draft"))
                .put("changes",new JSONArray().put(new JSONObject().put("kind","field").put("id","notes").put("value","Keep my draft"))));
        f.present("paused","Paused rule");f.store.put("rules",THREAD+"/paused/rule",new JSONObject().put("matterId",THREAD+"/paused").put("id","rule").put("storageId",THREAD+"/paused/rule").put("kind","price-below").put("paused",true));
        f.present("leased","Visible subscription");MatterRuntime.Listener listener=event->{};f.runtime.watch("lease",new JSONArray().put(THREAD+"/leased"),listener);
        f.present("reading","In-flight operation");AutoCloseable reading=f.runtime.retain(THREAD+"/reading");
        f.present("pending","Unresolved command");JSONObject pending=f.command("pending","unresolved");
        f.runtime.setSubmit(input->{throw new IOException("transport disconnected");});try {f.runtime.dispatch(pending);}catch(IOException expected) { }
        for(int i=0;i<320;i++)f.present("transient-"+i,"Transient "+i);
        check(f.store.count("matters")<=206,"Transient history grew beyond the shared retention policy");
        check(f.store.get("matters",THREAD+"/old")==null,"Old transient matter was not recycled");
        for(String key:List.of("kept","draft","paused","leased","reading","pending"))check(f.store.get("matters",THREAD+"/"+key)!=null,"Owned matter reclaimed: "+key);
        check(f.store.get("commands",old.getString("requestId"))==null,"Evicted projection left live command history");
        check(f.runtime.dispatch(old).getString("state").equals("notSent")&&publishes.get()==1,"Old receipt replay published after eviction");
        f.present("old","Recreated card");
        check(f.runtime.dispatch(old).getString("state").equals("notSent")&&publishes.get()==1,"Old receipt replay published after recreation");
        JSONObject wrongMatter=new JSONObject(old.toString()).put("matterId",THREAD+"/kept");
        try {f.runtime.dispatch(wrongMatter);throw new AssertionError("Archived receipt accepted a different matter");}catch(IOException expected) {check(expected.getMessage().equals("REQUEST_ID_REUSED"),"Archived matter identity was not checked");}
        JSONObject wrongPayload=new JSONObject(old.toString()).put("actionRef","changed");
        try {f.runtime.dispatch(wrongPayload);throw new AssertionError("Archived receipt accepted a different payload");}catch(IOException expected) {check(expected.getMessage().equals("REQUEST_ID_REUSED"),"Archived payload was not checked");}
        Fixture restarted=new Fixture(f.context);check(restarted.runtime.dispatch(old).getString("state").equals("notSent"),"Archived receipt did not survive restart");
        f.unpin("kept");reading.close();f.runtime.unwatch("lease",listener);
        for(int i=320;i<550;i++)f.present("transient-"+i,"Transient "+i);
        check(f.store.get("matters",THREAD+"/kept")==null&&f.store.get("matters",THREAD+"/leased")==null&&f.store.get("matters",THREAD+"/reading")==null,"Unkeep/close did not recover capacity");
    }
    private static void sourceRetention() throws Exception {
        Fixture f=new Fixture();
        for(String key:List.of("shared-old","shared-kept","active-audio","active-task","reading-source","fenced"))f.present(key,key);
        f.pin("shared-kept");
        f.store.put("sources","shared",new JSONObject().put("id","shared").put("kind","memory"));
        f.store.bind(THREAD+"/shared-old","shared","body");f.store.bind(THREAD+"/shared-kept","shared","body");
        f.store.put("sources","active-audio",new JSONObject().put("id","active-audio").put("kind","audio-session").put("matterId",THREAD+"/active-audio").put("observation",new JSONObject().put("status","paused")));
        f.store.put("sources","active-task",new JSONObject().put("id","active-task").put("kind","task-receipt").put("matterId",THREAD+"/active-task"));
        f.store.put("sources","reading",new JSONObject().put("id","reading").put("kind","memory"));f.store.bind(THREAD+"/reading-source","reading","body");
        f.store.put("occurrences","fence",new JSONObject().put("id","fence").put("ruleId","missing").put("matterId",THREAD+"/fenced").put("state","unknown-outcome").put("terminal",true));
        f.store.put("sources","finished-task",new JSONObject().put("id","finished-task").put("kind","task-receipt").put("matterId",THREAD+"/shared-kept").put("observation",new JSONObject().put("terminal",true)));
        for(int i=0;i<240;i++)f.store.put("sources","orphan-"+i,new JSONObject().put("id","orphan-"+i).put("kind","memory"));
        MatterRetention.reclaim(f.store,Set.of(THREAD+"/shared-kept"),Set.of("reading"),256,256,0,1);
        check(f.store.count("sources")==4,"Inactive orphan sources consumed capacity");
        check(f.store.get("sources","shared")!=null,"Shared source lost its retained reference");
        check(f.store.get("sources","finished-task")==null,"Kept matter permanently retained an inactive orphan receipt source");
        for(int i=0;i<250;i++)f.present("source-churn-"+i,"Churn "+i);
        check(f.store.get("matters",THREAD+"/active-audio")!=null&&f.store.get("matters",THREAD+"/active-task")!=null&&f.store.get("matters",THREAD+"/fenced")!=null,"Active source or unresolved fence was reclaimed");
        // A full protected store reports the true cap and permits existing updates.
        Fixture full=new Fixture();
        for(int i=0;i<256;i++) {String key="protected-"+i;full.present(key,key);full.pin(key);}
        try {full.present("overflow","Overflow");throw new AssertionError("Protected store silently evicted data");}catch(IOException expected) {check(expected.getMessage().equals("MATTER_STORAGE_FULL"),"Wrong capacity error");}
        JSONObject incoming=primary("new-pinned",1,"completed","Cannot admit");
        full.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",incoming));
        rejects("MATTER_STORAGE_FULL",()->full.panels.call("pin",new JSONObject().put("source",primarySource(incoming)).put("requestId",id("full-store-pin"))));
        check(full.store.get("matters",THREAD+"/new-pinned")==null&&full.store.get("primary_snapshots",THREAD+"/new-pinned")==null&&full.store.panelForMatter(THREAD+"/new-pinned")==null,"Failed admission persisted a partial Panel or trusted source");
        File legacy=new File(full.context.getFilesDir(),"agent-ui-panels");legacy.mkdirs();
        Files.writeString(new File(legacy,THREAD+"__detached.json").toPath(),new JSONObject().put("threadId",THREAD).put("key","detached").put("kept",false)
                .put("ui",new JSONObject().put("fields",new JSONObject().put("note",new JSONObject().put("draft","Original draft")))).toString());
        full.runtime.recover();full.io.drain();
        JSONObject detached=full.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/detached"));
        JSONObject replacement=primary("detached",1,"completed","Cannot admit");full.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",replacement));
        rejects("MATTER_STORAGE_FULL",()->full.panels.call("pin",new JSONObject().put("source",primarySource(replacement)).put("requestId",id("full-detached-pin"))));
        JSONObject unchanged=full.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/detached"));
        check(unchanged.similar(detached)&&unchanged.getJSONArray("review").getJSONObject(0).getString("value").equals("Original draft"),"Failed admission changed the archived draft");
        for(int i=0;i<5;i++)check(!full.pin("protected-0").getBoolean("created"),"Existing ownership consumed another capacity slot");
        full.present("protected-0","Existing update still works");
        check(full.store.count("matters")==256,"Capacity failure deleted protected entries");
        full.unpin("protected-0");full.present("recovered","Recovered capacity");
        check(full.store.get("matters",THREAD+"/recovered")!=null,"Unkeep did not recover full protected capacity");
        Fixture sourceFull=new Fixture();sourceFull.present("source-owner","Protected source owner");
        for(int i=0;i<256;i++) {String source="owned-source-"+i;sourceFull.store.put("sources",source,new JSONObject().put("id",source).put("kind","memory"));sourceFull.store.bind(THREAD+"/source-owner",source,"block-"+i);}
        try {
            sourceFull.store.transaction(()->{MatterRetention.reclaim(sourceFull.store,Set.of(THREAD+"/source-owner"),Set.of(),256,256,0,1);return null;});
            throw new AssertionError("Protected shared sources were silently deleted");
        }catch(IOException expected) {check(expected.getMessage().equals("MATTER_STORAGE_FULL"),"Wrong protected-source capacity error");}
        check(sourceFull.store.count("sources")==256,"A source capacity failure deleted owned observations");
    }
    private static void archiveFailureOrdering() throws Exception {
        Fixture f=new Fixture();f.present("archive","Archive ordering");String request=id("archive-failure");
        JSONObject command=new JSONObject().put("id",request).put("matterId",THREAD+"/archive").put("terminal",true).put("payload","same-payload")
                .put("receipt",new JSONObject().put("requestId",request).put("state","confirmed"));f.store.put("commands",request,command);
        File directory=new File(f.context.getFilesDir(),"agent-ui-receipts");Files.writeString(directory.toPath(),"a file blocks receipt directory creation");
        try {f.store.archiveCommand(command);throw new AssertionError("Receipt write failure was ignored");}catch(IOException expected) { }
        check(f.store.get("commands",request)!=null,"Failed archive deleted the live duplicate fence");
        Files.delete(directory.toPath());directory.mkdirs();
        // A crash after receipt durability, before deleting SQL, leaves two equivalent fences.
        new MatterReceiptHistory(directory).archiveCommand(command);
        Fixture interrupted=new Fixture(f.context);check(interrupted.store.commandReceipt(request).getString("payload").equals("same-payload"),"Dual durable fences did not survive restart");
        interrupted.store.archiveCommand(command);Fixture completed=new Fixture(f.context);
        check(completed.store.get("commands",request)==null&&completed.store.commandReceipt(request).getJSONObject("receipt").getString("state").equals("confirmed"),"Completed archive lost the receipt");
    }
    private static void inFlightSourceInterleaving() throws Exception {
        Fixture f=new Fixture();f.present("source-read","Reading current source");
        CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        f.adapters.reader=source->{reading.countDown();await(release);return new JSONObject().put("state","available").put("text","Resolved source").put("provenance",f.adapters.identity("memory"));};
        JSONObject attached=f.runtime.nativeAction(new JSONObject().put("threadId",THREAD).put("key","source-read").put("action","attachSource")
                .put("source",new JSONObject().put("kind","memory").put("resourceId","record").put("blockId","body")));
        String sourceId=attached.getString("sourceId");FutureTask<Void> resolving=fork(()->{f.io.take().run();return null;});await(reading);
        for(int i=0;i<230;i++)f.present("read-churn-"+i,"Reading churn "+i);
        check(f.store.get("matters",THREAD+"/source-read")!=null&&f.store.get("sources",sourceId)!=null,"In-flight source was reclaimed");
        JSONObject current=f.store.get("sources",sourceId);current.put("mutableMarker","concurrent update");f.store.put("sources",sourceId,current);
        release.countDown();result(resolving);
        check(f.store.get("sources",sourceId).getString("mutableMarker").equals("concurrent update"),"Read completion wrote stale mutable source state");
        check(f.snapshot("source-read").getJSONObject("request").getJSONArray("blocks").getJSONObject(0).getString("text").equals("Resolved source"),"Current source result did not reach the matter");
    }
    private static void pinCommitRace() throws Exception {
        Fixture f=new Fixture();f.present("race","Race target");
        AutoCloseable reading=f.runtime.retain(THREAD+"/race");
        for(int i=0;i<255;i++) {String key="owned-"+i;f.present(key,key);f.pin(key);}
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
        TaskRequests.beforeWrite=(file,text)->{
            if(file.getName().equals("host-matters.json")&&text.contains("Race target")) {writing.countDown();try {await(release);}catch(Exception failure) {throw new IllegalStateException(failure);}}
        };
        try {
            FutureTask<JSONObject> pinning=fork(()->f.pin("race"));await(writing);
            FutureTask<String> competing=fork(()->{try {f.present("race-overflow","Overflow");return "created";}catch(IOException full) {return full.getMessage();}});
            release.countDown();result(pinning);reading.close();
            check(result(competing).equals("MATTER_STORAGE_FULL"),"Creation evicted a panel during its Pin commit");
            check(f.store.get("matters",THREAD+"/race")!=null&&f.store.panelForMatter(THREAD+"/race")!=null,"Pin commit race lost fixed ownership");
        } finally {release.countDown();TaskRequests.beforeWrite=null;reading.close();}
    }
    private static void boundedCommandHistory() throws Exception {
        Fixture f=new Fixture();
        for(int group=0;group<8;group++) {
            String key="history-"+group;f.present(key,key);
            for(int entry=0;entry<64;entry++) {
                String request=id("history-"+group+"-"+entry);
                f.store.put("commands",request,new JSONObject().put("id",request).put("matterId",THREAD+"/"+key).put("payload","historical payload")
                        .put("createdAt",group*64+entry).put("terminal",true).put("receipt",new JSONObject().put("requestId",request).put("state","confirmed")));
            }
        }
        f.present("new-intent","New intent");f.runtime.setSubmit(input->new JSONObject().put("requestId",input.getString("requestId")).put("state","notSent"));
        for(int i=0;i<5;i++)f.runtime.dispatch(f.command("new-intent","new-intent-"+i));
        check(f.store.count("commands")==MatterStore.MAX_COMMAND_HISTORY,"Terminal command history grew beyond its bound");
        String archived=id("history-0-0");check(f.store.get("commands",archived)==null&&f.store.commandReceipt(archived)!=null,"Command compaction discarded its durable duplicate fence");
    }
    private static void notifications() throws Exception {
        Queue<Runnable> main=new ArrayDeque<>();WatchNotifications notifications=new WatchNotifications(main::add);
        Object audioMonitor=new Object(),runtimeMonitor=new Object();List<Integer> observed=new ArrayList<>();int[] version={0};
        AutoCloseable watch=notifications.watch("session",()->{
            check(!Thread.holdsLock(audioMonitor),"Audio observer holds the audio monitor");
            synchronized(runtimeMonitor) {synchronized(audioMonitor) {observed.add(version[0]);}}
        });
        synchronized(audioMonitor) {version[0]=1;notifications.changed("session");version[0]=2;notifications.changed("session");}
        check(observed.isEmpty(),"Audio observer ran synchronously");while(!main.isEmpty())main.remove().run();
        check(observed.equals(List.of(2,2)),"Invalidation snapshots were incoherent or reordered");
        notifications.changed("session");watch.close();while(!main.isEmpty())main.remove().run();check(observed.size()==2,"Closed watch received a queued callback");
        AutoCloseable late=notifications.watch("session",()->observed.add(3));check(main.isEmpty(),"New watch inherited an old invalidation");
        notifications.changed("session");
        CountDownLatch held=new CountDownLatch(1),callbackStarted=new CountDownLatch(1),closed=new CountDownLatch(1);
        AutoCloseable overlapping=notifications.watch("session",()->{callbackStarted.countDown();synchronized(runtimeMonitor) {observed.add(4);}});
        notifications.changed("session");
        FutureTask<Void> transition=fork(()->{synchronized(runtimeMonitor) {held.countDown();await(callbackStarted);late.close();overlapping.close();closed.countDown();}return null;});await(held);
        FutureTask<Void> delivery=fork(()->{while(!main.isEmpty())main.remove().run();return null;});await(closed);result(transition);result(delivery);
        check(observed.contains(4),"An already executing observer did not finish after close");
    }
    private static JSONObject inputRequest(String key,String title) throws Exception {
        JSONObject value=request(key,title);JSONArray blocks=value.getJSONArray("blocks");
        for(String name:List.of("notes","quantity","enabled")) {
            String type=name.equals("quantity")?"number":name.equals("enabled")?"boolean":"text";
            Object initial=name.equals("quantity")?1:name.equals("enabled")?true:"source";
            blocks.put(new JSONObject().put("id",name).put("kind","input").put("title",name).put("text","").put("image","").put("url","")
                    .put("value",new JSONObject().put("type",type).put("value",initial)).put("items",new JSONArray())
                    .put("config",new JSONObject().put("editor",type.equals("boolean")?"toggle":type.equals("number")?"number":"text")));
        }
        JSONArray items=new JSONArray();for(String id:List.of("a","b"))items.put(new JSONObject().put("id",id).put("label",id).put("detail","").put("image","").put("icon","").put("url","").put("value",new JSONObject().put("type","text").put("value",id)));
        blocks.put(new JSONObject().put("id","topics").put("kind","choices").put("title","Topics").put("text","").put("image","").put("url","")
                .put("value",new JSONObject().put("type","text").put("value","")).put("items",items).put("config",new JSONObject().put("multi",true)));
        return value;
    }
    private static JSONObject field(String id,Object value) {return new JSONObject().put("kind","field").put("id",id).put("value",value);}
    private static JSONObject edits(String matter,String request,long revision,JSONArray changes) {return new JSONObject().put("matterId",matter).put("requestId",id(request)).put("expectedUiRevision",revision).put("changes",changes);}
    private static Fixture editableFixture(String key) throws Exception {
        Fixture f=new Fixture();f.runtime.observe(THREAD,key,inputRequest(key,"Editable card"),"native-"+key,new JSONObject().put("kind","host-evidence"));return f;
    }
    private static void boundedCardViewHistory() throws Exception {
        Fixture f=editableFixture("large-edits");String matterId=THREAD+"/large-edits";
        JSONObject initial=f.panels.cardView("read",new JSONObject().put("matterId",matterId));JSONArray review=new JSONArray();
        for(int i=0;i<256;i++)review.put(new JSONObject().put("kind","field").put("id","old-"+i).put("sourceMatterId",matterId)
                .put("reason","schema-changed").put("needsReview",true).put("value","x".repeat(4000)));
        f.store.put("card_view_states",matterId,initial.put("review",review));
        JSONObject firstInput=edits(matterId,"large-edit-0",0,new JSONArray().put(field("notes","draft-0")));
        JSONObject first=f.panels.cardView("apply",firstInput);long bytes=first.toString().getBytes(StandardCharsets.UTF_8).length;
        check(bytes*36>32L*1024*1024,"Repeated edits do not exceed the bounded database without archiving");
        for(int i=1;i<36;i++) {
            JSONObject changed=f.panels.cardView("apply",edits(matterId,"large-edit-"+i,i,new JSONArray().put(field("notes","draft-"+i))));
            check(changed.getLong("uiRevision")==i+1&&f.store.count("card_view_receipts")==1,"Large edits accumulated live receipt copies or lost a revision");
        }
        check(f.store.all("card_view_receipts").toString().getBytes(StandardCharsets.UTF_8).length<2L*1024*1024,"Live receipt bytes grew with edit history");
        f.present("unrelated-write","Unrelated card");JSONObject panel=f.pin("unrelated-write").getJSONObject("panel");
        check(panel.getString("matterId").equals(THREAD+"/unrelated-write"),"Receipt pressure blocked unrelated writes");
        Fixture reopened=new Fixture(f.context);JSONObject current=reopened.panels.cardView("read",new JSONObject().put("matterId",matterId));
        JSONObject replay=reopened.panels.cardView("apply",firstInput);
        check(replay.similar(new JSONObject(first.toString()).put("replayed",true)),"Archived retry lost its full original result");
        check(reopened.panels.cardView("read",new JSONObject().put("matterId",matterId)).similar(current)&&current.getLong("uiRevision")==36,"Archived retry rewound the latest draft");
        rejects("IDEMPOTENCY_CONFLICT",()->reopened.panels.cardView("apply",new JSONObject(firstInput.toString()).put("changes",new JSONArray().put(field("notes","changed")))));
        rejects("IDEMPOTENCY_CONFLICT",()->reopened.panels.cardView("apply",new JSONObject(firstInput.toString()).put("expectedUiRevision",36)));
        rejects("IDEMPOTENCY_CONFLICT",()->reopened.panels.cardView("apply",new JSONObject(firstInput.toString()).put("matterId",THREAD+"/unrelated-write")));
        rejects("IDEMPOTENCY_CONFLICT",()->reopened.panels.call("pin",new JSONObject().put("requestId",firstInput.getString("requestId"))
                .put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/unrelated-write"))));
        rejects("IDEMPOTENCY_CONFLICT",()->continuePanel(reopened,panel,"large-edit-0"));
        JSONObject pinInput=new JSONObject().put("requestId",id("pin-before-card-edit")).put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/unrelated-write"));
        reopened.panels.call("pin",pinInput);
        rejects("IDEMPOTENCY_CONFLICT",()->reopened.panels.cardView("apply",edits(matterId,"pin-before-card-edit",36,new JSONArray().put(field("notes","conflict")))));
        continuePanel(reopened,panel,"continue-before-card-edit");
        rejects("IDEMPOTENCY_CONFLICT",()->reopened.panels.cardView("apply",edits(matterId,"continue-before-card-edit",36,new JSONArray().put(field("notes","conflict")))));
        reopened.store.remove("matters",matterId);
        check(reopened.panels.cardView("apply",firstInput).similar(replay)&&reopened.store.get("matters",matterId)==null,"Historical retry required or resurrected its removed source");
    }
    private static void existingCardViewHistory() throws Exception {
        Fixture f=editableFixture("old-receipts");String matterId=THREAD+"/old-receipts";
        List<JSONObject> inputs=new ArrayList<>(),results=new ArrayList<>();
        f.store.transaction(()->{
            for(int i=0;i<3;i++) {
                JSONObject input=edits(matterId,"old-receipt-"+i,i,new JSONArray().put(field("notes","old-draft-"+i)));inputs.add(input);
                results.add(new CardViewStateStore(f.store).apply(input));
            }
            return null;
        });
        check(f.store.count("card_view_receipts")==3,"Migration fixture did not retain existing SQL receipts");
        Fixture reopened=new Fixture(f.context);
        check(reopened.store.count("card_view_receipts")==0,"Opening existing storage did not archive committed receipts");
        for(int i=0;i<inputs.size();i++)check(reopened.panels.cardView("apply",inputs.get(i)).similar(new JSONObject(results.get(i).toString()).put("replayed",true)),"Migrated receipt did not replay its original result");
        check(reopened.panels.cardView("read",new JSONObject().put("matterId",matterId)).getLong("uiRevision")==3,"Migrating receipts changed the current draft");
    }
    private static void cardViewCommitFailureOrdering() throws Exception {
        for(boolean crash:List.of(false,true)) {
            Fixture f=editableFixture("atomic-edits");String matterId=THREAD+"/atomic-edits";
            JSONObject input=edits(matterId,"atomic-edit-"+crash,0,new JSONArray().put(field("notes","committed draft")));
            TaskRequests.beforeWrite=(file,text)->{if(file.getName().equals("host-matters.json")){if(crash)throw new ProcessStopped();throw new IllegalStateException("commit failed");}};
            try {f.panels.cardView("apply",input);throw new AssertionError("Failed commit appeared applied");}
            catch(ProcessStopped expected) {check(crash,"Unexpected simulated crash");}
            catch(IllegalStateException expected) {check(!crash,"Unexpected commit failure");}
            finally {TaskRequests.beforeWrite=null;}
            Fixture reopened=new Fixture(f.context);
            check(reopened.store.cardViewReceipt(input.getString("requestId"))==null&&reopened.panels.cardView("read",new JSONObject().put("matterId",matterId)).getLong("uiRevision")==0,"Failure before commit invented applied state or an archive fence");
            JSONObject applied=reopened.panels.cardView("apply",input);
            check(!applied.getBoolean("replayed")&&applied.getLong("uiRevision")==1,"Retry after a failed commit skipped the real edit");
        }
        Fixture nested=editableFixture("nested-edits");JSONObject input=edits(THREAD+"/nested-edits","nested-edit",0,new JSONArray().put(field("notes","rolled back")));
        rejects("CANCELLED",()->nested.store.transaction(()->{
            new CardViewStateStore(nested.store).apply(input);
            nested.store.transaction(()->null);
            throw new IOException("CANCELLED");
        }));
        Fixture reopened=new Fixture(nested.context);
        check(reopened.store.cardViewReceipt(input.getString("requestId"))==null&&reopened.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/nested-edits")).getLong("uiRevision")==0,"Nested transaction archived an uncommitted mutation");
    }
    private static void cardViewArchiveFailureOrdering() throws Exception {
        Fixture blocked=editableFixture("archive-edits");String matterId=THREAD+"/archive-edits";
        JSONObject firstInput=edits(matterId,"archive-edit-first",0,new JSONArray().put(field("notes","first draft")));
        JSONObject first=blocked.panels.cardView("apply",firstInput),nextInput=edits(matterId,"archive-edit-next",1,new JSONArray().put(field("notes","next draft")));
        File directory=new File(blocked.context.getFilesDir(),"agent-ui-receipts");Files.writeString(directory.toPath(),"blocks receipt storage");
        rejects("RECEIPT_STORAGE_UNAVAILABLE",()->blocked.panels.cardView("apply",nextInput));
        check(blocked.store.get("card_view_receipts",firstInput.getString("requestId"))!=null&&blocked.store.cardViewReceipt(nextInput.getString("requestId"))==null,"Archive failure removed a live fence or invented the next result");
        check(blocked.panels.cardView("read",new JSONObject().put("matterId",matterId)).getLong("uiRevision")==1,"Archive failure applied the next edit");
        Files.delete(directory.toPath());
        check(blocked.panels.cardView("apply",nextInput).getLong("uiRevision")==2,"Archive recovery did not admit the next edit");
        check(blocked.panels.cardView("apply",firstInput).similar(new JSONObject(first.toString()).put("replayed",true)),"Archive recovery lost the original result");
        for(boolean archiveDurable:List.of(false,true)) {
            Fixture f=editableFixture("archive-crash");String crashMatter=THREAD+"/archive-crash";
            JSONObject committedInput=edits(crashMatter,"archive-crash-first-"+archiveDurable,0,new JSONArray().put(field("notes","committed")));
            JSONObject committed=f.panels.cardView("apply",committedInput);
            JSONObject pending=edits(crashMatter,"archive-crash-next-"+archiveDurable,1,new JSONArray().put(field("notes","next")));
            String request=committedInput.getString("requestId");File archive=new File(new File(f.context.getFilesDir(),"agent-ui-receipts"),"card-view-"+request+".json");
            TaskRequests.beforeWrite=(file,text)->{
                if(!archiveDurable&&file.equals(archive)||archiveDurable&&file.getName().equals("host-matters.json")&&
                        !new JSONObject(text).getJSONObject("tables").getJSONObject("card_view_receipts").has(request))throw new ProcessStopped();
            };
            try {f.panels.cardView("apply",pending);throw new AssertionError("Expected process death during archive");}
            catch(ProcessStopped expected) {}
            finally {TaskRequests.beforeWrite=null;}
            JSONObject disk=new JSONObject(Files.readString(new File(f.context.getFilesDir(),"host-matters.json").toPath())).getJSONObject("tables");
            check(disk.getJSONObject("card_view_receipts").has(request)&&archive.isFile()==archiveDurable,"Crash did not retain the committed SQL fence at the archive boundary");
            Fixture reopened=new Fixture(f.context);
            check(reopened.panels.cardView("apply",committedInput).similar(new JSONObject(committed.toString()).put("replayed",true)),"Archive crash lost the committed original result");
            check(reopened.panels.cardView("read",new JSONObject().put("matterId",crashMatter)).getLong("uiRevision")==1&&reopened.store.cardViewReceipt(pending.getString("requestId"))==null,"Archive crash invented a later edit");
            JSONObject applied=reopened.panels.cardView("apply",pending);
            check(!applied.getBoolean("replayed")&&applied.getLong("uiRevision")==2,"Archive crash prevented the next edit from committing once");
        }
    }
    private static void panelDirectoryAndRetries() throws Exception {
        Fixture f=new Fixture();f.present("shared","Shared source");
        JSONObject first=new JSONObject().put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/shared")).put("requestId",id("first-pin"));
        FutureTask<JSONObject> a=fork(()->f.panels.call("pin",first)),b=fork(()->f.pin("shared"));
        JSONObject one=result(a),two=result(b);
        check(one.getJSONObject("panel").getString("panelId").equals(two.getJSONObject("panel").getString("panelId")),"Concurrent Pin created two identities");
        check(one.getBoolean("created")!=two.getBoolean("created"),"Concurrent Pin claimed two creations");
        check(f.panels.call("list",new JSONObject()).getJSONArray("items").length()==1,"Concurrent Pin duplicated the directory");
        f.unpin("shared");
        JSONObject replay=new Fixture(f.context).panels.call("pin",first);
        check(replay.getBoolean("replayed")&&replay.getJSONObject("panel").similar(one.getJSONObject("panel")),"Restart replay lost the original Pin receipt");
        check(f.panels.call("list",new JSONObject()).getJSONArray("items").length()==0,"Old Pin replay resurrected an unpinned panel");
        f.present("different","Different source");
        rejects("IDEMPOTENCY_CONFLICT",()->f.panels.call("pin",new JSONObject(first.toString()).put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/different"))));
        JSONObject current=f.pin("shared").getJSONObject("panel");
        check(!current.getString("panelId").equals(one.getJSONObject("panel").getString("panelId")),"A new fixed lifecycle reused the removed Panel ID");
        JSONObject rename=new JSONObject().put("panelId",current.getString("panelId")).put("title","  User title  ").put("expectedMetadataRevision",0).put("requestId",id("rename"));
        JSONObject renamed=f.panels.call("rename",rename);
        check(renamed.getJSONObject("panel").getString("title").equals("User title")&&renamed.getJSONObject("panel").getLong("sourceVersion")==0,"Rename changed the source or ignored normalized title");
        rejects("VERSION_CONFLICT",()->f.panels.call("rename",new JSONObject(rename.toString()).put("requestId",id("stale-rename")).put("title","stale")));
        check(f.panels.call("rename",rename).getBoolean("replayed"),"Rename retry changed metadata twice");
        long revision=renamed.getLong("listRevision");
        JSONObject move=new JSONObject().put("panelId",current.getString("panelId")).put("beforePanelId",current.getString("panelId")).put("expectedListRevision",revision).put("requestId",id("self-move"));
        check(f.panels.call("move",move).getLong("listRevision")==revision,"Move to self advanced the list version");
        rejects("PANEL_NOT_FOUND",()->f.panels.call("move",new JSONObject(move.toString()).put("beforePanelId",id("missing-panel")).put("requestId",id("missing-target"))));
        for(int i=0;i<70;i++){f.present("page-"+i,"Page "+i);f.pin("page-"+i);}
        JSONObject page=f.panels.call("list",new JSONObject().put("limit",17));String cursor=page.getString("nextCursor");
        Set<String> ids=new java.util.HashSet<>();int count=0;
        while(true){JSONArray items=page.getJSONArray("items");for(int i=0;i<items.length();i++){count++;check(ids.add(items.getJSONObject(i).getString("panelId")),"Pagination repeated an ID");}
            if(page.isNull("nextCursor"))break;page=f.panels.call("list",new JSONObject().put("limit",17).put("cursor",page.getString("nextCursor")));}
        check(count==71,"Directory retained the old 64-item cap");
        rejects("INVALID_ARGUMENT",()->f.panels.call("list",new JSONObject().put("cursor",cursor+"x")));
        f.present("changed-list","Changed");f.pin("changed-list");
        rejects("LIST_CHANGED",()->f.panels.call("list",new JSONObject().put("cursor",cursor)));
        for(int i=0;i<520;i++){JSONObject record=f.panels.call("get",new JSONObject().put("panelId",current.getString("panelId"))).getJSONObject("panel");
            f.panels.call("rename",new JSONObject().put("panelId",current.getString("panelId")).put("title","Title "+i).put("expectedMetadataRevision",record.getLong("metadataRevision")).put("requestId",id("durable-rename-"+i)));}
        check(f.panels.call("rename",rename).getJSONObject("panel").getString("title").equals("User title"),"Receipt pressure discarded the original result fence");
        check(f.panels.call("get",new JSONObject().put("panelId",current.getString("panelId"))).getJSONObject("panel").getString("title").equals("Title 519"),"Receipt replay rewound current metadata");
    }
    private static JSONObject continuePanel(Fixture f,JSONObject panel,String name) throws Exception {
        return f.panels.call("continue",new JSONObject().put("panelId",panel.getString("panelId")).put("expectedSourceVersion",panel.getLong("sourceVersion")).put("text","Update the existing card").put("requestId",id(name)));
    }
    private static JSONObject completionReply(String key,String turn) throws Exception {
        JSONObject reply=primary(key,1,"completed","Updated content");reply.getJSONObject("provenance").put("turnId",turn);
        reply.getJSONObject("card").put("request",inputRequest(key,"Updated content"));return reply;
    }
    private static JSONObject candidate(JSONObject reply) throws Exception {return new JSONObject().put("replyId",reply.getString("id")).put("key",reply.getString("key")).put("revision",reply.getLong("revision")).put("contractHash",reply.getJSONObject("provenance").getString("contractHash")).put("anchorOrdinal",reply.getJSONObject("provenance").getLong("anchorOrdinal"));}
    private static void terminal(String task,String turn,String status,JSONArray candidates,JSONObject reply,String code) throws Exception {
        File dir=new File(AssistantSession.TASKS,task);
        TaskRequests.write(new File(dir,"runtime-info.json"),new JSONObject().put("threadId",THREAD).put("turnId",turn).toString());
        JSONObject manifest=new JSONObject().put("schema","taku.panel-completion/1").put("taskId",task).put("threadId",THREAD).put("turnId",turn).put("status",status).put("candidates",candidates).put("reply",reply==null?JSONObject.NULL:reply);
        if(code!=null)manifest.put("code",code);TaskRequests.write(new File(dir,"panel-completion.json"),manifest.toString());
        TaskRequests.write(new File(dir,"state.json"),new JSONObject().put("id",task).put("state",status).toString());TaskRequests.write(new File(dir,"done"),status);
    }
    private static void sharedStateAndPublication() throws Exception {
        Fixture f=new Fixture();f.runtime.observe(THREAD,"editable",inputRequest("editable","Original"),"native-inputs",new JSONObject().put("kind","host-evidence"));
        JSONObject panel=f.pin("editable").getJSONObject("panel");String panelId=panel.getString("panelId");
        List<JSONObject> events=new ArrayList<>();boolean[] applying={false};
        MatterRuntime.Listener listener=event->{if(applying[0])check(!Thread.holdsLock(f.runtime),"Card state callback ran inside the runtime monitor");events.add(event);};
        f.runtime.watch("card-state",new JSONArray().put(THREAD+"/editable"),listener);long revision=f.snapshot("editable").getLong("revision");
        JSONObject changes=edits(THREAD+"/editable","truthy-values",0,new JSONArray().put(field("notes","")).put(field("quantity",0)).put(field("enabled",false)).put(new JSONObject().put("kind","selection").put("id","topics").put("itemIds",new JSONArray())));
        applying[0]=true;JSONObject changed=f.panels.cardView("apply",changes);applying[0]=false;
        check(changed.getLong("uiRevision")==1&&f.snapshot("editable").getLong("revision")==revision,"Pure UI edit changed the business revision");
        check(events.get(events.size()-1).getJSONObject("snapshot").getJSONObject("cardView").getLong("uiRevision")==1,"Pure UI edit did not reach Matter subscribers");
        JSONObject fields=changed.getJSONObject("ui").getJSONObject("fields");
        check(fields.getJSONObject("notes").getString("draft").isEmpty()&&fields.getJSONObject("quantity").getInt("draft")==0&&!fields.getJSONObject("enabled").getBoolean("draft"),"False, zero or empty draft was replaced by its source");
        check(f.panels.cardView("apply",changes).getBoolean("replayed"),"Card UI replay incremented its revision twice");
        rejects("VERSION_CONFLICT",()->f.panels.cardView("apply",edits(THREAD+"/editable","stale-ui",0,new JSONArray().put(field("notes","stale")))));
        rejects("INVALID_ARGUMENT",()->f.panels.cardView("apply",edits(THREAD+"/editable","bad-type",1,new JSONArray().put(field("quantity",false)))));
        rejects("INVALID_ARGUMENT",()->f.panels.cardView("apply",edits(THREAD+"/editable","bad-selection",1,new JSONArray().put(new JSONObject().put("kind","selection").put("id","topics").put("itemIds",new JSONArray().put("removed"))))));
        JSONObject oldAction=f.command("editable","superseded-action");
        TaskRequests.beforeWrite=(file,text)->{if(file.getName().equals("ready")){try {JSONObject binding=f.store.get("panel_updates",id("continue-main"));check(binding!=null&&binding.getString("taskId").equals(file.getParentFile().getName())&&f.store.get("sources","task-"+binding.getString("taskId"))!=null,"Task became ready before its Panel binding and source hold");}catch(Exception failure){throw new IllegalStateException(failure);}}};
        JSONObject submission;try{submission=continuePanel(f,panel,"continue-main");}finally{TaskRequests.beforeWrite=null;}
        String task=submission.getString("taskId");
        JSONObject request=SourceAdapters.readObject(new File(new File(AssistantSession.TASKS,task),"request.json"));
        check(request.getJSONObject("panelUpdate").getString("panelId").equals(panelId)&&request.getJSONObject("panelUpdate").getLong("sourceVersion")==0,"Continue omitted the trusted exact target");
        JSONObject reply=completionReply("replacement","turn-main");
        JSONArray updatedBlocks=reply.getJSONObject("card").getJSONObject("request").getJSONArray("blocks");
        for(int i=0;i<updatedBlocks.length();i++){JSONObject block=updatedBlocks.getJSONObject(i);if(block.getString("id").equals("quantity")){block.put("value",new JSONObject().put("type","text").put("value","new source"));block.put("config",new JSONObject().put("editor","text"));}}
        TaskRequests.write(new File(new File(AssistantSession.TASKS,task),"runtime-info.json"),new JSONObject().put("threadId",THREAD).put("turnId","turn-main").toString());f.runtime.taskChanged(task);
        f.runtime.observeReply(THREAD,"turn-main",reply);
        check(f.store.get("matters",THREAD+"/replacement")==null,"Pending continuation independently admitted its candidate");
        f.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",reply));
        rejects("PANEL_UPDATE_PENDING",()->f.panels.call("pin",new JSONObject().put("source",primarySource(reply)).put("requestId",id("candidate-pin"))));
        terminal(task,"turn-main","completed",new JSONArray().put(candidate(reply)),reply,null);f.runtime.unwatch("card-state",listener);
        android.app.KeyguardManager.locked=true;
        try {f.runtime.taskChanged(task);rejects("DEVICE_LOCKED",()->f.panels.call("get",new JSONObject().put("panelId",panelId)));}
        finally {android.app.KeyguardManager.locked=false;}
        JSONObject detail=f.panels.call("get",new JSONObject().put("panelId",panelId)),published=detail.getJSONObject("panel");
        check(published.getString("matterId").equals(THREAD+"/replacement")&&published.getLong("sourceVersion")==1&&published.getString("title").equals(panel.getString("title"))&&published.getLong("position")==panel.getLong("position"),"Publication changed identity or metadata instead of replacing the source");
        check(detail.getJSONObject("updateReceipt").getString("outcome").equals("published")&&detail.getJSONObject("updateReceipt").getJSONArray("candidates").getJSONObject(0).getString("replyId").equals(reply.getString("id")),"Publication lost its exact completion receipt");
        JSONObject migrated=f.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/replacement")).getJSONObject("ui").getJSONObject("fields");
        check(migrated.getJSONObject("quantity").getInt("draft")==0&&!migrated.getJSONObject("enabled").getBoolean("draft")&&migrated.getJSONObject("notes").getString("draft").isEmpty(),"Publication lost shared explicit empty values");
        check(migrated.getJSONObject("quantity").getBoolean("needsReview"),"Incompatible type change silently accepted an old numeric draft");
        rejects("DRAFT_REVIEW_REQUIRED",()->f.runtime.dispatch(f.command("replacement","reviewed-action").put("values",new JSONObject().put("quantity",0))));
        rejects("STALE_SOURCE",()->f.runtime.dispatch(oldAction));
        String savedBeforeLate=f.store.get("card_view_states",THREAD+"/editable").toString();
        rejects("STALE_SOURCE",()->f.panels.cardView("apply",edits(THREAD+"/editable","late-ui",1,new JSONArray().put(field("notes","Last typed")))));
        check(f.store.get("card_view_states",THREAD+"/editable").toString().equals(savedBeforeLate),"Rejected late write changed migrated state or cleared its transfer marker");
        check(f.store.cardViewReceipt(id("late-ui"))==null,"Rejected late write left a success receipt");
        check(f.panels.cardView("apply",changes).getBoolean("replayed"),"Publication invalidated an already committed edit receipt");
        check(f.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/replacement")).getJSONObject("ui").getJSONObject("fields").getJSONObject("notes").getString("draft").isEmpty(),"Rejected late write modified the replacement card");
        f.present("editable","Reopened old presentation");check(!f.snapshot("editable").getJSONObject("request").getJSONArray("actions").getJSONObject(0).has("actionRef"),"Superseded actions reminted on reread");
        Fixture restarted=new Fixture(f.context);rejects("STALE_SOURCE",()->restarted.runtime.dispatch(oldAction));
        rejects("STALE_SOURCE",()->restarted.panels.cardView("apply",edits(THREAD+"/editable","late-ui-restarted",1,new JSONArray().put(field("notes","Last typed after restart")))));
        long listRevision=detail.getLong("listRevision");restarted.runtime.taskChanged(task);
        check(restarted.panels.call("get",new JSONObject().put("panelId",panelId)).getLong("listRevision")==listRevision,"Completion retry republished a settled source");
        JSONObject repeated=continuePanel(restarted,panel,"continue-main");check(repeated.getBoolean("replayed")&&repeated.getString("taskId").equals(task),"Continue replay executed another task after restart");
        check(!restarted.pin("replacement").getBoolean("created"),"A published reply gained an independent second Panel");
    }
    private static void rejectedAndLateCompletions() throws Exception {
        Fixture f=new Fixture();f.runtime.observe(THREAD,"origin",inputRequest("origin","Origin"),"native-inputs",new JSONObject().put("kind","host-evidence"));
        JSONObject panel=f.pin("origin").getJSONObject("panel");String panelId=panel.getString("panelId");
        JSONObject first=continuePanel(f,panel,"first-update"),late=continuePanel(f,panel,"late-update");
        JSONObject reply=completionReply("first-result","turn-first");terminal(first.getString("taskId"),"turn-first","completed",new JSONArray().put(candidate(reply)),reply,null);f.runtime.taskChanged(first.getString("taskId"));
        check(f.store.get("card_view_states",THREAD+"/origin")==null,"Untouched source unexpectedly had saved state");
        rejects("STALE_SOURCE",()->f.panels.cardView("apply",edits(THREAD+"/origin","late-first-edit",0,new JSONArray().put(field("notes","First late input")))));
        check(f.store.get("card_view_states",THREAD+"/origin")==null&&f.store.cardViewReceipt(id("late-first-edit"))==null,"First late write created orphaned state or a success receipt");
        JSONObject lateReply=completionReply("late-result","turn-late");terminal(late.getString("taskId"),"turn-late","completed",new JSONArray().put(candidate(lateReply)),lateReply,null);f.runtime.taskChanged(late.getString("taskId"));
        JSONObject detail=f.panels.call("get",new JSONObject().put("panelId",panelId));
        check(detail.getJSONObject("panel").getString("matterId").equals(THREAD+"/first-result")&&f.store.get("matters",THREAD+"/late-result")==null,"Late completion overrode the CAS or admitted a rejected source");
        check(detail.getJSONObject("updateReceipt").getString("code").equals("STALE_SOURCE"),"Late completion lost its persisted outcome");
        JSONObject current=detail.getJSONObject("panel"),ambiguous=continuePanel(f,current,"ambiguous-update");JSONArray candidates=new JSONArray();
        for(int i=0;i<35;i++)candidates.put(candidate(completionReply("candidate-"+i,"turn-many")));
        terminal(ambiguous.getString("taskId"),"turn-many","completed",candidates,null,"PANEL_UPDATE_AMBIGUOUS");f.runtime.taskChanged(ambiguous.getString("taskId"));
        detail=f.panels.call("get",new JSONObject().put("panelId",panelId));check(detail.getJSONObject("updateReceipt").getString("code").equals("PANEL_UPDATE_AMBIGUOUS")&&detail.getJSONObject("panel").getLong("sourceVersion")==1,"Multiple candidates were truncated or guessed");
        JSONObject text=continuePanel(f,current,"text-update");terminal(text.getString("taskId"),"turn-text","completed",new JSONArray(),null,"REPLY_INCOMPLETE");f.runtime.taskChanged(text.getString("taskId"));
        check(f.panels.call("get",new JSONObject().put("panelId",panelId)).getJSONObject("updateReceipt").getString("code").equals("REPLY_INCOMPLETE"),"Plain text completion replaced the retained card");
        JSONObject failed=continuePanel(f,current,"failed-update");terminal(failed.getString("taskId"),"","cancelled",new JSONArray(),null,null);f.runtime.taskChanged(failed.getString("taskId"));
        check(f.panels.call("get",new JSONObject().put("panelId",panelId)).getJSONObject("updateReceipt").getString("outcome").equals("interrupted"),"Cancelled task appeared published");
        JSONObject rebound=continuePanel(f,current,"rebound-update");String reboundTask=rebound.getString("taskId");
        TaskRequests.write(new File(new File(AssistantSession.TASKS,reboundTask),"runtime-info.json"),new JSONObject().put("threadId",THREAD).put("turnId","bound-turn").toString());f.runtime.taskChanged(reboundTask);
        JSONObject wrongTurn=completionReply("wrong-turn-source","different-turn");terminal(reboundTask,"different-turn","completed",new JSONArray().put(candidate(wrongTurn)),wrongTurn,null);f.runtime.taskChanged(reboundTask);
        check(f.panels.call("get",new JSONObject().put("panelId",panelId)).getJSONObject("updateReceipt").getString("code").equals("SOURCE_CHANGED")&&f.store.get("matters",THREAD+"/wrong-turn-source")==null,"Changed task-turn identity published unrelated content");
        JSONObject removed=continuePanel(f,current,"removed-update");f.unpin("first-result");JSONObject afterRemoval=completionReply("after-unpin","turn-removed");
        terminal(removed.getString("taskId"),"turn-removed","completed",new JSONArray().put(candidate(afterRemoval)),afterRemoval,null);f.runtime.taskChanged(removed.getString("taskId"));
        check(f.panels.call("list",new JSONObject()).getJSONArray("items").length()==0&&f.store.get("matters",THREAD+"/after-unpin")==null,"Completion resurrected an unpinned lifecycle");
        check(continuePanel(f,current,"removed-update").getJSONObject("update").getString("outcome").equals("unpinned"),"Unpin during update lost the durable completion outcome");
    }
    private static void migrationAndUnknownDelivery() throws Exception {
        Fixture f=new Fixture();JSONObject request=inputRequest("legacy","Original native facts");f.runtime.observe(THREAD,"legacy",request,"native-legacy",new JSONObject().put("kind","host-evidence"));
        JSONObject ui=new JSONObject().put("fields",new JSONObject().put("notes",new JSONObject().put("draft","").put("source",new JSONObject().put("type","text").put("value","source")).put("needsReview",true))
                .put("quantity",new JSONObject().put("draft",0).put("source",new JSONObject().put("type","number").put("value",1)))
                .put("enabled",new JSONObject().put("draft",false).put("source",new JSONObject().put("type","boolean").put("value",true))))
                .put("collections",new JSONObject().put("topics",new JSONObject().put("selectedItemIds",new JSONArray()))).put("views",new JSONObject().put("details",new JSONObject().put("expanded",false)));
        File root=new File(f.context.getFilesDir(),"agent-ui-panels");root.mkdirs();File original=new File(root,THREAD+"__legacy.json");
        String archived=new JSONObject().put("threadId",THREAD).put("key","legacy").put("kept",true).put("ui",ui).put("request",request("legacy","Forged legacy facts")).put("commands",new JSONObject().put("fake","success")).toString();Files.writeString(original.toPath(),archived);
        f.runtime.recover();f.io.drain();JSONObject state=f.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/legacy"));JSONObject fields=state.getJSONObject("ui").getJSONObject("fields");
        check(f.panels.call("list",new JSONObject()).getJSONArray("items").length()==0,"Migration imported an old Keep relationship");
        check(f.snapshot("legacy").getJSONObject("request").getString("title").equals("Original native facts"),"Migration trusted legacy client content");
        check(fields.getJSONObject("quantity").getInt("draft")==0&&!fields.getJSONObject("enabled").getBoolean("draft")&&fields.getJSONObject("notes").getString("draft").isEmpty()&&fields.getJSONObject("notes").getBoolean("needsReview"),"Migration lost explicit values or review state");
        check(state.getJSONObject("ui").getJSONObject("collections").getJSONObject("topics").getJSONArray("selectedItemIds").length()==0,"Migration lost an explicit cleared selection");
        check(Files.readString(original.toPath()).equals(archived),"Migration rewrote the original user archive");
        long version=state.getLong("uiRevision");f.runtime.recover();f.io.drain();check(f.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/legacy")).getLong("uiRevision")==version,"Restart imported a completed migration twice");
        check(f.panels.call("describe",new JSONObject()).getJSONArray("migration").getJSONObject(0).getBoolean("complete"),"Migration disposition was not discoverable");
        JSONObject trusted=completionReply("pending-migration","turn-migration");f.runtime.observeReply(THREAD,"turn-migration",trusted);f.store.remove("primary_snapshots",THREAD+"/pending-migration");
        File pendingArchive=new File(root,THREAD+"__pending-migration.json");
        Files.writeString(pendingArchive.toPath(),new JSONObject().put("threadId",THREAD).put("key","pending-migration").put("source",primarySource(trusted)).put("ui",ui).toString());
        f.runtime.recover();f.io.drain();
        check(!f.store.get("card_state_migrations","1/"+pendingArchive.getName()).getBoolean("complete")&&f.store.get("primary_snapshots",THREAD+"/pending-migration")==null,"Unavailable migration source was falsely verified");
        long pendingRevision=f.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/pending-migration")).getLong("uiRevision");
        f.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",trusted));f.runtime.recover();f.io.drain();
        check(f.store.get("card_state_migrations","1/"+pendingArchive.getName()).getBoolean("complete")&&f.store.get("primary_snapshots",THREAD+"/pending-migration")!=null,"Retry did not preserve a newly verifiable native snapshot");
        check(f.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/pending-migration")).getLong("uiRevision")==pendingRevision,"Snapshot verification retry imported the shared draft twice");
        JSONObject panel=f.pin("legacy").getJSONObject("panel");TaskRequests.beforeWrite=(file,text)->{if(file.getName().equals("ready"))throw new IllegalStateException("uncertain transport");};
        JSONObject uncertain;try{uncertain=continuePanel(f,panel,"unknown-delivery");}finally{TaskRequests.beforeWrite=null;}
        check(uncertain.getString("state").equals("deliveryUnknown"),"Unknown publication was declared successful");
        int tasks=AssistantSession.TASKS.list().length;JSONObject replay=continuePanel(f,panel,"unknown-delivery");
        check(replay.getString("taskId").equals(uncertain.getString("taskId"))&&AssistantSession.TASKS.list().length==tasks,"Unknown delivery replay created a second task");
        String task=uncertain.getString("taskId");TaskRequests.write(new File(new File(AssistantSession.TASKS,task),"ready"),"1");
        JSONObject recovered=continuePanel(f,panel,"unknown-delivery");check(recovered.getString("state").equals("queued")&&recovered.getString("taskId").equals(task),"Known ready evidence did not recover the original unknown receipt");
        TaskRequests.write(new File(new File(AssistantSession.TASKS,task),"state.json"),new JSONObject().put("id",task).put("state","completed").toString());TaskRequests.write(new File(new File(AssistantSession.TASKS,task),"done"),"completed");f.runtime.taskChanged(task);
        check(f.panels.call("get",new JSONObject().put("panelId",panel.getString("panelId"))).getJSONObject("updateReceipt").getString("code").equals("SOURCE_UNAVAILABLE"),"Missing completion manifest guessed a source");
    }
    private static final class ProcessStopped extends Error {}
    private static void reserveWithoutSubmission(Fixture f,JSONObject panel,String name) throws Exception {
        TaskRequests.beforeWrite=(file,text)->{if(file.getName().equals(id(name)+".json")&&file.getParentFile().getName().equals("agent-ui-receipts"))throw new ProcessStopped();};
        try {continuePanel(f,panel,name);throw new AssertionError("Expected process death before submission journal");}
        catch(ProcessStopped expected) {}
        finally {TaskRequests.beforeWrite=null;}
        check(f.store.get("panel_updates",id(name))!=null&&AgentGatewaySubmissions.existing(f.context.getFilesDir(),id(name))==null,"Crash did not leave the precise pre-submission reservation");
    }
    private static void preSubmissionRecovery() throws Exception {
        for(boolean retry:List.of(false,true)) {
            Fixture f=new Fixture();f.present("reserved","Reserved source");JSONObject panel=f.pin("reserved").getJSONObject("panel");
            String name="pre-submission-"+retry;int tasks=AssistantSession.TASKS.list().length;
            reserveWithoutSubmission(f,panel,name);
            Fixture restarted=new Fixture(f.context);
            if(!retry){restarted.runtime.recover();restarted.io.drain();}
            JSONObject receipt=continuePanel(restarted,panel,name);String task=receipt.getString("taskId");
            check(receipt.getBoolean("replayed")&&receipt.getString("state").equals("queued")&&receipt.getString("requestId").equals(id(name)),"Pre-submission recovery lost the original request identity");
            check(AssistantSession.TASKS.list().length==tasks+1,"Pre-submission recovery did not publish exactly one task");
            JSONObject taskRequest=SourceAdapters.readObject(new File(new File(AssistantSession.TASKS,task),"request.json"));
            check(taskRequest.getString("prompt").equals("Update the existing card")&&taskRequest.getJSONObject("panelUpdate").getString("requestId").equals(id(name)),"Recovered task did not retain the authorized payload and target");
            restarted.runtime.recover();restarted.io.drain();
            check(continuePanel(restarted,panel,name).getString("taskId").equals(task)&&AssistantSession.TASKS.list().length==tasks+1,"Repeated recovery duplicated a task");
            JSONObject reply=completionReply("recovered-"+retry,"recovered-turn-"+retry);
            terminal(task,"recovered-turn-"+retry,"completed",new JSONArray().put(candidate(reply)),reply,null);restarted.runtime.taskChanged(task);
            check(restarted.panels.call("get",new JSONObject().put("panelId",panel.getString("panelId"))).getJSONObject("panel").getLong("sourceVersion")==1,"Recovered submission did not publish its exact completion");
            JSONObject later=completionReply("later-"+retry,"later-turn-"+retry);restarted.runtime.observeReply(THREAD,"later-turn-"+retry,later);
            restarted.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",later));
            check(restarted.pin("later-"+retry).getBoolean("created"),"Recovered reservation fenced unrelated later sources forever");
        }
        Fixture racing=new Fixture();racing.present("racing-reservation","Original");JSONObject racingPanel=racing.pin("racing-reservation").getJSONObject("panel");
        reserveWithoutSubmission(racing,racingPanel,"racing-reservation");Fixture recovering=new Fixture(racing.context);int racingTasks=AssistantSession.TASKS.list().length;
        CountDownLatch publishing=new CountDownLatch(1),release=new CountDownLatch(1);
        TaskRequests.beforeWrite=(file,text)->{if(file.getName().equals("ready")){publishing.countDown();try{await(release);}catch(Exception failure){throw new IllegalStateException(failure);}}};
        try {
            FutureTask<Void> recovery=fork(()->{recovering.runtime.recover();recovering.io.drain();return null;});await(publishing);
            JSONObject concurrent=continuePanel(recovering,racingPanel,"racing-reservation");
            check(concurrent.getString("state").equals("deliveryUnknown")&&AssistantSession.TASKS.list().length==racingTasks+1,"Concurrent retry crossed the in-progress recovery journal");
            release.countDown();result(recovery);
            JSONObject queued=continuePanel(recovering,racingPanel,"racing-reservation");
            check(queued.getString("state").equals("queued")&&queued.getString("taskId").equals(concurrent.getString("taskId"))&&AssistantSession.TASKS.list().length==racingTasks+1,"Concurrent recovery and retry published more than one task");
        } finally {release.countDown();TaskRequests.beforeWrite=null;}
        Fixture legacy=new Fixture();legacy.present("old-reservation","Original");JSONObject panel=legacy.pin("old-reservation").getJSONObject("panel");
        reserveWithoutSubmission(legacy,panel,"legacy-reservation");JSONObject old=legacy.store.get("panel_updates",id("legacy-reservation"));old.remove("text");legacy.store.put("panel_updates",id("legacy-reservation"),old);
        int tasks=AssistantSession.TASKS.list().length;Fixture restarted=new Fixture(legacy.context);restarted.runtime.recover();restarted.io.drain();
        JSONObject receipt=continuePanel(restarted,panel,"legacy-reservation");
        check(receipt.getString("state").equals("notSent")&&receipt.getJSONObject("update").getBoolean("settled")&&receipt.getString("requestId").equals(id("legacy-reservation")),"Legacy reservation without a journal remained deliveryUnknown");
        check(AssistantSession.TASKS.list().length==tasks,"Legacy recovery invented a task without its original text");
        JSONObject later=completionReply("after-legacy-reservation","later-turn");restarted.runtime.observeReply(THREAD,"later-turn",later);
        restarted.runtime.setReplyReader(input->new JSONObject().put("ok",true).put("reply",later));
        check(restarted.pin("after-legacy-reservation").getBoolean("created"),"Legacy pre-submission reservation kept fencing future sources");
    }
    private static void multibyteLegacyMigration() throws Exception {
        Fixture f=new Fixture();f.runtime.observe(THREAD,"multibyte",inputRequest("multibyte","Trusted native facts"),"native-multibyte",new JSONObject().put("kind","host-evidence"));
        JSONObject archive=new JSONObject().put("threadId",THREAD).put("key","multibyte").put("kept",true)
                .put("ui",new JSONObject().put("fields",new JSONObject().put("notes",new JSONObject().put("draft","保留草稿").put("source",new JSONObject().put("type","text").put("value","source")))));
        JSONObject view=new JSONObject().put("readableText","");archive.put("view",view);
        view.put("readableText","界".repeat(128_000-archive.toString().length()));String archived=archive.toString();
        check(archived.length()==128_000&&archived.getBytes(StandardCharsets.UTF_8).length>128_000,"Legacy fixture does not exercise the actual UTF-16 allowance");
        File root=new File(f.context.getFilesDir(),"agent-ui-panels");root.mkdirs();File original=new File(root,THREAD+"__multibyte.json");Files.writeString(original.toPath(),archived);
        Fixture restarted=new Fixture(f.context);restarted.runtime.recover();restarted.io.drain();
        JSONObject state=restarted.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/multibyte"));
        check(state.getJSONObject("ui").getJSONObject("fields").getJSONObject("notes").getString("draft").equals("保留草稿"),"Multibyte migration lost the user draft");
        check(restarted.store.get("card_state_migrations","1/"+original.getName()).getBoolean("complete"),"Valid multibyte legacy record never completed migration");
        restarted.present("after-multibyte","New source");check(restarted.pin("after-multibyte").getBoolean("created"),"Multibyte legacy record globally blocked admission");
        check(Files.readString(original.toPath()).equals(archived),"Multibyte migration rewrote the archive");
        long revision=state.getLong("uiRevision");restarted.runtime.recover();restarted.io.drain();
        check(restarted.panels.cardView("read",new JSONObject().put("matterId",THREAD+"/multibyte")).getLong("uiRevision")==revision,"Multibyte migration imported the draft twice");
        Fixture oversized=new Fixture();File largeRoot=new File(oversized.context.getFilesDir(),"agent-ui-panels");largeRoot.mkdirs();File tooLarge=new File(largeRoot,"oversized.json");
        Files.writeString(tooLarge.toPath(),new JSONObject().put("threadId",THREAD).put("key","oversized").put("view","界".repeat(128_001)).toString());
        oversized.runtime.recover();oversized.io.drain();
        check(!oversized.store.get("card_state_migrations","1/"+tooLarge.getName()).getBoolean("complete"),"Legacy migration read beyond its bounded maximum");
    }

    public static void main(String[] args) throws Exception {
        dispatchInterleaving();sequencedPreflightInterleaving();scheduledInterleaving();retentionAndReplay();sourceRetention();inFlightSourceInterleaving();pinCommitRace();boundedCommandHistory();archiveFailureOrdering();notifications();
        primaryAdmissionAndPreferences();oldPrimarySnapshotBackfill();
        reservedPrimaryNamespace();primaryCapabilityRevocation();
        panelDirectoryAndRetries();sharedStateAndPublication();rejectedAndLateCompletions();migrationAndUnknownDelivery();
        preSubmissionRecovery();multibyteLegacyMigration();
        boundedCardViewHistory();existingCardViewHistory();cardViewCommitFailureOrdering();cardViewArchiveFailureOrdering();
        System.out.println("MatterRuntimeConcurrencyTest: "+assertions+" concurrency/retention/receipt assertions passed");
    }
}
