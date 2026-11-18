package org.agentos.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Real enqueue, native RPC completion, durable coordinator and runtime cancellation. */
public final class NativeBridgeFamilyRoutingTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static String id(int n){return String.format("00000000-0000-0000-0000-%012d",n);}
    interface Ready{boolean get()throws Exception;}
    static void until(Ready ready,String message)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.get()&&System.nanoTime()<end)Thread.sleep(2);
        check(ready.get(),message);
    }
    static void cycle(SessionRemovals store)throws Exception{
        for(int n=0;n<300;n++){
            store.dispatch();until(()->store.activeAttempts()==0,"routed removal slice returned");
            JSONArray rows=store.list().getJSONArray("data");boolean ready=false;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                ready|=!row.getBoolean("completed")&&!row.getString("phase").equals("retry");
            }
            if(!ready)return;
        }
        throw new AssertionError("ready routed slices did not finish");
    }
    static void question(CodexAppServer server,int n,String thread)throws Exception{
        String turn="turn-"+thread;
        NativeBridge.event(new JSONObject().put("method","turn/started").put("params",new JSONObject().put("threadId",thread).put("turn",new JSONObject().put("id",turn))));
        NativeBridge.request(server,new JSONObject().put("id",n).put("method","item/tool/call").put("params",new JSONObject()
                .put("threadId",thread).put("turnId",turn).put("namespace","taku_phone").put("tool","ask_user")
                .put("arguments",new JSONObject().put("question","Continue?").put("options",new JSONArray().put("Continue")))));
        check(NativeBridge.hasPhoneQuestion(thread),"pending phone card exists before acceptance");
    }
    public static void main(String[] args)throws Exception{
        CodexAppServer server=new CodexAppServer();CodexAppServer.shared=server;
        DedicatedScreens registry=DedicatedScreensOnDevice.shared();
        registry.family=List.of(id(1),id(2),id(3));
        AtomicBoolean active=new AtomicBoolean(true),archived=new AtomicBoolean(false),ignore=new AtomicBoolean(false),pumping=new AtomicBoolean(true);
        AtomicInteger deletes=new AtomicInteger();AtomicLong now=new AtomicLong(1000);
        server.requestHook=(requestId,call)->{
            if(ignore.get())return;
            JSONObject result=new JSONObject();
            if(call.method.equals("thread/list")){
                JSONArray data=new JSONArray();
                if(call.params.has("ancestorThreadId")){
                    if(active.get()||archived.get())for(String member:registry.family)if(!member.equals(id(1)))data.put(new JSONObject().put("id",member));
                }else if(call.params.optBoolean("archived")?archived.get():active.get())data.put(new JSONObject().put("id",id(1)));
                result.put("data",data).put("nextCursor",JSONObject.NULL);
            }else if(call.method.equals("thread/loaded/list"))result.put("data",new JSONArray()).put("nextCursor",JSONObject.NULL);
            else if(call.method.equals("thread/delete")){deletes.incrementAndGet();active.set(false);archived.set(false);registry.family=List.of();}
            else if(call.method.equals("thread/archive")){active.set(false);archived.set(true);}
            NativeBridge.complete(new JSONObject().put("id",requestId).put("result",result));
        };
        SessionRemovals.Backend backend=new SessionRemovals.Backend(){
            public Set<String> family(String root)throws Exception{
                check(!NativeBridge.lockHeld(),"family RPC runs outside the bridge lock");
                Set<String> members=registry.removalFamily(root);members.addAll(PhoneSubAgents.gone(NativeBridge::askCleanupCodex,root));return members;
            }
            public JSONArray resources(Set<String> sessions){return registry.removalResources(sessions);}
            public SessionRemovals.State state(String root)throws Exception{return PhoneSubAgents.removalState(NativeBridge::askCleanupCodex,root);}
            public void apply(String root,String target)throws Exception{NativeBridge.askCleanupCodex("thread/"+target,new JSONObject().put("threadId",root));}
            public void cancel(String session)throws Exception{registry.removalCancelled(session);NativeBridge.cancelRemovedSession(session);}
            public void cleanup(JSONObject resource){registry.cleanupRemoval(resource);}
            public void forget(Set<String> sessions){}
        };
        try(SessionRemovals store=new SessionRemovals(Files.createTempDirectory("native-removals").toFile(),backend,message->{},now::get,80,30000,false)){
            java.lang.reflect.Field field=SessionRemovalRuntime.class.getDeclaredField("store");field.setAccessible(true);field.set(null,store);
            registry.removalFence(store::pending);
            Thread pump=new Thread(()->{while(pumping.get())try{NativeBridge.tick();Thread.sleep(2);}catch(Exception error){throw new RuntimeException(error);}},"test-native-pump");pump.setDaemon(true);pump.start();
            try{
                question(server,71,id(1));question(server,72,id(2));question(server,73,id(9));
                NativeBridge.Call submitted=new NativeBridge.Call("thread/delete",new JSONObject().put("threadId",id(1)));
                check(NativeBridge.enqueue(submitted),"native route accepts the durable intent");
                JSONObject accepted=submitted.result.get(1,TimeUnit.SECONDS);
                check(accepted.getBoolean("accepted")&&!accepted.getBoolean("completed"),"native caller receives acceptance independently of completion");
                check(registry.gone.isEmpty()&&deletes.get()==0,"acceptance never impersonates a native deletion response");
                NativeBridge.Call duplicate=new NativeBridge.Call("thread/delete",new JSONObject().put("threadId",id(1)));NativeBridge.enqueue(duplicate);
                check(duplicate.result.get().getString("requestId").equals(accepted.getString("requestId")),"both native submissions share one receipt");
                JSONObject agent=SessionRemovalRuntime.control(new JSONObject().put("action","submit").put("threadId",id(1)).put("target","archive"));
                check(agent.getString("requestId").equals(accepted.getString("requestId"))&&agent.getString("target").equals("delete"),"Agent submission shares the native intent and cannot downgrade deletion");
                check(SessionRemovalRuntime.control(new JSONObject().put("action","get").put("threadId",id(1))).getString("requestId").equals(accepted.getString("requestId")),"GUI and Agent progress use the same receipt");
                JSONObject rows=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("id",id(1))).put(new JSONObject().put("id",id(9))));
                SessionRemovalRuntime.filter(rows);
                check(rows.getJSONArray("data").length()==1&&rows.getJSONArray("data").getJSONObject(0).getString("id").equals(id(9)),"shared list filtering hides the accepted root");
                try{NativeBridge.enqueue(new NativeBridge.Call("turn/start",new JSONObject().put("threadId",id(1))));throw new AssertionError("pending family started a new turn");}
                catch(IOException expected){check(expected.getMessage().equals("SCREEN_SESSION_REMOVING"),"native new-turn admission uses the same pending state");}
                PhoneGestures.badCancellation=id(2);cycle(store);
                check(deletes.get()==1&&store.query(id(1),"").getBoolean("targetReached"),"native deletion is verified through ordinary and archived lists");
                check(!store.query(id(1),"").getBoolean("completed"),"one local failure retains the original pending intent");
                check(PhoneGestures.cancellations.contains(id(3)),"a failed member cannot skip the later child's cancellation");
                check(!registry.gone.contains(id(2))&&registry.gone.contains(id(3)),"failed cancellation never transfers that member's resources");
                check(!NativeBridge.hasPhoneQuestion(id(1))&&!NativeBridge.hasPhoneQuestion(id(2))&&NativeBridge.hasPhoneQuestion(id(9)),"family cards are cleared while unrelated cards remain");
                PhoneGestures.badCancellation="";now.addAndGet(30000);cycle(store);
                check(store.query(id(1),"").getBoolean("completed")&&deletes.get()==1,"retry completes saved cleanup without resending an already confirmed delete");
                check(!NativeBridge.phoneTurnActive(new JSONObject().put("threadId",id(2)).put("turnId","turn-"+id(2))),"old child turns remain stopped after the fence ends");
                NativeBridge.Call late=new NativeBridge.Call("thread/archive",new JSONObject().put("threadId",id(1)));late.cleanup=true;
                NativeBridge.calls.put(999,late);int cleaned=registry.gone.size();
                NativeBridge.complete(new JSONObject().put("id",999).put("result",new JSONObject()));
                check(registry.gone.size()==cleaned&&store.query(id(1),"").getString("target").equals("delete"),"a late raw archive reply cannot perform cleanup or alter current intent");
                ignore.set(true);
                for(int n=60;n<63;n++)store.submit(id(n),"delete");store.dispatch();
                until(()->store.activeAttempts()==0,"three unanswered real RPCs release all execution positions");
                check(NativeBridge.calls.isEmpty()&&NativeBridge.inbox.isEmpty(),"timed-out RPC records and unsent calls are retired");
                ignore.set(false);now.addAndGet(30000);cycle(store);
                for(int n=60;n<63;n++)check(store.query(id(n),"").getBoolean("completed"),"later jobs finish after RPC timeouts");
                try(CleanupWork.Held held=NativeBridge.lock()){
                    store.submit(id(70),"delete");store.dispatch();until(()->store.activeAttempts()==0,"bridge-state lock contention times out in the actual worker");
                    check(!store.query(id(70),"").getBoolean("completed"),"bridge lock timeout retains work for retry");
                }
                now.addAndGet(30000);cycle(store);
                check(store.query(id(70),"").getBoolean("completed"),"the same row recovers after bridge lock contention");
            }finally{pumping.set(false);pump.join(2000);field.set(null,null);}
        }
        NativeBridge.closed();
        System.out.println("NativeBridgeFamilyRoutingTest: "+checks+" checks passed (production routing and durable coordinator)");
    }
}
