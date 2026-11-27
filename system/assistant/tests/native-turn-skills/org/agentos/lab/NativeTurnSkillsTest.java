package org.agentos.lab;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.json.*;
import sun.misc.Unsafe;

/** Real nativeRequest -> refresh/prepare with real files, receipts and a cached stdio skills RPC. */
public final class NativeTurnSkillsTest {
    private static final String NAME = "taku-open-access";
    private static final String V1 = "---\nname: taku-open-access\ndescription: Old device access.\n---\nVersion one.\n";
    private static final String V2 = V1.replace("Old device access.", "New device access.") + "New command.\n";
    private static final String V3 = V2 + "Another new command.\n";
    private static final String FIGMA_MOTION = "taku-figma-motion";
    private static final List<String> BUILTINS = List.of("taku-app-development", "taku-system-ai-settings",
            NAME, "taku-plugin-creator", FIGMA_MOTION);
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void field(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    // Redirect the Android-only constant before exercising it; no production test hook or /data writes.
    private static void skillsHome(File home) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        Field target = AgentSkills.class.getDeclaredField("HOME");
        target.get(null); // Initialize before replacing a non-constant static final object.
        unsafe.putObject(unsafe.staticFieldBase(target), unsafe.staticFieldOffset(target), home);
    }
    private static String document(String name) {
        return "---\nname: " + name + "\ndescription: Fixture skill.\n---\n";
    }
    private static JSONArray scan(File home) throws Exception {
        JSONArray rows = new JSONArray();
        for (String name : BUILTINS) {
            File body = new File(home, name + "/SKILL.md");
            rows.put(AgentSkills.metadata(body).put("path", body.getPath()).put("enabled", true));
        }
        return rows;
    }
    private static final class Rig implements AutoCloseable {
        final File root = Files.createTempDirectory("native-turn-skills-").toRealPath().toFile();
        final File home = new File(root, "skills");
        final File body = new File(home, NAME + "/SKILL.md");
        final String thread = UUID.randomUUID().toString();
        final CodexAppServer server;

