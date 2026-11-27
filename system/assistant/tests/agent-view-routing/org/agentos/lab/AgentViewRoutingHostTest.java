package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replays native reads through the real bridge/projections before resolving the original child. */
public final class AgentViewRoutingHostTest {
    private static final String PARENT = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";
    private static final String OTHER_PARENT = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2";
    private static final String CHILD_PATH = "/root/original-child";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject spawned(String parent, String path) {
        return new JSONObject().put("subAgent", new JSONObject().put("thread_spawn",
                new JSONObject().put("parent_thread_id", parent).put("agent_path", path)));
    }

    private static JSONObject thread(Object source) {
        return new JSONObject().put("id", UUID.randomUUID().toString())
                .put("source", source).put("turns", new JSONArray());
    }

    private static JSONObject read(JSONObject thread, boolean includeTurns) throws Exception {
        String before = thread.toString();
        CompletableFuture<JSONObject> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return NativeBridge.askCodex("thread/read", new JSONObject()
                        .put("threadId", thread.getString("id")).put("includeTurns", includeTurns));
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        try {
            NativeBridge.Call call = NativeBridge.inbox.poll(2, TimeUnit.SECONDS);
            check(call != null && call.method.equals("thread/read"), "the internal native read was queued");
            CodexAppServer server = CodexAppServer.shared;
            server.nativeRequest(call);
            check(NativeBridge.complete(new JSONObject().put("id", server.nextRequest)
                    .put("result", new JSONObject().put("thread", thread))), "the real bridge completed the read");
            JSONObject reply = pending.get(2, TimeUnit.SECONDS);
            check(thread.toString().equals(before), "display projection leaves the native thread unchanged");
            return reply;
        } finally {
            pending.cancel(true);
        }
    }

    private static void identity(JSONObject reply, String parent, String path) {
        check(PhoneSubAgents.parent(reply).equals(parent), "native read retains the exact parent: " + parent);
        check(PhoneSubAgents.path(reply).equals(path), "native read retains the original child path");
    }

    private static void nativeReadKeepsChildIdentity() throws Exception {
        JSONObject thread = thread(spawned(PARENT, CHILD_PATH));
        identity(read(thread, false), PARENT, CHILD_PATH);
        identity(read(thread, true), PARENT, CHILD_PATH);
    }

    private static void refreshAndReloadKeepChildIdentity() throws Exception {
        JSONObject thread = thread(spawned(PARENT, CHILD_PATH));
        identity(read(thread, false), PARENT, CHILD_PATH);
        String updatedPath = "/root/updated-child";
        thread.put("source", spawned(OTHER_PARENT, updatedPath));
        identity(read(thread, false), OTHER_PARENT, updatedPath);
        File checkpoint = new File(AgentViews.ROOT, thread.getString("id") + ".json");
        JSONObject persisted = new JSONObject(Files.readString(checkpoint.toPath()));
        identity(new JSONObject().put("thread", persisted), OTHER_PARENT, updatedPath);
        AgentViews.runtimeClosed();
        JSONObject metadataOnly = new JSONObject().put("id", thread.getString("id")).put("name", "restored");
        identity(read(metadataOnly, false), OTHER_PARENT, updatedPath);
    }

    private static void routingFieldsAreNotDisplayText() throws Exception {
        String path = "/root/" + "original-child-".repeat(1500);
        JSONObject thread = thread(spawned(PARENT, path))
                .put("name", "Title ".repeat(10_000)).put("preview", "Preview ".repeat(10_000))
                .put("model", "Model ".repeat(10_000)).put("modelProvider", "Provider ".repeat(10_000))
                .put("status", new JSONObject().put("text", "Status ".repeat(10_000)));
        JSONObject reply = read(thread, false);
        identity(reply, PARENT, path);
        JSONObject view = reply.getJSONObject("thread");
        check(view.optInt("historyOmittedText") > 0, "display text still obeys the existing folding budget");
        check(view.toString().getBytes(StandardCharsets.UTF_8).length <= AgentViewProjection.MAX_BYTES,
                "preserving routing metadata still obeys the total display byte limit");
    }

    private static void ordinarySourcesAndExplicitReplacement() throws Exception {
        for (Object source : new Object[]{"appServer", "cli", JSONObject.NULL}) {
            JSONObject thread = thread(source);
            JSONObject reply = read(thread, false);
            check(reply.getJSONObject("thread").has("source"), "native source presence is retained");
            check(reply.getJSONObject("thread").get("source").equals(source), "ordinary native source type is retained");
            identity(reply, "", "");
        }
        JSONObject thread = thread(spawned(PARENT, CHILD_PATH));
        identity(read(thread, false), PARENT, CHILD_PATH);
        thread.put("source", "appServer");
        JSONObject reply = read(thread, false);
        identity(reply, "", "");
        check(reply.getJSONObject("thread").getString("source").equals("appServer"),
                "fresh native source replaces the cached family metadata");
        JSONObject legacy = new JSONObject().put("id", UUID.randomUUID().toString());
        JSONObject legacyReply = read(legacy, false);
        check(!legacyReply.getJSONObject("thread").has("source"), "absent source is not invented");
        identity(legacyReply, "", "");
    }

