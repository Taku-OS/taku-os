package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only endpoints. NativeBridge and its confirmation validation are production sources. */
final class PhoneNativeTools {
    static final AtomicInteger executions=new AtomicInteger();
    static volatile CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);
    static JSONArray definitions() { return new JSONArray(); }
    static JSONObject text(String value) throws Exception { return new JSONObject().put("success",true).put("text",value); }
    static JSONObject failure(String value) throws Exception { return text(value).put("success",false); }
    static JSONObject execute(JSONObject params) throws Exception {
        executions.incrementAndGet();
        JSONObject args=params.optJSONObject("arguments");
        if(args!=null && args.optBoolean("block")) {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch(InterruptedException stopped) { interrupted.countDown();throw stopped; }
        }
        return text("executed");
    }
    static void nextBlockingCall() { entered=new CountDownLatch(1);interrupted=new CountDownLatch(1); }
}
final class PhoneGestures {
    static volatile String badCancellation="";
    static final java.util.List<String> cancellations=new java.util.concurrent.CopyOnWriteArrayList<>();
    static void cancelThreadChecked(String thread)throws Exception {
        cancellations.add(thread);
        if(thread.equals(badCancellation))throw new IOException("synthetic cancellation failure");
    }
    static void releaseTurn(String thread,String turn) {}
    static void endTurn(String thread,String turn) {}
    static void startTurn(String thread,String turn) {}
    static void cancelAll() {}
}
final class WorkspaceExecutionRuntime {static void initialize() {}}
final class MagpieDecisions { JSONObject control(JSONObject input) { return new JSONObject(); } }
final class HarnessCatalog {
    static File apk() { return new File("unused-host-test.apk"); }
    static JSONObject replyContract() { return new JSONObject(); }
}
final class ModelConnection {
    static JSONArray catalog() { throw new AssertionError("Model catalog is outside this routing test"); }
    static JSONArray publicModels(JSONArray catalog) { throw new AssertionError("Model catalog is outside this routing test"); }
    static boolean local() {return false;}
    static java.util.List<String> command() {return java.util.List.of(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),"org.agentos.lab.CodexTaskOwnershipTest","runtime");}
    static JSONObject settings() {return new JSONObject().put("model","host-model");}
    static JSONObject read() {return new JSONObject();}
    static JSONObject control(JSONObject input) {return new JSONObject();}
}
final class MemoryBridge { static JSONObject control(JSONObject input) { return new JSONObject(); } }
final class MemoryGen {
    static final java.util.List<JSONObject> events=new java.util.ArrayList<>();
    static void event(JSONObject event) { events.add(new JSONObject(event.toString())); }
    static boolean owns(String thread) {return false;}
}
final class ImeReplies { static JSONObject control(JSONObject input,int uid) { return new JSONObject(); } }
final class AudioConfigBridge { static void start() { throw new AssertionError("Audio service is outside this routing test"); } }
final class AppPermissionPolicy {
    static boolean isRootSocketAllowed(int uid) { throw new AssertionError("App permissions are outside this routing test"); }
    static JSONObject control(JSONObject input, int uid) { throw new AssertionError("App permissions are outside this routing test"); }
}
final class MatterBridge { static JSONObject control(JSONObject input) { throw new AssertionError("Matter service is outside this routing test"); } }
final class AudioBridge { static JSONObject control(JSONObject input) { throw new AssertionError("Audio commands are outside this routing test"); } }
final class SystemAi {
    static final SystemAiSettings.Store SETTINGS = null;
    static JSONObject config() throws Exception { return new JSONObject().put("revision",1); }
    static void requireEnabled() {}
    static JSONObject control(JSONObject input) { return new JSONObject(); }
    static JSONObject ui(JSONObject input) { throw new AssertionError("UI generation is outside this routing test"); }
}
final class PhoneExecutionScreens {
    static final class Admission {final long revision;Admission(long revision){this.revision=revision;}}
    interface Ledger {
        boolean agentAdmitted(String thread)throws Exception;
        Admission admission(String thread)throws Exception;
        boolean admissionCurrent(Admission grant)throws Exception;
        boolean turnStarted(String thread,Admission grant)throws Exception;
    }
}
final class DedicatedScreensOnDevice {
    static final DedicatedScreens shared = new DedicatedScreens();
    static DedicatedScreens shared() { return shared; }
}
final class DedicatedScreens implements PhoneExecutionScreens.Ledger {
    java.util.List<String> family=java.util.List.of("parent","child");
    private java.util.function.Predicate<String> fence=thread->false;
    void removalFence(java.util.function.Predicate<String> fence){this.fence=fence;}
    void observeRemovalThread(JSONObject thread){}
    java.util.Set<String> removalFamily(String root){java.util.Set<String> result=new java.util.LinkedHashSet<>(family);result.add(root);return result;}
    org.json.JSONArray removalResources(java.util.Set<String> sessions){
        org.json.JSONArray result=new org.json.JSONArray();
        for(String session:sessions)result.put(new JSONObject().put("session",session).put("resourceId",session));
        return result;
    }
    void removalCancelled(String thread){}
    void cleanupRemoval(JSONObject resource){gone.add(resource.getString("session"));}
    void forgetRemoved(java.util.Set<String> sessions){}
    long revision;boolean allowed=true,resumeRequired;
    public boolean agentAdmitted(String thread){return allowed&&!fence.test(thread);}
    public PhoneExecutionScreens.Admission admission(String thread)throws Exception {if(!allowed)throw new IOException("SCREEN_AGENT_PAUSED");return new PhoneExecutionScreens.Admission(revision);}
    public boolean admissionCurrent(PhoneExecutionScreens.Admission grant){return allowed&&grant!=null&&grant.revision==revision;}
    public boolean turnStarted(String thread,PhoneExecutionScreens.Admission grant){if(grant!=null?!admissionCurrent(grant):!allowed||resumeRequired)return false;resumeRequired=false;return true;}
    public JSONObject describe(String thread) {return new JSONObject().put("bound",false);}

