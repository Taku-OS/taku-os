package org.agentos.lab;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the real broker pump against a controlled stdio runtime. */
public final class CodexTaskOwnershipTest {
    private static final String THREAD="10000000-0000-4000-8000-000000000001";
    private static final String OTHER="20000000-0000-4000-8000-000000000002";
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    private static JSONObject result(JSONObject request,JSONObject value) {return new JSONObject().put("id",request.getInt("id")).put("result",value);}
    private static JSONObject turn(String id,String status) {return new JSONObject().put("id",id).put("status",status);}
    private static void emit(JSONObject value) {System.out.println(value);System.out.flush();}
    private static void event(String method,String thread,String turn,JSONObject item) {
        JSONObject params=new JSONObject().put("threadId",thread).put("turnId",turn);
        if(method.startsWith("turn/"))params.put("turn",turn(turn,method.equals("turn/completed")?"completed":"inProgress"));
        if(item!=null)params.put("item",item);
        emit(new JSONObject().put("method",method).put("params",params));
    }
    private static JSONObject message(String id) {return new JSONObject().put("id",id).put("type","agentMessage").put("text",id);}
    private static void runtime() throws Exception {
        JSONObject owner=null;List<JSONObject> nativeStarts=new ArrayList<>();
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            String line;
            while((line=input.readLine())!=null) {
                JSONObject request=new JSONObject(line);String method=request.optString("method");
                if(!request.has("id"))continue;
                JSONObject params=request.optJSONObject("params");
                if(method.equals("turn/start")) {
                    String text=params.getJSONArray("input").getJSONObject(0).getString("text");
                    if(text.startsWith("owned:"))owner=request;else nativeStarts.add(request);
                    if(owner==null||nativeStarts.size()!=3)continue;
                    for(int i=0;i<2;i++) {
                        JSONObject nativeStart=nativeStarts.get(i);String thread=nativeStart.getJSONObject("params").getString("threadId"),turn="foreign-"+i;
                        emit(result(nativeStart,new JSONObject().put("turn",turn(turn,"inProgress"))));
                        event("turn/started",thread,turn,null);event("item/completed",thread,turn,message(turn));
                    }
                    event("turn/started",THREAD,"owned-turn",null);
                    event("item/completed",THREAD,"owned-turn",message("owned-reply"));
                    if(owner.getJSONObject("params").getJSONArray("input").getJSONObject(0).getString("text").equals("owned:reject")) {
                        emit(new JSONObject().put("id",owner.getInt("id")).put("error",new JSONObject().put("code",-32600).put("message","rejected")));
                    } else emit(result(owner,new JSONObject().put("turn",turn("owned-turn","inProgress"))));
                    JSONObject late=nativeStarts.get(2);
                    emit(result(late,new JSONObject().put("turn",turn("late-foreign","inProgress"))));
                    event("turn/started",THREAD,"late-foreign",null);
                    event("item/completed",THREAD,"late-foreign",message("late-foreign"));
                    emit(new JSONObject().put("method","turn/completed").put("params",new JSONObject().put("threadId",THREAD).put("turn",turn("late-foreign","interrupted"))));
                    event("turn/completed",THREAD,"owned-turn",null);
                } else if(method.equals("account/read"))emit(result(request,new JSONObject().put("account",new JSONObject().put("type","host"))));
                else if(method.equals("model/list"))emit(result(request,new JSONObject().put("data",new JSONArray().put(new JSONObject().put("model","host-model").put("isDefault",true)))));
                else if(method.equals("thread/resume")||method.equals("thread/read"))emit(result(request,new JSONObject().put("model","host-model").put("thread",new JSONObject().put("id",params.getString("threadId")).put("turns",new JSONArray()))));
                else emit(result(request,new JSONObject()));
            }
        }
    }
    private static void ownership(String mode) throws Exception {
        File root=Files.createTempDirectory("codex-task-ownership-").toFile();TaskBroker.ROOT=root;
        File directory=new File(root,"101");directory.mkdirs();File info=new File(directory,"runtime-info.json");
        String prior=new JSONObject().put("taskId","101").put("threadId",OTHER).put("turnId","immutable-original").toString();
        if(mode.equals("conflict"))TaskBroker.write(info,prior);
        List<NativeBridge.Call> foreign=new ArrayList<>();List<String> replies=new ArrayList<>();
        HarnessContext.prepareHook=request->{
            if(!request.has("id"))return;
            for(String thread:List.of(OTHER,THREAD,THREAD)) {
                NativeBridge.Call call=new NativeBridge.Call("turn/start",new JSONObject().put("threadId",thread)
                        .put("input",new JSONArray().put(new JSONObject().put("type","text").put("text","foreign"))));
                check(NativeBridge.enqueue(call),"Foreign native start was not admitted");foreign.add(call);
            }
        };
        AgentViews.eventHook=event->{
            JSONObject params=event.optJSONObject("params");if(params==null||!event.optString("method").equals("item/completed"))return;
            String thread=params.getString("threadId"),turn=params.getString("turnId");
            if(thread.equals(OTHER)) {
                check(mode.equals("conflict")?Files.readString(info.toPath()).equals(prior):!info.exists(),"An unrelated native start rebound the active task before its own response");
                return;
            }
            check(mode.equals("success"),"A reply escaped without a successful immutable task binding");
            JSONObject association=new JSONObject(Files.readString(info.toPath()));
            check(association.getString("taskId").equals("101")&&association.getString("threadId").equals(THREAD)&&association.getString("turnId").equals("owned-turn"),"A reply became visible before the owning RPC persisted its exact task/thread/turn");
            replies.add(turn);
        };
        JSONObject request=new JSONObject().put("id","101").put("conversationId",THREAD).put("prompt","owned:"+mode)
                .put("panelUpdate",new JSONObject().put("originThreadId",THREAD));
        try {
            try {CodexAppServer.run(directory,request);check(mode.equals("success"),"A conflicting or rejected start unexpectedly completed");}
            catch(IOException failure) {
                check(!mode.equals("success"),"The owning start failed: "+failure.getMessage());
                if(mode.equals("conflict"))check(failure.getMessage().equals("SOURCE_CHANGED"),"Conflicting runtime association was not rejected");
            }
            if(mode.equals("success")) {
                check(foreign.size()==3&&foreign.stream().allMatch(call->call.result.isDone()),"The response interleaving did not exercise all native starts");
                check(replies.equals(List.of("foreign-1","owned-turn","late-foreign")),"Deferred replies lost their stream order");
                JSONObject association=new JSONObject(Files.readString(info.toPath()));
                check(association.getString("taskId").equals("101")&&association.getString("threadId").equals(THREAD)&&association.getString("turnId").equals("owned-turn"),"A later native response or same-thread notification replaced immutable task ownership");
                JSONObject manifest=new JSONObject(Files.readString(new File(directory,"panel-completion.json").toPath()));
                check(manifest.getString("taskId").equals("101")&&manifest.getString("threadId").equals(THREAD)&&manifest.getString("turnId").equals("owned-turn"),"Panel completion used a foreign turn");
                check(Files.readString(new File(directory,"done").toPath()).equals("completed"),"A foreign terminal turn completed the task");
            } else {
                check(replies.isEmpty(),"Rejected binding exposed buffered replies");
                check(mode.equals("conflict")?Files.readString(info.toPath()).equals(prior):!info.exists(),"Unowned start wrote task runtime information");
            }
        } finally {
            HarnessContext.prepareHook=null;AgentViews.eventHook=null;
            if(CodexAppServer.shared!=null)CodexAppServer.shared.close();CodexAppServer.shared=null;
            NativeBridge.inbox.clear();
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("runtime")){runtime();return;}
        ownership("success");ownership("conflict");ownership("reject");
        System.out.println("CodexTaskOwnershipTest: "+checks+" ownership assertions passed");
    }
}

final class TaskBroker {
    static File ROOT=new File(System.getProperty("java.io.tmpdir"),"unused-task-broker");
    static String read(File file) throws IOException {return Files.readString(file.toPath());}
    static void write(File file,String value) throws IOException {
        file.getParentFile().mkdirs();File temporary=new File(file.getPath()+".tmp");
        Files.writeString(temporary.toPath(),value);
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static void state(File directory,JSONObject request,String state,String message) throws IOException {write(new File(directory,"state.json"),new JSONObject().put("id",request.getString("id")).put("state",state).toString());}
    static String command(String... command) {throw new AssertionError("Device commands are outside this host test");}
    static void controls() {}
}
