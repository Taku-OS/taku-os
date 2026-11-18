package org.agentos.lab;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Delivery/receipt safety is host-testable without a model, device, or fake business success. */
public final class WorkspaceExecutionTest {
    static int checks;
    static final String THREAD="00000000-0000-4000-8000-000000000001",KEY="task-0123456789abcdef0123";
    static final String GENERATION="00000000-0000-4000-8000-000000000002";
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void rejects(String code,Checked action)throws Exception {
        try{action.run();throw new AssertionError("Expected "+code);}catch(IOException error){check(error.getMessage().equals(code),"Unexpected "+error);}
    }
    static JSONObject request(String action)throws Exception {
        return new JSONObject().put("action",action).put("threadId",THREAD).put("screenKey",KEY)
                .put("generation",GENERATION).put("requestId",UUID.randomUUID().toString()).put("controlRevision",3)
                .put("text","Please choose a restaurant with a private room.");
    }
    static JSONObject query(JSONObject request)throws Exception {
        return new JSONObject(request.toString()).put("action","executionStatus");
    }
    static JSONObject await(WorkspaceExecution engine,JSONObject request,String state)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);JSONObject latest;
        do{latest=engine.control(query(request));if(latest.optString("status").equals(state))return latest;Thread.sleep(5);}
        while(System.nanoTime()<deadline);
        throw new AssertionError("Expected "+state+", got "+latest);
    }
    static final class Backend implements WorkspaceExecution.Backend {
        final AtomicInteger reserves=new AtomicInteger(),prepares=new AtomicInteger(),dispatches=new AtomicInteger(),rollbacks=new AtomicInteger(),releases=new AtomicInteger();
        final CompletableFuture<JSONObject> future=new CompletableFuture<>();
        final CountDownLatch prepared=new CountDownLatch(1),dispatched=new CountDownLatch(1);
        volatile Exception prepareFailure,dispatchFailure;
        volatile boolean definite=true,closed;
        String method="turn/start",target="";
        public void reserve(WorkspaceExecution.Request r){reserves.incrementAndGet();}
        public WorkspaceExecution.Prepared prepare(WorkspaceExecution.Request r,File directory)throws Exception {
            prepares.incrementAndGet();prepared.countDown();if(prepareFailure!=null)throw prepareFailure;
            JSONObject image=r.action.equals("returnAndResume")?new JSONObject().put("sha256","verified-original-image"):null;
            return new WorkspaceExecution.Prepared(method,new JSONObject().put("threadId",target.isEmpty()?r.thread:target)
                    .put("expectedTurnId","active-turn"),image,5,()->closed=true);
        }
        public WorkspaceExecution.Dispatch dispatch(WorkspaceExecution.Request r,WorkspaceExecution.Prepared p)throws Exception {
            dispatches.incrementAndGet();dispatched.countDown();if(dispatchFailure!=null)throw dispatchFailure;
            return new WorkspaceExecution.Dispatch(){public CompletableFuture<JSONObject> result(){return future;}public boolean definitelyRejected(){return definite;}};
        }
        public void rejected(WorkspaceExecution.Request r,WorkspaceExecution.Prepared p){rollbacks.incrementAndGet();}
        public void release(WorkspaceExecution.Request r){releases.incrementAndGet();}
        public JSONObject screen(WorkspaceExecution.Request r)throws Exception {return new JSONObject().put("controlRevision",5);}
    }
    static WorkspaceExecution engine(Path root,Backend backend)throws Exception {return new WorkspaceExecution(root.toFile(),backend);}
    static Path directory()throws Exception{return Files.createTempDirectory("taku-workspace-execution-");}
    static void duplicatesAndConcurrency()throws Exception {
        Path root=directory();Backend backend=new Backend();WorkspaceExecution engine=engine(root,backend);JSONObject r=request("returnAndResume");
        check(engine.control(r).getString("status").equals("pending"),"first request returns an honest pending receipt");
        check(backend.dispatched.await(2,TimeUnit.SECONDS),"request dispatches asynchronously");
        for(int i=0;i<8;i++)check(engine.control(new JSONObject(r.toString())).getString("status").equals("pending"),"duplicates only read pending receipt");
        check(backend.dispatches.get()==1,"pending duplicate never invokes native twice");
        rejects("WORKSPACE_EXECUTION_BUSY",()->engine.control(request("addRequirement")));
        rejects("WORKSPACE_REQUEST_ID_CONFLICT",()->engine.control(new JSONObject(r.toString()).put("controlRevision",4)));
        rejects("WORKSPACE_REQUEST_ID_CONFLICT",()->engine.control(query(r).put("generation",UUID.randomUUID().toString())));
        JSONObject disk=new JSONObject(Files.readString(root.resolve(r.getString("requestId")+".json")));
        check(disk.getString("phase").equals("dispatching")&&disk.has("observation"),"image evidence persisted before any acknowledgement");
        backend.future.complete(new JSONObject().put("turn",new JSONObject().put("id","native-turn")));
        JSONObject accepted=await(engine,r,"accepted");
        check(accepted.getString("turnId").equals("native-turn")&&accepted.getBoolean("screenshotAttached"),"native turn acknowledgement binds original observation");
        check(!accepted.getBoolean("taskCompleted")&&!accepted.getBoolean("requiresReconciliation"),"acknowledgement does not claim business completion");
        check(!accepted.has("request")&&!accepted.has("fingerprint"),"public receipt excludes raw stored request");
        check(backend.closed,"input/control lock released before completion callback");
        check(engine.control(r).getBoolean("accepted")&&backend.dispatches.get()==1,"accepted duplicate does not replay");
        Backend restored=new Backend();WorkspaceExecution reopened=engine(root,restored);
        check(reopened.control(r).getBoolean("accepted")&&restored.dispatches.get()==0,"accepted receipt survives broker restart without replay");
    }
    static void textIdentityAndSteer()throws Exception {
        Backend backend=new Backend();backend.method="turn/steer";WorkspaceExecution engine=engine(directory(),backend);JSONObject r=request("addRequirement");engine.control(r);
        check(backend.dispatched.await(2,TimeUnit.SECONDS),"requirement queued");
        rejects("WORKSPACE_REQUEST_ID_CONFLICT",()->engine.control(new JSONObject(r.toString()).put("text","Changed under the same id")));
        backend.future.complete(new JSONObject().put("turnId","active-turn"));JSONObject result=await(engine,r,"accepted");
        check(result.getString("method").equals("turn/steer")&&!result.getBoolean("screenshotAttached"),"requirement steers same turn without fake screenshot");
        check(result.getString("expectedTurnId").equals("active-turn"),"steer receipt records exact turn");
    }
    static void utf8ReceiptRecovery()throws Exception {
        Path root=directory();Backend backend=new Backend();WorkspaceExecution engine=engine(root,backend);
        JSONObject r=request("addRequirement").put("text","请换成有包间的餐厅。\nNo seafood — 海鲜过敏 🦐");engine.control(r);
        check(backend.dispatched.await(2,TimeUnit.SECONDS),"multilingual requirement dispatched once");
        backend.future.complete(new JSONObject().put("turnId","utf8-turn"));await(engine,r,"accepted");
        Backend restored=new Backend();WorkspaceExecution reopened=engine(root,restored);
        check(reopened.control(r).getBoolean("accepted")&&restored.dispatches.get()==0,"UTF-8 request identity survives restart without replay");
        rejects("WORKSPACE_REQUEST_ID_CONFLICT",()->reopened.control(new JSONObject(r.toString()).put("text","请换成无包间的餐厅。")));
        Path damaged=directory().resolve("damaged.json");Files.write(damaged,new byte[]{(byte)0xc3,0x28});
        try{WorkspaceExecution.read(damaged.toFile());throw new AssertionError("Malformed UTF-8 accepted");}
        catch(java.nio.charset.CharacterCodingException expected){check(true,"damaged receipt UTF-8 fails closed rather than replacing bytes");}
    }
    static void definiteFailures()throws Exception {
        for(String error:List.of("SCREEN_CONTROL_CHANGED","SCREEN_AGENT_PAUSED","WORKSPACE_MODEL_UNAVAILABLE")){
            Backend backend=new Backend();backend.prepareFailure=new IOException(error);WorkspaceExecution engine=engine(directory(),backend);JSONObject r=request("returnAndResume");engine.control(r);
            JSONObject failed=await(engine,r,"failed");check(failed.getString("error").equals(error)&&!failed.getBoolean("requiresReconciliation"),"definite preflight/stale/paused rejection stays explicit");
            engine.control(r);check(backend.prepares.get()==1&&backend.dispatches.get()==0,"failed ID never executes again");
        }
        Backend backend=new Backend();WorkspaceExecution engine=engine(directory(),backend);JSONObject r=request("returnAndResume");engine.control(r);backend.dispatched.await(2,TimeUnit.SECONDS);
        backend.future.completeExceptionally(new IOException("WORKSPACE_TURN_CHANGED"));JSONObject result=await(engine,r,"failed");
        check(result.getString("error").equals("WORKSPACE_TURN_CHANGED")&&backend.rollbacks.get()==1,"before-send turn change rolls returned ownership safely back");
        engine.control(r);check(backend.dispatches.get()==1,"native rejection replay prohibited under same ID");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(backend.releases.get()==0&&System.nanoTime()<deadline)Thread.sleep(2);
        check(backend.releases.get()==1,"definite failure releases reservation for explicit new user request");
        Backend fullQueue=new Backend();fullQueue.dispatchFailure=new IOException("WORKSPACE_RUNTIME_BUSY");WorkspaceExecution busy=engine(directory(),fullQueue);JSONObject queued=request("returnAndResume");busy.control(queued);
        check(await(busy,queued,"failed").getString("error").equals("WORKSPACE_RUNTIME_BUSY")&&fullQueue.rollbacks.get()==1,"prepared handoff with rejected enqueue rolls ownership back");
        check(fullQueue.closed,"enqueue failure releases control lock");
    }
    static void uncertaintyAndRestart()throws Exception {
        Path root=directory();Backend backend=new Backend();backend.definite=false;WorkspaceExecution engine=engine(root,backend);JSONObject r=request("returnAndResume");engine.control(r);backend.dispatched.await(2,TimeUnit.SECONDS);
        backend.future.completeExceptionally(new IOException("TRANSPORT_CLOSED"));JSONObject result=await(engine,r,"unknown");
        check(result.getBoolean("requiresReconciliation")&&backend.rollbacks.get()==0&&backend.releases.get()==0,"uncertain send neither replays nor falsely rolls back");
        rejects("WORKSPACE_EXECUTION_BUSY",()->engine.control(request("returnAndResume")));
        Backend restored=new Backend();WorkspaceExecution reopened=engine(root,restored);check(reopened.control(r).getString("status").equals("unknown"),"unknown outcome persists");
        check(restored.reserves.get()==1&&restored.prepares.get()==0,"restart reinstates execution barrier without retry");
        rejects("WORKSPACE_EXECUTION_BUSY",()->reopened.control(request("addRequirement")));
        Path pendingRoot=directory();Backend pending=new Backend();WorkspaceExecution old=engine(pendingRoot,pending);JSONObject pendingRequest=request("returnAndResume");old.control(pendingRequest);pending.dispatched.await(2,TimeUnit.SECONDS);
        Backend fresh=new Backend();WorkspaceExecution restarted=engine(pendingRoot,fresh);JSONObject restart=restarted.control(pendingRequest);
        check(restart.getString("status").equals("unknown")&&restart.getString("phase").equals("runtime_restarted"),"pending receipt becomes unknown after crash, never queued again");
        check(fresh.dispatches.get()==0&&fresh.reserves.get()==1,"crashed dispatch is reserved, not replayed");
        Backend interrupt=new Backend();interrupt.prepareFailure=new WorkspaceExecution.Uncertain("WORKSPACE_STATE_UNCONFIRMED",new IOException("STOP_UNCONFIRMED"));WorkspaceExecution interrupted=engine(directory(),interrupt);JSONObject ir=request("returnAndResume");interrupted.control(ir);
        check(await(interrupted,ir,"unknown").getBoolean("requiresReconciliation")&&interrupt.dispatches.get()==0,"unconfirmed interrupt never starts a new turn");
    }
    static void lateAcknowledgement()throws Exception {
        Backend backend=new Backend();WorkspaceExecution engine=new WorkspaceExecution(directory().toFile(),backend,50);JSONObject r=request("returnAndResume");engine.control(r);
        JSONObject unknown=await(engine,r,"unknown");check(unknown.getString("phase").equals("awaiting_native_ack")&&backend.releases.get()==0,"ack timeout remains reserved and unknown");
        check(engine.control(r).getString("status").equals("unknown")&&backend.dispatches.get()==1,"timeout query does not resend");
        rejects("WORKSPACE_EXECUTION_BUSY",()->engine.control(request("addRequirement")));
        backend.future.complete(new JSONObject().put("turnId","late-native-turn"));JSONObject accepted=await(engine,r,"accepted");
        check(accepted.getString("turnId").equals("late-native-turn")&&!accepted.getBoolean("requiresReconciliation"),"late native ack reconciles same request, without another send");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(backend.releases.get()==0&&System.nanoTime()<deadline)Thread.sleep(2);
        check(backend.releases.get()==1&&backend.dispatches.get()==1,"only actual late ack releases uncertain reservation");
    }
    static void imageEvidence()throws Exception {
        JSONObject before=new JSONObject().put("width",720).put("height",1280).put("frameSequence",10);
        JSONObject captured=new JSONObject(before.toString()).put("captureRequestId",UUID.randomUUID().toString()).put("capturedAt",105L);
        WorkspaceExecutionRuntime.validateFreshCapture(before,captured,"",100,110);check(true,"fresh export of static same-sequence frame is valid");
        rejects("WORKSPACE_CAPTURE_NOT_FRESH",()->WorkspaceExecutionRuntime.validateFreshCapture(before,captured,captured.getString("captureRequestId"),100,110));
        rejects("WORKSPACE_CAPTURE_NOT_FRESH",()->WorkspaceExecutionRuntime.validateFreshCapture(before,new JSONObject(captured.toString()).put("capturedAt",99),"",100,110));
        rejects("WORKSPACE_CAPTURE_NOT_FRESH",()->WorkspaceExecutionRuntime.validateFreshCapture(before,new JSONObject(captured.toString()).put("capturedAt",111),"",100,110));
        rejects("WORKSPACE_CAPTURE_NOT_FRESH",()->WorkspaceExecutionRuntime.validateFreshCapture(before,new JSONObject(captured.toString()).put("frameSequence",9),"",100,110));
        rejects("WORKSPACE_CAPTURE_NOT_FRESH",()->WorkspaceExecutionRuntime.validateFreshCapture(before,new JSONObject(captured.toString()).put("width",721),"",100,110));
        byte[] png=new byte[24];byte[] signature={(byte)137,80,78,71,13,10,26,10};System.arraycopy(signature,0,png,0,8);
        ByteBuffer.wrap(png,8,16).putInt(13).putInt(0x49484452).putInt(720).putInt(1280);
        WorkspaceExecutionRuntime.validatePng(png,720,1280);check(true,"PNG dimensions validated from original bytes");
        rejects("WORKSPACE_IMAGE_DIMENSIONS_CHANGED",()->WorkspaceExecutionRuntime.validatePng(png,721,1280));
        byte[] corrupt=png.clone();corrupt[12]='X';rejects("INVALID_WORKSPACE_IMAGE",()->WorkspaceExecutionRuntime.validatePng(corrupt,720,1280));
        byte[] wrong=png.clone();wrong[0]=0;rejects("INVALID_WORKSPACE_IMAGE",()->WorkspaceExecutionRuntime.validatePng(wrong,720,1280));
        check(!WorkspaceExecution.hash(png).equals(WorkspaceExecution.hash(corrupt)),"mutated image changes bound digest");
    }
    static void invalidRequests()throws Exception {
        WorkspaceExecution engine=engine(directory(),new Backend());
        rejects("INVALID_WORKSPACE_REQUEST",()->engine.control(request("returnAndResume").put("requestId","../../receipt")));
        rejects("INVALID_SCREEN_CONTROL_REVISION",()->engine.control(request("returnAndResume").put("controlRevision",3.2)));
        rejects("INVALID_WORKSPACE_REQUIREMENT",()->engine.control(request("addRequirement").put("text"," \n ")));
        rejects("WORKSPACE_REQUEST_NOT_FOUND",()->engine.control(query(request("returnAndResume"))));
    }
    static void deliveryTargetIdentity()throws Exception {
        String parent="00000000-0000-4000-8000-000000000003";
        for(String target:List.of(THREAD,parent))for(String method:List.of("turn/start","turn/steer")){
            Path root=directory();Backend backend=new Backend();backend.target=target;backend.method=method;
            WorkspaceExecution engine=engine(root,backend);JSONObject request=request("returnAndResume");engine.control(request);
            check(backend.dispatched.await(2,TimeUnit.SECONDS),"main/parent delivery reaches dispatch");
            Path file=root.resolve(request.getString("requestId")+".json");
            JSONObject saved=new JSONObject(Files.readString(file));
            check(saved.optString("targetThreadId").equals(target),"actual delivery target is durable before acknowledgement");
            backend.future.complete(new JSONObject().put("turnId","target-turn"));
            JSONObject accepted=await(engine,request,"accepted");
            check(accepted.getString("threadId").equals(THREAD),"screen/request identity stays with its original thread");
            check(accepted.getString("targetThreadId").equals(target)&&accepted.getString("turnId").equals("target-turn"),"turn pairs with its actual target, including parent relay");
            Backend restored=new Backend();restored.target=UUID.randomUUID().toString();
            WorkspaceExecution reopened=engine(root,restored);
            check(reopened.control(query(request)).getString("targetThreadId").equals(target)&&restored.dispatches.get()==0,"restart reads the saved target without inference or replay");
            JSONObject legacy=new JSONObject(Files.readString(file));legacy.remove("targetThreadId");WorkspaceExecution.write(file.toFile(),legacy);
            Backend oldBackend=new Backend();WorkspaceExecution old=engine(root,oldBackend);
            JSONObject historic=old.control(query(request));
            check(historic.getBoolean("accepted")&&!historic.has("targetThreadId")&&oldBackend.dispatches.get()==0,"legacy receipt keeps an unknown target and is never replayed");
        }
    }
    static void requestAppsContract()throws Exception {
        JSONArray namespaces=PhoneNativeTools.definitions();JSONObject namespace=namespaces.getJSONObject(0);
        check(namespace.getString("name").equals("taku_phone"),"inspect the actual model-visible phone tool namespace");
        JSONObject request=null;JSONArray tools=namespace.getJSONArray("tools");
        for(int i=0;i<tools.length();i++)if(tools.getJSONObject(i).optString("name").equals("request_apps"))request=tools.getJSONObject(i);
        check(request!=null,"request_apps is still registered");
        String description=request.getString("description");
        check(description.contains("normally")&&description.contains("taken over")&&description.contains("paused")
                &&description.contains("reserved")&&description.contains("turn ends"),"tool description distinguishes normal end from retained takeover/pause");
        JSONObject schema=request.getJSONObject("inputSchema");JSONArray required=schema.getJSONArray("required");
        check(required.length()==2&&schema.getJSONObject("properties").has("packages")&&schema.getJSONObject("properties").has("taskTitle")
                &&!schema.getBoolean("additionalProperties"),"description change preserves the existing request input contract");
    }
    public static void main(String[] args)throws Exception {
        duplicatesAndConcurrency();textIdentityAndSteer();utf8ReceiptRecovery();definiteFailures();uncertaintyAndRestart();lateAcknowledgement();imageEvidence();invalidRequests();
        deliveryTargetIdentity();requestAppsContract();
        System.out.println("WorkspaceExecutionTest: "+checks+" checks passed (host only; no device/model/business acceptance)");
    }
}