    final java.util.List<String> turnsEnded = new java.util.concurrent.CopyOnWriteArrayList<>();
    Thread turnEnded(String thread) {
        turnsEnded.add(thread);
        return null;
    }
    /** Each session archived or deleted. */
    final java.util.List<String> gone = new java.util.concurrent.CopyOnWriteArrayList<>();
    Thread sessionGone(String thread) {
        gone.add(thread);
        return null;
    }
    void idleTimeout(long ms) {}
    /** Times Codex went away. */
    final java.util.concurrent.atomic.AtomicInteger codexGone = new java.util.concurrent.atomic.AtomicInteger();
    Thread codexGone() {
        codexGone.incrementAndGet();
        return null;
    }
    record ParentNote(String parent, String text) {}
    /** What a refused turn of each closed sub-agent tells its parent. */
    final java.util.Map<String, ParentNote> closed = new java.util.concurrent.ConcurrentHashMap<>();
    ParentNote closedNote(String thread) {
        return closed.get(thread);
    }
    /** Each card shown, as "thread text returnTo"; each card ended, by thread. */
    final java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>(),
            ended = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** What the next questionEnded returns. */
    volatile JSONObject handedOver;
    void asked(String thread, String text, String returnTo) {
        asked.add(thread + " " + text + " " + returnTo);
    }
    JSONObject questionEnded(String thread) {
        ended.add(thread);
        JSONObject note = handedOver;
        handedOver = null;
        return note;
    }
}
final class WorkspaceControl {static JSONObject control(JSONObject input){return new JSONObject();}}
final class PhoneAppCatalog {static JSONObject requireLaunchable(String pkg){return new JSONObject();}}
final class PluginCreator {static JSONObject catalogAdd(java.nio.file.Path path){return new JSONObject();}}
final class SkillCredentials {static JSONObject control(JSONObject input){return new JSONObject();}}
final class MagpieSettings {
    static void schedule() {}
    static void migrate() { throw new AssertionError("Magpie migration is outside this routing test"); }
    static void sync() { throw new AssertionError("Magpie sync is outside this routing test"); }
}

/** These tests route phone tools; unrelated credential endpoints must not execute. */
final class ByokProviders {
    JSONObject control(JSONObject input) {throw new AssertionError("BYOK is outside this routing test");}
}

final class TurnProcesses {
    static final String OWNER_ENV="TAKU_TEST_OWNER";
    TurnProcesses(String instance) {}
    void starting(String thread) {}
    void interrupting(String thread,String turn) {}
    JSONObject event(JSONObject event) {return null;}
    JSONObject workspaceProof(String thread) {return new JSONObject();}
    void close() {}
}
final class HarnessContext {
    interface PrepareHook {void accept(JSONObject request) throws Exception;}
    static PrepareHook prepareHook;
    static boolean auditIncomplete;
    static void prepare(CodexAppServer server,String thread,JSONObject request) throws Exception {if(prepareHook!=null)prepareHook.accept(request);}
    static void event(JSONObject event) {}
    static String background(String thread) {return "";}
    static JSONObject seed() {return new JSONObject().put("text","");}
    static void started(String thread,JSONObject seed) {}
    static void turnStarted(String thread,String turn,String task) {}
    static JSONObject read(String thread) {return new JSONObject();}
}
final class AgentAccount {
    static boolean refreshRequested;
    static void event(JSONObject event) {}
    static void gateway(boolean ready,String message) {}
    static void read(JSONObject account) {}
    static String loginId() {return "";}
    static void starting() {}
    static void started(JSONObject login) {}
    static void cancelled() {}
    static void failed() {}
}
final class AgentSkills {
    static void refresh(CodexAppServer server,boolean force) {}
    static JSONObject readWhileRunning(String thread) {return new JSONObject();}
    static JSONObject control(CodexAppServer server,String method,JSONObject params) {return new JSONObject();}
    static JSONArray selected(String thread) {return new JSONArray();}
}
final class SkillUninstall {static void recover(CodexAppServer server) {}}
final class NotebookBridge {
    static void refreshReferenceGrants(JSONObject thread) {}
}