    private static void sourceIsCopiedAndTotalLimitStillApplies() throws Exception {
        JSONObject nativeThread = thread(spawned(PARENT, CHILD_PATH));
        JSONObject view = AgentViewProjection.project(nativeThread);
        view.getJSONObject("source").getJSONObject("subAgent").getJSONObject("thread_spawn")
                .put("parent_thread_id", OTHER_PARENT);
        identity(new JSONObject().put("thread", nativeThread), PARENT, CHILD_PATH);
        JSONObject oversized = thread(spawned(PARENT, "/root/" + "x".repeat(AgentViewProjection.MAX_BYTES)));
        try {
            AgentViewProjection.project(oversized);
            throw new AssertionError("preserved source must not bypass the total byte limit");
        } catch (IOException expected) {
            check(expected.getMessage().contains("safety limit"), "oversized source keeps the existing byte-limit error");
        }
    }

    private static void historyRefreshDoesNotBlockNativeRouting(boolean paged) throws Exception {
        String turnId = UUID.randomUUID().toString();
        String imageUrl = "data:image/jpeg;base64,AAAA";
        JSONObject receipt = new JSONObject().put("schema", "taku.notebook.references/1")
                .put("androidUserId", 0).put("items", new JSONArray().put(new JSONObject()
                        .put("id", "a".repeat(64)).put("sha256", "b".repeat(64))
                        .put("imageUri", "content://org.agentos.notebook/images/" + "a".repeat(64))));
        JSONObject item = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("type", "dynamicToolCall").put("tool", "notebook_recall")
                .put("namespace", "taku_phone").put("success", true)
                .put("contentItems", new JSONArray()
                        .put(new JSONObject().put("type", "inputText").put("text", receipt.toString()))
                        .put(new JSONObject().put("type", "inputImage").put("imageUrl", imageUrl)));
        JSONArray rawTurns = new JSONArray().put(new JSONObject().put("id", turnId)
                .put("items", new JSONArray().put(item)));
        JSONObject thread = thread("appServer").put("nativeOnly", true);
        JSONObject params = new JSONObject().put("threadId", thread.getString("id"));
        JSONObject result;
        String method;
        if (paged) {
            thread.getJSONArray("turns").put(new JSONObject().put("id", UUID.randomUUID().toString())
                    .put("items", new JSONArray()));
            read(thread, true);
            params.put("sortDirection", "desc").put("itemsView", "full");
            result = new JSONObject().put("data", rawTurns);
            method = "thread/turns/list";
        } else {
            params.put("includeTurns", true);
            result = new JSONObject().put("thread", thread.put("turns", rawTurns));
            method = "thread/read";
        }
        NativeBridge.Call call = new NativeBridge.Call(method, params);
        CodexAppServer server = CodexAppServer.shared;
        server.nativeRequest(call);
        JSONObject response = new JSONObject().put("id", server.nextRequest).put("result", result);
        String expectedTurns = rawTurns.toString();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> observed = new CompletableFuture<>();
        NotebookBridge.refreshHook = (turns, newestFirst) -> {
            for (int i = 0; i < turns.length(); i++) {
                if (!turnId.equals(turns.getJSONObject(i).optString("id"))) continue;
                observed.complete(turns.toString().equals(expectedTurns) && newestFirst == paged);
                try {
                    release.await();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        };
        try {
            CompletableFuture<Boolean> dispatched = CompletableFuture.supplyAsync(() -> {
                try {
                    return NativeBridge.complete(response);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            check(observed.get(5, TimeUnit.SECONDS), method + " refresh receives only the raw native turns");
            check(dispatched.get(2, TimeUnit.SECONDS), method + " completes while refresh is blocked");
            JSONObject reply = call.result.get(2, TimeUnit.SECONDS);
            check(paged ? reply.getJSONArray("data").length() == 2 : !reply.getJSONObject("thread").has("nativeOnly"),
                    method + " still returns the display projection");
            CompletableFuture.runAsync(() -> {
                try (CleanupWork.Held held = NativeBridge.lock()) {
                    // Other broker operations must be able to acquire this lock.
                }
            }).get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            NotebookBridge.refreshHook = null;
        }
    }

    public static void main(String[] args) throws Exception {
        CodexAppServer.shared = new CodexAppServer();
        nativeReadKeepsChildIdentity();
        refreshAndReloadKeepChildIdentity();
        routingFieldsAreNotDisplayText();
        ordinarySourcesAndExplicitReplacement();
        sourceIsCopiedAndTotalLimitStillApplies();
        historyRefreshDoesNotBlockNativeRouting(false);
        historyRefreshDoesNotBlockNativeRouting(true);
        System.out.println("PASS AgentViewRoutingHostTest: " + checks + " checks, 7 cases");
    }
}
