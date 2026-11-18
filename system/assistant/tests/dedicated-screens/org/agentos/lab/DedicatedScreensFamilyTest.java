package org.agentos.lab;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import static org.agentos.lab.DedicatedScreensTest.*;

/** Durable removal attempts against the real dedicated-screen ledger. */
public final class DedicatedScreensFamilyTest {
    interface Ready { boolean ready() throws Exception; }
    static void until(Ready ready,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.ready()&&System.nanoTime()<end)Thread.sleep(2);
        check(ready.ready(),message);
    }
    static final class Fixture implements AutoCloseable {
        final Rig parts=new Rig();
        final AtomicBoolean active=new AtomicBoolean(true),archived=new AtomicBoolean(false),incomplete=new AtomicBoolean(false);
        final AtomicLong now=new AtomicLong(1000);
        final List<String> warnings=new CopyOnWriteArrayList<>(),cancelled=new CopyOnWriteArrayList<>();
        final Path directory=Files.createTempDirectory("family-removal");
        final PhoneSubAgents.Rpc rpc=(method,params)->{
            check(!this.screens.lockHeld(),"SDK queries run outside the ledger lock");
            if(method.equals("thread/loaded/list"))return new JSONObject().put("data",new JSONArray()).put("nextCursor",JSONObject.NULL);
            if(method.equals("thread/list")&&!params.has("ancestorThreadId")) {
                if(incomplete.get())return new JSONObject().put("data",new JSONArray()).put("nextCursor","incomplete");
                JSONArray data=new JSONArray();
                if(params.optBoolean("archived")?archived.get():active.get())data.put(new JSONObject().put("id",session(1)));
                return new JSONObject().put("data",data).put("nextCursor",JSONObject.NULL);
            }
            return parts.codex.call(method,params);
        };
        DedicatedScreens screens;
        final SessionRemovals removals;
        volatile java.util.function.Consumer<String> cancelling=session->{};
        int deletes;
        Fixture()throws Exception {
            screens=new DedicatedScreens(parts.host,parts.tasks,parts.clock,rpc,()->"en",warnings::add);
            removals=new SessionRemovals(directory.toFile(),new SessionRemovals.Backend(){
                public Set<String> family(String root)throws Exception {
                    Set<String> result=screens.removalFamily(root);result.addAll(PhoneSubAgents.gone(rpc,root));return result;
                }
                public JSONArray resources(Set<String> sessions)throws Exception{return screens.removalResources(sessions);}
                public SessionRemovals.State state(String root)throws Exception{return PhoneSubAgents.removalState(rpc,root);}
                public void apply(String root,String target)throws Exception {
                    JSONObject saved=new JSONObject(Files.readString(directory.resolve(root+".json")));
                    check(saved.getBoolean("familySaved"),"full family is on disk before native metadata is destroyed");
                    active.set(false);archived.set(target.equals("archive"));deletes++;
                    parts.codex.parents.clear();
                }
                public void cancel(String session)throws Exception {
                    check(!screens.lockHeld(),"runtime cancellation runs outside the ledger lock");
                    screens.removalCancelled(session);cancelled.add(session);cancelling.accept(session);
                }
                public void cleanup(JSONObject resource)throws Exception{screens.cleanupRemoval(resource);}
                public void forget(Set<String> sessions){screens.forgetRemoved(sessions);}
            },warnings::add,now::get,80,30000,false);
            screens.removalFence(removals::pending);
        }
        JSONObject acquire(int id,String pkg)throws Exception{return screens.request(session(id),apps(pkg),"family task");}
        void cycle()throws Exception{
            for(int n=0;n<300;n++){
                removals.dispatch();until(()->removals.activeAttempts()==0,"bounded removal slice returned");
                JSONObject row=progress();
                if(row.getBoolean("completed")||row.getString("phase").equals("retry"))return;
            }
            throw new AssertionError("ready removal slices did not finish");
        }
        JSONObject progress()throws Exception{return removals.query(session(1),"");}
        public void close(){removals.close();}
    }
    static void savedFamily()throws Exception {
        for(String owner:List.of("agent","user","paused"))try(Fixture f=new Fixture()){
            f.parts.codex.parents.put(session(2),session(1));f.parts.codex.parents.put(session(3),session(2));
            f.parts.codex.archived.add(session(3));f.parts.codex.page=1;
            f.acquire(1,"com.settings");JSONObject child=f.acquire(2,"com.clock");f.acquire(3,"com.files");f.acquire(4,"com.other");
            if(!owner.equals("agent")){
                f.screens.beginPause(session(2),child.getString("screenKey"),child.getString("generation"));
                f.screens.finishControl(session(2),child.getString("screenKey"),child.getString("generation"),owner);
            }
            JSONObject first=f.removals.submit(session(1),"delete");
            check(first.getString("requestId").equals(f.removals.submit(session(1),"delete").getString("requestId")),"duplicate requests reuse the real ledger's durable intent");
            check(!f.screens.agentAdmitted(session(2))&&f.screens.agentAdmitted(session(4)),"family ancestry is fenced while unrelated turns are admitted");
            rejects("SCREEN_SESSION_REMOVING",()->f.acquire(2,"com.extra"));
            for(int childId:List.of(5,6)){
                String parentId=session(childId==5?1:5);
                f.screens.observeRemovalThread(new JSONObject().put("id",session(childId)).put("source",new JSONObject()
                    .put("subAgent",new JSONObject().put("thread_spawn",new JSONObject().put("parent_thread_id",parentId)))));
                check(!f.screens.agentAdmitted(session(childId)),"newly spawned descendants are fenced before family enumeration finishes");
                rejects("SCREEN_SESSION_REMOVING",()->f.acquire(childId,"com.late"));
            }
            f.cancelling=session->{check(!f.screens.agentAdmitted(session),"family remains fenced throughout cancellation");};
            f.cycle();
            check(f.progress().getBoolean("completed"),"ordinary, taken-over and paused families all finish");
            check(f.screens.status(session(0)).getJSONArray("apps").length()==1,"only the unrelated App remains held");
            check(f.cancelled.containsAll(List.of(session(1),session(2),session(3))),"saved family includes archived descendants and grandchildren");
            check(f.parts.host.stopped.isEmpty(),"healthy cleanup keeps physical screens for idle reuse");
        }
    }
    static void lockTimeoutAndRetry()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject screen=f.acquire(1,"com.clock");
            f.parts.tasks.open("com.clock",screen.getInt("displayId"));
            java.util.concurrent.locks.ReentrantLock input=f.screens.exact(session(1),screen.getString("screenKey"),screen.getString("generation")).inputLock();
            input.lock();
            try{
                f.removals.submit(session(1),"delete");f.cycle();
                check(!f.progress().getBoolean("completed")&&f.progress().getBoolean("targetReached"),"an input-lock timeout retains the native-complete cleanup job");
                check(f.screens.status(session(0)).getJSONArray("apps").length()==1,"lock timeout cannot publish a dirty screen or App as free");
            }finally{input.unlock();}
            f.now.addAndGet(30000);f.cycle();
            check(f.progress().getBoolean("completed")&&f.deletes==1,"after the lock frees only cleanup is retried");
            check(f.screens.status(session(0)).getJSONArray("apps").isEmpty(),"completed lock retry releases the old App hold");
            check(f.parts.tasks.tasks.isEmpty(),"completed lock retry removes the old Android task");
            check(f.screens.status(session(0)).getInt("idleScreens")==1,"completed lock retry returns the screen to idle capacity");
            JSONObject next=f.acquire(2,"com.clock");
            check(next.getString("screenKey").equals(screen.getString("screenKey")),"another session can reuse the cleaned screen and App");
        }
    }
    static void failedCloseAndRetry()throws Exception {
        for(String target:List.of("delete","archive"))try(Fixture f=new Fixture()){
            JSONObject screen=f.acquire(1,"com.clock");
            f.parts.tasks.open("com.clock",screen.getInt("displayId"));
            f.parts.tasks.removeFailure="injected task cleanup failure";
            f.parts.host.stopFailure="injected host stop failure";
            f.removals.submit(session(1),target);f.cycle();
            check(f.progress().getBoolean("targetReached")&&!f.progress().getBoolean("completed"),"native removal succeeds while failed resource cleanup stays pending");
            check(f.screens.list(session(1)).getJSONObject(0).getString("status").equals("cleanup_failed"),"failed cleanup keeps the releasing screen in the ledger");
            check(f.screens.status(session(0)).getJSONArray("apps").getJSONObject(0).getString("heldBy").equals(session(1)),"failed cleanup retains the old App hold");
            check(f.parts.host.stopped.isEmpty(),"an unconfirmed stop does not release the physical screen");

            CompletableFuture<JSONObject> granted=new CompletableFuture<>();
            Thread waiter=daemon(()->{
                try{granted.complete(f.acquire(2,"com.clock"));}
                catch(Exception error){granted.completeExceptionally(error);}
            });
            try{
                until(()->f.screens.status(session(0)).getJSONArray("apps").getJSONObject(0).getInt("waiting")==1,"another session waits for the retained App");
                check(!granted.isDone(),"the waiting session cannot take the App before cleanup is confirmed");
                f.parts.host.beforeStop=key->check(!f.screens.lockHeld(),"retry stops the host outside the ledger lock");
                f.parts.host.stopFailure="";
                f.now.addAndGet(30000);f.cycle();
                check(f.progress().getBoolean("completed")&&f.deletes==1,"successful cleanup retry completes without repeating native removal");
                check(f.parts.host.stopped.contains(screen.getString("screenKey")),"retry confirms the old physical screen has stopped");
                check(f.screens.removalResources(Set.of(session(1))).isEmpty(),"completed close retry removes the old ledger occupancy");
                check(held(granted.get(1,TimeUnit.SECONDS)).equals(List.of("com.clock")),"successful retry wakes and grants the waiting App request");
                check(f.screens.status(session(0)).getJSONArray("screens").length()==1,"the stopped screen no longer consumes capacity");
            }finally{waiter.interrupt();waiter.join(1000);}
        }
    }
    static void ownershipAndRestart()throws Exception {
        Rig rig=new Rig();JSONObject old=rig.request(1,"old","com.clock");
        JSONArray evidence=rig.screens.removalResources(Set.of(session(1)));
        rig.screens.turnEnded(session(1)).join(5000);
        JSONObject reused=rig.request(1,"next turn","com.clock");
        check(old.getString("screenKey").equals(reused.getString("screenKey")),"test reuses the same session and physical generation");
        try(CleanupWork scope=new CleanupWork(100)){rig.screens.cleanupRemoval(evidence.getJSONObject(0));}
        check(app(rig,"com.clock")!=null,"durable old cleanup cannot clear the same session's new occupancy");
        Rig restarted=new Rig();restarted.host.slots.putAll(rig.host.slots);
        try(CleanupWork scope=new CleanupWork(100)){restarted.screens.cleanupRemoval(evidence.getJSONObject(0));}
        check(restarted.host.stopped.contains(old.getString("screenKey")),"saved key and generation can stop an orphan after broker restart");
        Rig replaced=new Rig();replaced.host.slots.put(old.getString("screenKey"),replaced.host.info(old.getString("screenKey"),9));
        try(CleanupWork scope=new CleanupWork(100)){replaced.screens.cleanupRemoval(evidence.getJSONObject(0));}
        check(replaced.host.stopped.isEmpty(),"a replacement host generation is protected after restart");
    }
    static void ownershipChangesWhileWaiting()throws Exception {
        Rig rig=new Rig();JSONObject old=rig.request(1,"old","com.clock");
        JSONObject evidence=rig.screens.removalResources(Set.of(session(1))).getJSONObject(0);
        PhoneExecutionScreens.Lease lease=rig.screens.exact(session(1),old.getString("screenKey"),old.getString("generation"));
        java.util.concurrent.locks.ReentrantLock input=lease.inputLock();
        java.lang.reflect.Field releasing=lease.getClass().getDeclaredField("releasing");releasing.setAccessible(true);
        java.lang.reflect.Method finish=DedicatedScreens.class.getDeclaredMethod("finishTurn",lease.getClass());finish.setAccessible(true);
        CompletableFuture<Void> finished=new CompletableFuture<>();Thread nextCleanup=null;
        CountDownLatch cleanupGate=new CountDownLatch(1);
        input.lock();
        try{
            daemon(()->{try(CleanupWork scope=new CleanupWork(2000)){
                rig.screens.cleanupRemoval(evidence);finished.complete(null);
            }catch(Exception error){finished.completeExceptionally(error);}});
            until(()->releasing.getBoolean(lease),"old cleanup passed the first ownership check and is waiting for input");
            finish.invoke(rig.screens,lease); // A normal release completes before the old waiter obtains input.
            JSONObject next=rig.request(1,"next","com.clock");
            rig.tasks.open("com.clock",next.getInt("displayId"));
            rig.tasks.removeGate=cleanupGate;rig.tasks.removing=new CountDownLatch(1);
            nextCleanup=rig.screens.turnEnded(session(1));
        }finally{input.unlock();}
        try{
            finished.get(1,TimeUnit.SECONDS);
            check(!rig.tasks.tasks.isEmpty(),"old cleanup rechecks allocation after input wait and leaves the new turn's task untouched");
        }finally{
            cleanupGate.countDown();if(nextCleanup!=null)nextCleanup.join(5000);
        }
    }
    static void ledgerLockTimeout()throws Exception {
        try(Fixture f=new Fixture()){
            f.acquire(1,"com.clock");
            try(CleanupWork.Held held=f.screens.lock()){
                f.removals.submit(session(1),"delete");f.cycle();
                check(f.deletes==0&&!f.progress().getBoolean("completed"),"real ledger lock contention exits before native mutation");
            }
            f.now.addAndGet(30000);f.cycle();
            check(f.progress().getBoolean("completed"),"the same row recovers when the real ledger lock becomes available");
        }
    }
    static void lateOpening()throws Exception {
        try(Fixture f=new Fixture()){
            f.parts.codex.parents.put(session(2),session(1));f.parts.host.startGate=new CountDownLatch(1);
            CompletableFuture<String> result=new CompletableFuture<>();
            Thread opening=daemon(()->{try{f.acquire(2,"com.clock");result.complete("granted");}catch(Exception error){result.complete(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}});
            until(()->f.screens.status(session(0)).getJSONArray("apps").length()==1,"opening entered before acceptance");
            f.removals.submit(session(1),"delete");
            f.cancelling=session->{if(session.equals(session(2))){opening.interrupt();f.parts.host.startGate.countDown();try{opening.join(2000);}catch(InterruptedException error){throw new RuntimeException(error);}}};
            f.cycle();opening.join(2000);
            check(!result.get(2,TimeUnit.SECONDS).equals("granted"),"an opening in flight cannot grant new resources after acceptance");
            check(f.screens.status(session(0)).getJSONArray("apps").length()==0,"no late opening leaves an App held");
        }
    }
    static void incompleteLists()throws Exception {
        for(String mode:List.of("limit","cycle","empty")){
            AtomicInteger page=new AtomicInteger();PhoneSubAgents.Rpc rpc=(method,params)->{
                int n=page.incrementAndGet();JSONArray data=new JSONArray();if(!mode.equals("empty"))data.put(new JSONObject().put("id",session(n)));
                return new JSONObject().put("data",data).put("nextCursor",mode.equals("cycle")?"again":Integer.toString(n));
            };
            rejects("SESSION_FAMILY_INCOMPLETE",()->PhoneSubAgents.gone(rpc,session(1)));
        }
        JSONObject protocol=new JSONObject(Files.readString(Path.of("system/assistant/assets/native/protocol.json")));
        List<Object> kinds=protocol.getJSONObject("definitions").getJSONObject("ThreadSourceKind").getJSONArray("enum").toList();
        PhoneSubAgents.Rpc sdk=(method,params)->{
            check(params.getJSONArray("sourceKinds").toList().containsAll(kinds),"all pinned source kinds participate in family queries");
            return new JSONObject().put("data",new JSONArray()).put("nextCursor",JSONObject.NULL);
        };
        check(PhoneSubAgents.gone(sdk,session(1)).isEmpty(),"complete empty enumeration is supported");
    }
    public static void main(String[] args)throws Exception {
        WorkspacePolicy.store=new WorkspacePolicy.Store(Files.createTempDirectory("family-policy").resolve("policy.json").toFile());
        savedFamily();failedCloseAndRetry();lockTimeoutAndRetry();ledgerLockTimeout();ownershipAndRestart();ownershipChangesWhileWaiting();lateOpening();incompleteLists();
        System.out.println("DedicatedScreensFamilyTest: "+checks+" checks passed (durable coordinator and real ledger)");
    }
}