        Rig(boolean existingBase, String retainedReason) throws Exception {
            TaskBroker.ROOT = root;
            skillsHome(home);
            field(HarnessContext.class, "store", null);
            NativeBridge.state.clear();
            MemoryGen.threads.clear();
            for (String name : BUILTINS)
                TaskBroker.write(new File(home, name + "/SKILL.md"), name.equals(NAME) ? V1 : document(name));
            JSONObject receipt = new JSONObject().put("name", NAME).put("path", body.getPath())
                    .put("repo", "assistant-apk").put("commit", "builtin-1").put("bodySha256", HarnessStore.hash(V1));
            if (retainedReason.equals("LOCAL_MODIFIED")) TaskBroker.write(body, V1 + "Local note.\n");
            if (retainedReason.equals("UNVERIFIED_BASELINE")) receipt.remove("bodySha256");
            TaskBroker.write(new File(HarnessContext.store().root, "installed-skills/" + NAME + ".json"), receipt.toString());
            JSONArray old = scan(home);
            for (int i = 0; i < old.length(); i++) {
                JSONObject row = old.getJSONObject(i);
                row.put("bodySha256", HarnessStore.hash(TaskBroker.read(new File(row.getString("path")))));
            }
            field(AgentSkills.class, "discovered", old);
            if (existingBase) HarnessContext.started(thread, HarnessContext.seed());
            bundle(V1);
            server = new CodexAppServer(root, event -> {
                if (event.optString("method").equals("turn/completed"))
                    CodexAppServer.completion = event.getJSONObject("params").getJSONObject("turn").getString("status");
            });
            CodexAppServer.shared = server;
            server.initialize(); // The RPC fixture caches V1 here, before the APK changes.
            bundle(V2);
        }
        void bundle(String text) throws Exception {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(new File(root, "assistant.apk")))) {
                for (String name : BUILTINS) {
                    zip.putNextEntry(new ZipEntry("assets/skills/" + name + "/SKILL.md"));
                    zip.write((name.equals(NAME) ? text : document(name)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }
        NativeBridge.Call call(String method, String id) {
            return new NativeBridge.Call(method, new JSONObject().put("threadId", id)
                    .put("input", new JSONArray().put(new JSONObject().put("type", "text").put("text", "fixture"))));
        }
        void start(String method) throws Exception {
            NativeBridge.Call call = call(method, thread);
            server.nativeRequest(call);
            await(call);
        }
        void await(NativeBridge.Call call) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!call.result.isDone() && System.nanoTime() < deadline) server.pump(20);
            check(call.result.isDone(), "native request completes without a nested-pump deadlock");
            call.result.get();
        }
        List<JSONObject> requests(String method) throws Exception {
            List<JSONObject> result = new ArrayList<>();
            for (String line : Files.readAllLines(new File(root, "rpc.jsonl").toPath())) {
                JSONObject request = new JSONObject(line);
                if (request.getString("method").equals(method)) result.add(request);
            }
            return result;
        }
        JSONObject skill(String id) throws Exception {
            return find(HarnessContext.read(id).getJSONObject("current").getJSONArray("skills"));
        }
        @Override public void close() throws Exception {
            server.close();
            CodexAppServer.shared = null;
            try (var paths = Files.walk(root.toPath())) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static JSONObject find(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).optString("name").equals(NAME)) return rows.getJSONObject(i);
        throw new AssertionError("builtin missing from catalog");
    }
    private static void upgrade(String method, boolean existingBase, String retainedReason) throws Exception {
        try (Rig rig = new Rig(existingBase, retainedReason)) {
            String installed = TaskBroker.read(rig.body);
            rig.start(method);
            List<JSONObject> scans = rig.requests("skills/list");
            JSONObject skill = rig.skill(rig.thread);
            JSONObject receipt = new JSONObject(TaskBroker.read(new File(HarnessContext.store().root, "installed-skills/" + NAME + ".json")));
            check(skill.getString("bodySha256").equals(HarnessStore.hash(TaskBroker.read(rig.body))),
                    method + ", existingBase=" + existingBase + ": context digest must match the updated installed file");
            check(scans.size() == 1, "native turn rediscovers skills before preparing context");
            if (retainedReason.isEmpty()) {
                check(TaskBroker.read(rig.body).equals(V2), "pristine builtin updates to V2");
                check(skill.getString("description").equals("New device access."), "forceReload updates cached native metadata too");
                check(scans.get(0).getJSONObject("params").getBoolean("forceReload"), "changed builtin forces native rediscovery");
                rig.bundle(V3);
                rig.start(method);
                check(rig.skill(rig.thread).getString("bodySha256").equals(HarnessStore.hash(V3)), "same runtime and same session see the next APK version");
                rig.start(method);
                check(!rig.requests("skills/list").get(2).getJSONObject("params").getBoolean("forceReload"), "unchanged builtin avoids a forced rescan");
            } else {
                check(TaskBroker.read(rig.body).equals(installed), "retained builtin is not overwritten");
                check(HarnessStore.canonical(skill.getJSONObject("builtinUpdate")).equals(HarnessStore.canonical(receipt.getJSONObject("builtinUpdate"))), "turn notice matches current update receipt");
                check(skill.getJSONObject("builtinUpdate").getString("reason").equals(retainedReason), "retention reason reaches the next turn");
            }
            JSONObject base = HarnessContext.read(rig.thread);
            if (!existingBase) {
                JSONArray modules = base.getJSONArray("modules");
                JSONObject catalog = modules.getJSONObject(modules.length() - 1).getJSONObject("data");
                check(HarnessStore.canonical(find(catalog.getJSONArray("skills"))).equals(HarnessStore.canonical(skill)),
                        "first seed already has the refreshed digest, metadata and update notice");
            }
        }
    }
    private static void exclusionsAndFailure(String method) throws Exception {
        try (Rig rig = new Rig(false, "")) {
            MemoryGen.threads.add(rig.thread);
            rig.start(method);
            check(rig.requests("skills/list").isEmpty(), "MemoryGen stays outside skill refresh");
            check(rig.requests("thread/inject_items").isEmpty(), "MemoryGen receives no agent background");
            check(TaskBroker.read(rig.body).equals(V1), "MemoryGen does not update agent skill files");
        }
        try (Rig rig = new Rig(false, "")) {
            Files.createFile(new File(rig.root, "fail-refresh").toPath());
            NativeBridge.Call call = rig.call(method, rig.thread);
            try { rig.server.nativeRequest(call); throw new AssertionError("failed refresh started a turn"); }
            catch (IOException expected) { check(expected.getMessage().contains("skills/list"), "refresh failure propagates through the existing error path"); }
            check(!call.nativeSent && rig.requests(method).isEmpty(), "failed discovery sends no native turn");
            check(rig.requests("thread/inject_items").isEmpty(), "failed discovery injects no stale background");
        }
    }
    private static void concurrentPreparation() throws Exception {
        try (Rig rig = new Rig(true, "")) {
            String active = UUID.randomUUID().toString(), peer = UUID.randomUUID().toString();
            rig.bundle(V1);
            HarnessContext.started(active, HarnessContext.seed());
            HarnessContext.prepare(rig.server, active, new JSONObject().put("conversationId", active).put("replyMode", "disabled"));
            rig.bundle(V2);
            HarnessContext.started(peer, HarnessContext.seed());
            String frozen = HarnessStore.canonical(HarnessContext.read(active).getJSONObject("current"));
            NativeBridge.state.put(active, new JSONObject().put("activeTurnId", "active-fixture-turn"));
            NativeBridge.Call other = rig.call("turn/start", peer);
            NativeBridge.inbox.add(other); // The real call()/pump() dispatches this while the first refresh waits.
            rig.start("turn/start");
            rig.await(other);
            check(rig.skill(rig.thread).getString("bodySha256").equals(HarnessStore.hash(V2)), "outer preparation sees V2");
            check(rig.skill(peer).getString("bodySha256").equals(HarnessStore.hash(V2)), "nested preparation sees V2");
            check(HarnessStore.canonical(HarnessContext.read(active).getJSONObject("current")).equals(frozen),
                    "already-running session keeps its confirmed context snapshot");
        }
    }
    private static void legacyEntrypoints() throws Exception {
        try (Rig rig = new Rig(false, "")) {
            CodexAppServer.control(rig.root, new JSONObject().put("method", "thread/start"));
            JSONObject response = new JSONObject(TaskBroker.read(new File(rig.root, "response.json")));
            check(response.getBoolean("ok"), "legacy thread/start still succeeds: " + response);
            String thread = response.getJSONObject("result").getJSONObject("thread").getString("id");
            check(HarnessContext.background(thread).contains(HarnessStore.hash(V2)), "legacy thread/start seeds the updated catalog");
            check(rig.requests("skills/list").size() == 1, "legacy thread/start refreshes exactly once");
            rig.bundle(V3);
            CodexAppServer.control(rig.root, new JSONObject().put("method", "harness/refresh")
                    .put("params", new JSONObject().put("threadId", thread)));
            response = new JSONObject(TaskBroker.read(new File(rig.root, "response.json")));
            check(response.getBoolean("ok"), "manual harness refresh still succeeds");
            check(rig.skill(thread).getString("bodySha256").equals(HarnessStore.hash(V3)), "manual refresh uses the updated catalog");
            check(rig.requests("skills/list").size() == 2, "manual refresh adds exactly one discovery RPC");
            String latest = V3 + "Legacy task update.\n";
            rig.bundle(latest);
            File task = new File(rig.root, "legacy-task");
            task.mkdirs();
            CodexAppServer.run(task, new JSONObject().put("conversationId", thread)
                    .put("id", "fixture-task").put("prompt", "fixture"));
            check(TaskBroker.read(new File(task, "done")).equals("completed"), "legacy run still completes");
            check(rig.skill(thread).getString("bodySha256").equals(HarnessStore.hash(latest)), "legacy run refreshes before context preparation");
            check(rig.requests("skills/list").size() == 3, "legacy run adds no duplicate discovery RPC");
        }
    }
    private static void installsFigmaMotionFromBundle() throws Exception {
        try (Rig rig = new Rig(false, "")) {
            File directory = new File(rig.home, FIGMA_MOTION);
            File body = new File(directory, "SKILL.md");
            Files.delete(body.toPath());
            Files.delete(directory.toPath());

            AgentSkills.ensureBuiltin();

            check(TaskBroker.read(body).equals(document(FIGMA_MOTION)), "fresh Figma motion skill comes from the APK");
        }
    }
    // No inference: cache skill metadata until forceReload, acknowledge context injection and turn RPCs.
    private static void runtime(File root) throws Exception {
        JSONArray cached = scan(new File(root, "skills"));
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                PrintWriter output = new PrintWriter(System.out, true)) {
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject request = new JSONObject(line);
                if (!request.has("id")) continue;
                Files.writeString(new File(root, "rpc.jsonl").toPath(), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                String method = request.getString("method");
                JSONObject response = new JSONObject().put("id", request.getInt("id")), result = new JSONObject();
                if (method.equals("skills/list")) {
                    if (new File(root, "fail-refresh").exists()) {
                        output.println(response.put("error", new JSONObject().put("code", -32000).put("message", "fixture discovery failure")));
                        continue;
                    }
                    if (request.getJSONObject("params").optBoolean("forceReload")) cached = scan(new File(root, "skills"));
                    result.put("data", new JSONArray().put(new JSONObject().put("skills", cached)));
                } else if (method.equals("model/list")) {
                    result.put("data", new JSONArray().put(new JSONObject().put("model", "fixture").put("isDefault", true)));
                } else if (method.equals("thread/start") || method.equals("thread/read")) {
                    String thread = method.equals("thread/start") ? UUID.randomUUID().toString() : request.getJSONObject("params").getString("threadId");
                    result.put("thread", new JSONObject().put("id", thread)).put("model", "fixture");
                } else if (method.equals("account/read")) result.put("account", new JSONObject());
                else if (method.equals("turn/start")) result.put("turn", new JSONObject().put("id", "fixture-turn"));
                else if (!Set.of("initialize", "thread/inject_items", "thread/realtime/start").contains(method))
                    throw new AssertionError("Unexpected RPC: " + method);
                output.println(response.put("result", result));
                if (method.equals("turn/start")) output.println(new JSONObject().put("method", "turn/completed")
                        .put("params", new JSONObject().put("threadId", request.getJSONObject("params").getString("threadId"))
                                .put("turn", new JSONObject().put("id", "fixture-turn").put("status", "completed"))));
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--runtime")) { runtime(new File(args[1])); return; }
        File original = AgentSkills.HOME;
        try {
            installsFigmaMotionFromBundle();
            for (String method : List.of("turn/start", "thread/realtime/start")) {
                for (boolean existing : new boolean[]{false, true})
                    for (String reason : List.of("", "LOCAL_MODIFIED", "UNVERIFIED_BASELINE")) upgrade(method, existing, reason);
                exclusionsAndFailure(method);
            }
            concurrentPreparation();
            legacyEntrypoints();
            System.out.println("NativeTurnSkillsTest: " + checks + " checks passed (real native/context/skill code; device and RPC fixtures)");
        } finally { skillsHome(original); }
    }
}
