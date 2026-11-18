package org.agentos.lab;

import android.os.Process;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Isolated emulator only: real broker/store/screens, fixture Codex metadata and failure gates. */
public final class SessionRemovalDeviceProbe {
    static final File ROOT=new File("/data/local/tmp/taku-pr62-removals");
    static final String PARENT="00000000-0000-0000-0000-000000000001",CHILD="00000000-0000-0000-0000-000000000002";
    static void write(File file,String text)throws Exception{
        try(FileOutputStream output=new FileOutputStream(file)){output.write(text.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
    }
    static SessionRemovals.State nativeState()throws Exception{
        File file=new File(ROOT,"native-state");
        return file.exists()?SessionRemovals.State.valueOf(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)):SessionRemovals.State.ACTIVE;
    }
    public static void main(String[] arguments)throws Exception{
        ROOT.mkdirs();
        write(new File(ROOT,"probe.pid"),Integer.toString(Process.myPid()));
        if(!new File("/dev/qemu_pipe").exists()&&!new File("/dev/goldfish_pipe").exists()
                &&!TaskBroker.command("/system/bin/getprop","ro.kernel.qemu").trim().equals("1"))
            throw new IOException("EMULATOR_ONLY");
        WorkspacePolicy.store=new WorkspacePolicy.Store(new File(ROOT,"policy.json"));
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fixture-screen-clock");t.setDaemon(true);return t;});
        PhoneSubAgents.Rpc codex=(method,params)->{
            if(method.equals("thread/read")){
                JSONObject source=new JSONObject();
                if(params.getString("threadId").equals(CHILD))source.put("subAgent",new JSONObject().put("thread_spawn",new JSONObject().put("parent_thread_id",PARENT)));
                return new JSONObject().put("thread",new JSONObject().put("id",params.getString("threadId")).put("source",source));
            }
            if(method.equals("thread/loaded/list"))return new JSONObject().put("data",new JSONArray()).put("nextCursor",JSONObject.NULL);
            if(!method.equals("thread/list"))throw new IOException("FIXTURE_UNEXPECTED_RPC");
            SessionRemovals.State state=nativeState();JSONArray data=new JSONArray();
            boolean visible=state!=SessionRemovals.State.ABSENT&&(params.optBoolean("archived")==(state==SessionRemovals.State.ARCHIVED));
            if(visible)data.put(new JSONObject().put("id",params.has("ancestorThreadId")?CHILD:PARENT));
            return new JSONObject().put("data",data).put("nextCursor",JSONObject.NULL);
        };
        DedicatedScreens screens=new DedicatedScreens(PhoneExecutionScreens.DEVICE_HOST,PhoneTasks.DEVICE,new DedicatedScreens.Clock(){
            public long now(){return System.nanoTime()/1_000_000;}
            public void after(long milliseconds,Runnable run){timer.schedule(run,milliseconds,TimeUnit.MILLISECONDS);}
        },codex,()->"zh",System.err::println);
        Field ledger=DedicatedScreensOnDevice.class.getDeclaredField("shared");ledger.setAccessible(true);ledger.set(null,screens);
        if(!new File(ROOT,"seeded").exists()){
            JSONObject parent=screens.request(PARENT,Map.of("com.android.settings","Settings"),"PR62 parent fixture");
            JSONObject child=screens.request(CHILD,Map.of("com.android.deskclock","Clock"),"PR62 child fixture");
            TaskBroker.command("/system/bin/am","start","--display",Integer.toString(parent.getInt("displayId")),"-n","com.android.settings/.Settings");
            TaskBroker.command("/system/bin/am","start","--display",Integer.toString(child.getInt("displayId")),"-n","com.android.deskclock/.DeskClock");
            write(new File(ROOT,"screens.fixture"),new JSONObject().put("parent",parent).put("child",child).toString());
            write(new File(ROOT,"seeded"),"ready");
        }
        SessionRemovals store=new SessionRemovals(new File(ROOT,"jobs"),new SessionRemovals.Backend(){
            public Set<String> family(String root)throws Exception{
                Set<String> members=screens.removalFamily(root);members.addAll(PhoneSubAgents.gone(codex,root));return members;
            }
            public JSONArray resources(Set<String> sessions)throws Exception{return screens.removalResources(sessions);}
            public SessionRemovals.State state(String root)throws Exception{return PhoneSubAgents.removalState(codex,root);}
            public void apply(String root,String target)throws Exception{
                if(target.equals("archive")&&new File(ROOT,"defer-archive").exists())throw new IOException("FIXTURE_ARCHIVE_DEFERRED");
                write(new File(ROOT,"native-state"),target.equals("delete")?"ABSENT":"ARCHIVED");
                try(FileOutputStream output=new FileOutputStream(new File(ROOT,"native-actions"),true)){
                    output.write((target+"\n").getBytes(StandardCharsets.UTF_8));output.getFD().sync();
                }
            }
            public void cancel(String session)throws Exception{
                screens.removalCancelled(session);NativeBridge.cancelRemovedSession(session);
                if(session.equals(CHILD)&&new File(ROOT,"defer-cancellation").exists())throw new IOException("FIXTURE_CHILD_CANCELLATION_DEFERRED");
            }
            public void cleanup(JSONObject resource)throws Exception{screens.cleanupRemoval(resource);}
            public void forget(Set<String> sessions){screens.forgetRemoved(sessions);}
        },System.err::println);
        screens.removalFence(store::pending);
        Field runtime=SessionRemovalRuntime.class.getDeclaredField("store");runtime.setAccessible(true);runtime.set(null,store);
        NativeBridge.start();
        write(new File(ROOT,"probe.pid"),Integer.toString(Process.myPid()));
        write(new File(ROOT,"ready"),"ready");
        System.out.println("PR62 fixture ready: real native socket, durable store and Android tasks; Codex metadata is a fixture");
        new CountDownLatch(1).await();
    }
}
