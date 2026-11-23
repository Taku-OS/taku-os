package org.agentos.lab;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.json.*;

/** Device adapters and unused entrypoints only. Turn preparation, discovery and updates are real. */
final class TaskBroker {
    static File ROOT;
    static String read(File file) throws Exception { return Files.readString(file.toPath()); }
    static void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), text);
    }
    static String command(String... args) {
        if (Arrays.equals(args, new String[]{"/system_ext/bin/phone-display", "id"})) return "fixture-display";
        throw new AssertionError("Unexpected device command");
    }
    static void state(File directory, JSONObject request, String status, String message) {}
    static void controls() {} // The fixture has no queued control tasks.
}
final class ModelConnection {
    static List<String> command() {
        return List.of(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), NativeTurnSkillsTest.class.getName(),
                "--runtime", TaskBroker.ROOT.getPath());
    }
    static boolean local() { return false; }
    static JSONObject settings() { return new JSONObject().put("model", "fixture"); }
    static JSONObject read() { throw new AssertionError("Model settings are outside this test"); }
}
final class TurnProcesses {
    static final String OWNER_ENV = "TAKU_RUNTIME_INSTANCE";
    TurnProcesses(String owner) {}
    void starting(String thread) {}
    void interrupting(String thread, String turn) {}
    JSONObject event(JSONObject event) { return null; }
    JSONObject workspaceProof(String thread) { return new JSONObject(); }
    void close() {}
}
final class WorkspaceAdmissions {
    static final class Ticket {}
    Ticket queue(String method, String thread) { return new Ticket(); }
    void requireCurrent(Ticket ticket) {}
    void beforeSend(int id, Ticket ticket) {}
    void failedBeforeSend(Ticket ticket) {}
    void response(int id, boolean success) {}
    void beginSkillMaintenance() throws IOException { throw new AssertionError("Skill management is outside this test"); }
    void endSkillMaintenance() {}
    static boolean execution(String method) { return false; }
}
final class NativeBridge {
    static final class Call {
        interface Guard { void check() throws Exception; }
        final String method;
        final JSONObject params;
        final CompletableFuture<JSONObject> result = new CompletableFuture<>();
        WorkspaceAdmissions.Ticket admission = new WorkspaceAdmissions.Ticket();
        Guard workspaceGuard;
        boolean nativeSent, cleanup;
        Call(String method, JSONObject params) { this.method = method; this.params = params; }
    }
    static final WorkspaceAdmissions admissions = new WorkspaceAdmissions();
    static final Map<Integer, Call> calls = new HashMap<>();
    static final Map<String, JSONObject> state = new HashMap<>();
    static final Map<String, JSONObject> requests = new HashMap<>();
    static final Map<String, String> phonePermissions = new HashMap<>();
    static final Queue<Call> inbox = new ArrayDeque<>();
    static CleanupWork.Held lock() { return new CleanupWork.Held(); }
    static void tick() throws Exception {
        for (int n = 0; n < 4; n++) {
            Call call = inbox.poll();
            if (call == null) return;
            CodexAppServer.shared.nativeRequest(call);
        }
    }
    static boolean complete(JSONObject response) {
        Call call = calls.remove(response.getInt("id"));
        if (call == null) return false;
        call.result.complete(response.getJSONObject("result"));
        return true;
    }
    static void event(JSONObject event) {}
    static void request(CodexAppServer server, JSONObject request) { throw new AssertionError("Unexpected server request"); }
    static void cancellation(String thread, JSONObject cleanup) {}
    static void setPhonePermission(String thread, String permission) {}
    static void stopPhoneTurn(String thread, String turn) {}
    static JSONObject state(String thread) { return state.get(thread); }
    static JSONArray toolDefinitions() { return new JSONArray(); }
    static String publicError(Exception error) { return error.getMessage(); }
    static void closed() { calls.clear(); inbox.clear(); }
}
final class CleanupWork {
    static final class Held implements AutoCloseable {
        @Override public void close() {}
    }
}
final class SessionRemovalRuntime {
    static boolean pending(String thread) { return false; }
    static void filter(JSONObject result) {}
}
final class HarnessCatalog {
    static String apk() { return new File(TaskBroker.ROOT, "assistant.apk").getPath(); }
    static String asset(String path) throws Exception {
        try (ZipFile zip = new ZipFile(apk()); InputStream input = zip.getInputStream(zip.getEntry(path))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static JSONArray modules() { return new JSONArray(); }
    static String compose(JSONArray modules) throws Exception { return HarnessStore.canonical(modules); }
    static JSONObject replyContract() {
        return new JSONObject().put("protocol", "fixture").put("catalogVersion", "1")
                .put("contractHash", "fixture").put("prompt", "Reply to the user.");
    }
}
final class HarnessEnvironment {
    static JSONObject collect(JSONObject request) {
        return new JSONObject().put("os", new JSONObject()).put("capabilities", new JSONObject());
    }
}
final class MemoryBridge {
    static JSONObject provider(String method, JSONObject params) { return new JSONObject(); }
}
final class MemoryGen {
    static final Set<String> threads = new HashSet<>();
    static boolean owns(String thread) { return threads.contains(thread); }
}
final class DedicatedScreensOnDevice {
    static DedicatedScreensOnDevice shared() { return new DedicatedScreensOnDevice(); }
    boolean agentAdmitted(String thread) { return true; }
}
final class SystemAi {
    static void requireEnabled() {}
    static JSONObject config() { return new JSONObject().put("revision", 1); }
}
final class AgentViews {
    static final File ROOT = new File("unused-view-fixture");
    static final Map<String, JSONObject> threads = new HashMap<>();
    static void prepareReply(String thread, JSONObject contract, boolean enabled) {}
    static void interruptReply(String thread, String turn) {}
    static void event(JSONObject event) {}
    static void runtimeClosed() {}
    static void save(JSONObject thread) { threads.put(thread.getString("id"), thread); }
    static void saveMetadata(JSONObject thread) { save(thread); }
    static void index() {}
    static JSONObject snapshot(String thread) { return threads.get(thread); }
    static String readableTurn(String thread, String turn) { return ""; }
    static JSONObject panelCompletion(String thread, String turn) { return new JSONObject().put("replies", new JSONArray()); }
}
final class AgentAccount {
    static boolean refreshRequested;
    static void gateway(boolean ready, String message) {}
    static void read(JSONObject response) {}
    static void event(JSONObject event) {}
    static String loginId() { return ""; }
    static void starting() {}
    static void started(JSONObject response) {}
    static void cancelled() {}
    static void failed() {}
}
final class PhoneGestures { static void endTurn(String thread, String turn) {} }
final class UiCardRequest { static final String ROLES = ""; }
final class SkillIcons { static void installed(JSONObject row, File directory, JSONObject display) {} }
final class SkillVersion {
    static boolean hasBackup(String name) { return false; }
    static JSONObject check(String path) { throw new AssertionError("Unexpected version check"); }
    static void upgrade(String path, String commit) { throw new AssertionError("Unexpected upgrade"); }
    static void rollback(String path) { throw new AssertionError("Unexpected rollback"); }
}
final class SkillUninstall {
    static void recover(CodexAppServer server) {}
    static void deleteTree(java.nio.file.Path path) {}
    static void remove(CodexAppServer server, JSONObject skill) { throw new AssertionError("Unexpected uninstall"); }
}
final class SkillCommunity {
    static JSONArray sources() { return new JSONArray(); }
    static JSONObject catalog(String source) { throw new AssertionError("Unexpected community read"); }
    static JSONObject detail(String source, String name, String commit) { throw new AssertionError("Unexpected community read"); }
    static void add(String repo, String path, boolean privateRepo) { throw new AssertionError("Unexpected community write"); }
    static JSONObject install(String source, String name, String commit) { throw new AssertionError("Unexpected install"); }
}
