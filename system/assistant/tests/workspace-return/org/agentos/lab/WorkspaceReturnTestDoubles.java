package org.agentos.lab;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.*;

/** Device/transport doubles; prepare and the final dispatch guard are production code. */
final class TaskBroker {
    static final File ROOT = new File(System.getProperty("java.io.tmpdir"), "workspace-return-host");
    static String read(File file) throws Exception { return Files.readString(file.toPath()); }
}
final class AgentViews { static final Map<String,JSONObject> threads = new HashMap<>(); }
final class SystemAi {
    static long revision = 1;
    static void requireEnabled() {}
    static JSONObject config() { return new JSONObject().put("revision",revision); }
}
final class ModelConnection { static JSONObject settings() { return new JSONObject().put("model","test"); } }
final class HarnessStore { static String canonical(JSONObject json) { return json.toString(); } }
final class NativeBridge {
    private static final java.util.concurrent.locks.ReentrantLock bridgeLock = new java.util.concurrent.locks.ReentrantLock();
    static CleanupWork.Held lock(){return CleanupWork.lock(bridgeLock);}
    static String parent = "";
    static Call delivered;
    static final Admissions admissions = new Admissions();
    static final class Admissions {
        void reserve(String thread,String request) {}
        void releaseReservation(String thread,String request) {}
    }
    static final class Call {
        interface Guard { void check() throws Exception; }
        final String method;
        final JSONObject params;
        final CompletableFuture<JSONObject> result = new CompletableFuture<>();
        String workspaceRequestId = "";
        boolean nativeSent,responseReceived;
        Guard workspaceGuard;
        Call(String method,JSONObject params) { this.method=method;this.params=params; }
    }
    static JSONObject askCodex(String method,JSONObject params) {
        JSONObject source = new JSONObject();
        if(!parent.isEmpty()) source.put("subAgent",new JSONObject().put("thread_spawn",
                new JSONObject().put("parent_thread_id",parent).put("agent_path","/root/original-child")));
        return new JSONObject().put("thread",new JSONObject().put("source",source));
    }
    static boolean enqueue(Call call) throws Exception {
        if(call.method.equals("taku/workspacePreflight")) {
            call.result.complete(new JSONObject().put("model","test").put("systemRevision",SystemAi.revision));
        } else {
            if(call.workspaceGuard!=null) call.workspaceGuard.check();
            call.nativeSent=true;delivered=call;
            call.result.complete(new JSONObject().put("turnId","resumed-turn"));
        }
        return true;
    }
}
final class PhoneExecutionScreens {
    interface Lease {
        ReentrantLock controlLock();
        ReentrantLock inputLock();
        Screen screenHandle();
    }
    static final class Screen {
        final String generation=WorkspaceReturnRuntimeTest.GENERATION,screenKey=WorkspaceReturnRuntimeTest.KEY;
        final int displayId=2;
        final File root;
        Screen() {
            try { root=Files.createTempDirectory("return-screen").toFile(); }
            catch(IOException error) { throw new UncheckedIOException(error); }
        }
        File directory() { return root; }
        void validateLive(JSONObject value,int pid,int uid) {}
    }
}
final class DedicatedScreensOnDevice {
    static DedicatedScreens registry;
    static DedicatedScreens shared() { return registry; }
}
final class DedicatedScreens implements PhoneExecutionScreens.Lease {
    final ReentrantLock control = new ReentrantLock(),input = new ReentrantLock();
    final PhoneExecutionScreens.Screen screen = new PhoneExecutionScreens.Screen();
    long revision=1;
    String owner="user";
    boolean blocked,resumeRequired=true;
    void requireAppsAllowed(String thread,String key,String generation) throws Exception {
        if(blocked) throw new IOException("APP_NOT_ALLOWED");
    }
    PhoneExecutionScreens.Lease exact(String thread,String key,String generation) { return this; }
    JSONObject describe(String thread) {
        return new JSONObject().put("admitted",true).put("controlRevision",revision)
                .put("controlOwner",owner).put("resumeRequired",resumeRequired).put("taskTitle","Read the clock");
    }
    void validate(PhoneExecutionScreens.Lease lease) {}
    void beginReturn(String thread,String key,String generation) { owner="paused";revision++; }
    void finishControl(String thread,String key,String generation,String value) { owner=value;revision++; }
    void resumeGranted(String thread,String key,String generation) { resumeRequired=false; }
    boolean agentAdmitted(String thread) { return owner.equals("agent"); }
    void beginPause(String thread,String key,String generation) { owner="paused";revision++; }
    public ReentrantLock controlLock() { return control; }
    public ReentrantLock inputLock() { return input; }
    public PhoneExecutionScreens.Screen screenHandle() { return screen; }
}
final class WorkspaceControl {
    static int captures;
    static JSONObject runtime(String thread) { return new JSONObject().put("activeTurnId",thread.equals(NativeBridge.parent)?parentTurn:""); }
    static String parentTurn="";
    static void stopExecution(String thread,String key,String generation) {}
    static void requireQuiescent(String thread) {}
    static JSONObject result(String thread,boolean ignored) { return new JSONObject().put("screen",DedicatedScreensOnDevice.shared().describe(thread)); }
    static final class Credentials { int getPid(){return 1;} int getUid(){return 0;} }
    static final class Socket {
        void setSoTimeout(int timeout) {}
        Credentials getPeerCredentials() { return new Credentials(); }
    }
    static final class Channel implements AutoCloseable {
        final Socket socket = new Socket();
        final PhoneExecutionScreens.Screen screen;
        Channel(PhoneExecutionScreens.Screen screen) { this.screen=screen; }
        JSONObject status() { return new JSONObject().put("frameSequence",1).put("width",720).put("height",1280); }
        byte[] call(String command) throws Exception {
            captures++;
            JSONObject capture=status().put("captureRequestId",UUID.randomUUID().toString()).put("capturedAt",System.currentTimeMillis());
            byte[] bytes={(byte)137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,0,0,0,0,0};
            ByteBuffer.wrap(bytes,16,8).putInt(720).putInt(1280);
            Files.write(new File(screen.directory(),"screen.png").toPath(),bytes);
            Files.writeString(new File(screen.directory(),"capture.json").toPath(),capture.toString());
            return capture.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        public void close() {}
    }
}
