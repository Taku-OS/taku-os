package org.agentos.lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class WorkspaceReturnRuntimeTest {
    static final String THREAD="00000000-0000-4000-8000-000000000001";
    static final String PARENT="00000000-0000-4000-8000-000000000002";
    static final String KEY="task-00000000000000000001";
    static final String GENERATION="00000000-0000-4000-8000-000000000003";
    static int checks;
    static void check(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    static WorkspaceExecution.Request request() throws Exception {
        return new WorkspaceExecution.Request(new JSONObject().put("action","returnAndResume").put("threadId",THREAD)
                .put("screenKey",KEY).put("generation",GENERATION).put("controlRevision",1)
                .put("requestId",UUID.randomUUID().toString()),false);
    }
    static void reset(boolean child,String parentTurn) {
        DedicatedScreensOnDevice.registry=new DedicatedScreens();
        NativeBridge.parent=child?PARENT:"";NativeBridge.delivered=null;
        WorkspaceControl.parentTurn=parentTurn;WorkspaceControl.captures=0;SystemAi.revision=1;
        AgentViews.threads.put(THREAD,new JSONObject());AgentViews.threads.put(PARENT,new JSONObject());
    }
    static void returned(boolean child,String parentTurn) throws Exception {
        reset(child,parentTurn);
        WorkspaceExecutionRuntime runtime=new WorkspaceExecutionRuntime();
        WorkspaceExecution.Request request=request();
        try(WorkspaceExecution.Prepared prepared=runtime.prepare(request,Files.createTempDirectory("return-request").toFile())) {
            // Reproduce the original delay without sleeping or changing the capture validator.
            if(prepared.observation!=null)prepared.observation.put("capturedAt",System.currentTimeMillis()-20000);
            runtime.dispatch(request,prepared).result().get();
            check(NativeBridge.delivered!=null,"the original task must be dispatched despite time spent preparing it");
            check(prepared.observation==null && WorkspaceControl.captures==0,"return must not capture before the agent wakes");
            check(prepared.params.getString("threadId").equals(child?PARENT:THREAD),"main returns directly; child returns through its parent");
            check(prepared.method.equals(parentTurn.isEmpty()?"turn/start":"turn/steer"),"running parent is steered; idle parent starts");
            check(prepared.params.getJSONArray("input").length()==1,"no obsolete image is sent before resume");
            String text=prepared.params.getJSONArray("input").getJSONObject(0).getString("text");
            check(text.contains("observe_background"),"the resumed agent must be told to observe the current screen first");
            if(child)check(text.contains("followup_task")&&text.contains("original-child"),"the parent wakes the same child");
        }
        check(!DedicatedScreensOnDevice.registry.control.isLocked(),"the return control lock is released");
    }
    public static void main(String[] args) throws Exception {
        returned(false,"");returned(true,"");returned(true,"running-parent-turn");
        reset(false,"");DedicatedScreensOnDevice.registry.blocked=true;
        try { new WorkspaceExecutionRuntime().prepare(request(),Files.createTempDirectory("blocked-return").toFile());throw new AssertionError("blocked App resumed"); }
        catch(IOException error) { check(error.getMessage().equals("APP_NOT_ALLOWED"),"App policy still checked before returning"); }
        reset(false,"");WorkspaceExecutionRuntime runtime=new WorkspaceExecutionRuntime();
        WorkspaceExecution.Request request=request();
        try(WorkspaceExecution.Prepared prepared=runtime.prepare(request,Files.createTempDirectory("changed-return").toFile())) {
            DedicatedScreensOnDevice.registry.revision++;
            try { runtime.dispatch(request,prepared);throw new AssertionError("stale control state resumed"); }
            catch(IOException error) { check(error.getMessage().equals("SCREEN_CONTROL_CHANGED"),"later control changes still block stale returns"); }
        }
        System.out.println("WorkspaceReturnRuntimeTest: "+checks+" checks passed (production prepare/dispatch, fake device and transport)");
    }
}
