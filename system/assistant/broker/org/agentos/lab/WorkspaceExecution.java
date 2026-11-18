package org.agentos.lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Durable delivery receipts for explicit workspace requests, not a task/result store. */
final class WorkspaceExecution {
    interface Backend {
        void reserve(Request request) throws Exception;
        Prepared prepare(Request request, File directory) throws Exception;
        Dispatch dispatch(Request request, Prepared prepared) throws Exception;
        void rejected(Request request, Prepared prepared) throws Exception;
        void release(Request request);
        JSONObject screen(Request request) throws Exception;
    }
    interface Dispatch {
        CompletableFuture<JSONObject> result();
        boolean definitelyRejected();
    }
    static final class Prepared implements AutoCloseable {
        final String method;
        final JSONObject params, observation;
        final long controlRevision;
        JSONObject runtimeGuard;
        private Runnable unlock;
        Prepared(String method, JSONObject params, JSONObject observation, long revision, Runnable unlock) {
            this.method=method;this.params=params;this.observation=observation;
            this.controlRevision=revision;this.unlock=unlock;
        }
        public void close(){if(unlock!=null){Runnable value=unlock;unlock=null;value.run();}}
    }
    static final class Uncertain extends IOException {
        Uncertain(String message,Throwable cause){super(message,cause);}
    }
    static final class Request {
        final String action,thread,key,generation,id,text;
        final long revision;
        Request(JSONObject json, boolean query) throws Exception {
            action=json.getString("action");thread=json.getString("threadId");key=json.getString("screenKey");
            generation=json.getString("generation");id=json.getString("requestId");
            if(!thread.matches("[a-f0-9-]{36}")||!key.matches("task-[a-f0-9]{20}")
                    ||!generation.matches("[a-f0-9-]{36}")
                    ||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                throw new IOException("INVALID_WORKSPACE_REQUEST");
            if(query){revision=-1;text="";return;}
            if(!action.equals("returnAndResume")&&!action.equals("addRequirement"))throw new IOException("UNKNOWN_WORKSPACE_ACTION");
            Object number=json.get("controlRevision");
            if(!(number instanceof Integer)&&!(number instanceof Long))throw new IOException("INVALID_SCREEN_CONTROL_REVISION");
            revision=((Number)number).longValue();if(revision<0)throw new IOException("INVALID_SCREEN_CONTROL_REVISION");
            text=action.equals("addRequirement")?json.getString("text"):"";
            if(action.equals("addRequirement")&&(text.isBlank()||text.length()>4000))throw new IOException("INVALID_WORKSPACE_REQUIREMENT");
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("action",action).put("threadId",thread).put("screenKey",key)
                    .put("generation",generation).put("requestId",id).put("controlRevision",revision).put("text",text);
        }
        String fingerprint() throws Exception {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)){
                for(String value:new String[]{action,thread,key,generation,Long.toString(revision),text}){
                    byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);
                }
            }
            return hash(bytes.toByteArray());
        }
        void sameLease(Request other) throws IOException {
            if(!thread.equals(other.thread)||!key.equals(other.key)||!generation.equals(other.generation))
                throw new IOException("WORKSPACE_REQUEST_ID_CONFLICT");
        }
    }
    private final File root;
    private final Backend backend;
    private final long ackTimeoutMillis;
    private final Map<String,JSONObject> receipts=new HashMap<>();
    private final Map<String,String> active=new HashMap<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"workspace-execution");t.setDaemon(true);return t;});
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"workspace-receipts");t.setDaemon(true);return t;});

    WorkspaceExecution(File root,Backend backend)throws Exception {
        this(root,backend,40000);
    }
    WorkspaceExecution(File root,Backend backend,long ackTimeoutMillis)throws Exception {
        if(ackTimeoutMillis<1)throw new IllegalArgumentException("ackTimeoutMillis");
        this.root=root;this.backend=backend;this.ackTimeoutMillis=ackTimeoutMillis;privateDirectory(root);
        File[] files=root.listFiles((dir,name)->name.endsWith(".json"));
        if(files!=null)for(File file:files){
            JSONObject receipt=new JSONObject(read(file));Request request=new Request(receipt.getJSONObject("request"),false);
            if(!file.getName().equals(request.id+".json"))throw new IOException("INVALID_WORKSPACE_RECEIPT");
            if(receipt.optString("status").equals("pending")){
                receipt.put("status","unknown").put("phase","runtime_restarted")
                        .put("requiresReconciliation",true).put("error","WORKSPACE_RUNTIME_RESTARTED");
                write(file,receipt);
            }
            receipts.put(request.id,receipt);
            if(receipt.optString("status").equals("unknown")){
                if(active.putIfAbsent(request.thread,request.id)!=null)throw new IOException("WORKSPACE_RECEIPT_CONFLICT");
                backend.reserve(request);
            }
        }
    }

    synchronized JSONObject control(JSONObject json)throws Exception {
        boolean query=json.optString("action").equals("executionStatus");
        Request request=new Request(json,query);
        JSONObject prior=receipts.get(request.id);
        if(prior!=null){
            Request original=new Request(prior.getJSONObject("request"),false);original.sameLease(request);
            if(!query&&!prior.getString("fingerprint").equals(request.fingerprint()))
                throw new IOException("WORKSPACE_REQUEST_ID_CONFLICT");
            return view(original,prior);
        }
        if(query)throw new IOException("WORKSPACE_REQUEST_NOT_FOUND");
        if(active.containsKey(request.thread))throw new IOException("WORKSPACE_EXECUTION_BUSY");
        JSONObject receipt=new JSONObject().put("schema",1).put("request",request.json())
                .put("fingerprint",request.fingerprint()).put("status","pending").put("phase","queued")
                .put("requiresReconciliation",false).put("createdAt",System.currentTimeMillis());
        write(new File(root,request.id+".json"),receipt);receipts.put(request.id,receipt);active.put(request.thread,request.id);
        workers.execute(()->perform(request));
        return view(request,receipt);
    }

    private void perform(Request request){
        Prepared prepared=null;
        try{
            backend.reserve(request);
            update(request,new JSONObject().put("phase","preparing"));
            File directory=new File(root,request.id);privateDirectory(directory);
            prepared=backend.prepare(request,directory);
            JSONObject evidence=new JSONObject().put("method",prepared.method).put("phase","dispatching")
                    .put("targetThreadId",prepared.params.getString("threadId"))
                    .put("expectedTurnId",prepared.params.optString("expectedTurnId"))
                    .put("dispatchControlRevision",prepared.controlRevision);
            if(prepared.observation!=null)evidence.put("observation",prepared.observation);
            // Persist before enqueue: a crash may have delivered a request even without an acknowledgement.
            update(request,evidence);
            Dispatch dispatch=backend.dispatch(request,prepared);
            prepared.close();
            Prepared sent=prepared;
            dispatch.result().whenComplete((result,error)->workers.execute(()->finish(request,sent,dispatch,result,error)));
            deadlines.schedule(()->{
                if(!dispatch.result().isDone())try{
                    updatePending(request,new JSONObject().put("status","unknown").put("phase","awaiting_native_ack")
                            .put("requiresReconciliation",true).put("error","WORKSPACE_NATIVE_ACK_TIMEOUT"));
                }catch(Exception ignored){/* Durable dispatching already prevents replay after restart. */}
            },ackTimeoutMillis,TimeUnit.MILLISECONDS);
        }catch(Exception error){
            try{
                if(prepared!=null)backend.rejected(request,prepared);
                boolean unknown=error instanceof Uncertain;
                update(request,new JSONObject().put("status",unknown?"unknown":"failed").put("phase","preparation_failed")
                        .put("requiresReconciliation",unknown).put("error",errorCode(error)));
                if(!unknown)release(request);
            }catch(Exception saveOrCleanupFailed){
                try{update(request,new JSONObject().put("status","unknown").put("phase","cleanup_unconfirmed")
                        .put("requiresReconciliation",true).put("error","WORKSPACE_STATE_UNCONFIRMED"));}catch(Exception ignored){}
            }
        }finally{if(prepared!=null)prepared.close();}
    }

    private void finish(Request request,Prepared prepared,Dispatch dispatch,JSONObject result,Throwable error){
        try{
            if(error!=null){
                boolean failed=dispatch.definitelyRejected();
                if(failed)backend.rejected(request,prepared);
                update(request,new JSONObject().put("status",failed?"failed":"unknown").put("phase",failed?"native_rejected":"delivery_unknown")
                        .put("requiresReconciliation",!failed).put("error",errorCode(error)));
                if(failed)release(request);
                return;
            }
            String turn=result.optString("turnId",prepared.params.optString("expectedTurnId"));
            if(result.optJSONObject("turn")!=null)turn=result.getJSONObject("turn").optString("id",turn);
            if(turn.isEmpty()){
                update(request,new JSONObject().put("status","unknown").put("phase","native_result_unrecognized")
                        .put("requiresReconciliation",true).put("error","WORKSPACE_NATIVE_RESULT_UNRECOGNIZED"));
                return;
            }
            update(request,new JSONObject().put("status","accepted").put("phase","native_accepted")
                    .put("turnId",turn).put("screenshotAttached",prepared.observation!=null)
                    .put("requiresReconciliation",false).put("error","")
                    .put("acceptedAt",System.currentTimeMillis()).put("taskCompleted",false));
            release(request);
        }catch(Exception failure){
            try{update(request,new JSONObject().put("status","unknown").put("phase","receipt_unconfirmed")
                    .put("requiresReconciliation",true).put("error","WORKSPACE_RECEIPT_UNCONFIRMED"));}catch(Exception ignored){}
        }
    }

    private void release(Request request){backend.release(request);synchronized(this){active.remove(request.thread,request.id);}}
    private synchronized void updatePending(Request request,JSONObject patch)throws Exception {
        if(receipts.get(request.id).optString("status").equals("pending"))update(request,patch);
    }
    private synchronized void update(Request request,JSONObject patch)throws Exception {
        JSONObject next=new JSONObject(receipts.get(request.id).toString());
        for(Iterator<String> keys=patch.keys();keys.hasNext();){String key=keys.next();next.put(key,patch.get(key));}
        next.put("updatedAt",System.currentTimeMillis());
        write(new File(root,request.id+".json"),next);receipts.put(request.id,next);
    }
    private JSONObject view(Request request,JSONObject receipt)throws Exception {
        JSONObject result=new JSONObject(receipt.toString());result.remove("request");result.remove("fingerprint");
        result.put("requestId",request.id).put("action",request.action).put("threadId",request.thread)
                .put("screenKey",request.key).put("generation",request.generation)
                .put("accepted",result.optString("status").equals("accepted"));
        try{result.put("screen",backend.screen(request));}catch(Exception unavailable){result.put("screenAvailable",false);}
        return result;
    }
    static String errorCode(Throwable error){Throwable value=error;while(value.getCause()!=null)value=value.getCause();String message=value.getMessage();return message!=null&&message.matches("[A-Z0-9_]{1,100}")?message:"WORKSPACE_EXECUTION_FAILED";}
    static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static void privateDirectory(File file)throws Exception {Files.createDirectories(file.toPath());Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rwx------"));}
    static String read(File file)throws Exception {
        if(file.length()>2_000_000)throw new IOException("INVALID_WORKSPACE_RECEIPT");
        // Android exposes readAllBytes, but not the JDK 11 Files.readString API.
        // Keep strict UTF-8 decoding so damaged receipts fail closed on restart.
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
    }
    static void write(File file,JSONObject json)throws Exception {
        File temporary=new File(file.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Files.setPosixFilePermissions(temporary.toPath(),PosixFilePermissions.fromString("rw-------"));
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
