package org.agentos.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

/** Evidence that an earlier choice-only task ended before doing any phone work. */
final class DiningRideInputRecovery {
    static JSONObject check(Context context, JSONObject entry, JSONObject args, String thread)
            throws Exception {
        PanelProtocol.exact(args, Set.of("id", "taskId", "turnId", "requestId"));
        String task = args.getString("taskId"),
                turn = args.getString("turnId"),
                request = args.getString("requestId");
        if (!task.matches("[0-9]{1,20}") || !uuid(turn) || !uuid(thread) || !uuid(request))
            throw new IOException("INVALID_ARGUMENT");
        JSONObject submission = entry.optJSONObject("submission");
        if (!Set.of("queued", "ended").contains(entry.optString("phase"))
                || entry.optBoolean("rideRequested")
                || !task.equals(entry.optString("taskId"))
                || !request.equals(entry.optString("requestId"))
                || !thread.equals(entry.optString("sourceThreadId"))
                || submission == null
                || !"queued".equals(submission.optString("state"))
                || !task.equals(submission.optString("taskId"))
                || !request.equals(submission.optString("requestId"))
                || !thread.equals(submission.optString("threadId"))) throw unavailable();

        JSONObject plan = entry.getJSONObject("ridePlan");
        if (!plan.getString("pickup").isBlank() || !plan.optBoolean("suggestedTime"))
            throw unavailable();
        String priorPrompt = entry.getString("prompt");
        File dir = new File(TaskRuntime.TASKS, task);
        File done = new File(dir, "done");
        if (!new File(dir, "ready").isFile()
                || !done.isFile()
                || done.length() > 32
                || !"completed"
                        .equals(
                                new String(
                                                Files.readAllBytes(done.toPath()),
                                                StandardCharsets.UTF_8)
                                        .strip())
                || !new File(dir, "result.txt").isFile()) throw unavailable();
        JSONObject job = read(new File(dir, "request.json"), 64_000);
        JSONObject state = read(new File(dir, "state.json"), 64_000);
        JSONObject runtime = read(new File(dir, "runtime-info.json"), 16_000);
        checkTask(job, state, runtime, task, thread, turn, request, priorPrompt);
        JSONObject projection =
                read(new File(TaskRuntime.ROOT, "agent-view/" + thread + ".json"), 1_048_576);
        checkProjection(projection, thread, turn);
        checkIdle(thread);
        return new JSONObject().put("taskId", task).put("turnId", turn).put("requestId", request);
    }

    static void checkTask(
            JSONObject job,
            JSONObject state,
            JSONObject runtime,
            String task,
            String thread,
            String turn,
            String request,
            String priorPrompt)
            throws IOException {
        if (!task.equals(job.optString("id"))
                || !thread.equals(job.optString("conversationId"))
                || !request.equals(job.optString("composerRequestId"))
                || !priorPrompt.equals(job.optString("prompt"))
                || job.has("kind")
                || job.has("panelUpdate")
                || job.optBoolean("includeScreenshot", true)
                || !job.optString("screenshot").isEmpty()
                || !task.equals(state.optString("id"))
                || !"completed".equals(state.optString("state"))
                || !priorPrompt.equals(state.optString("prompt"))
                || !task.equals(runtime.optString("taskId"))
                || !thread.equals(runtime.optString("threadId"))
                || !turn.equals(runtime.optString("turnId"))
                || !runtime.optBoolean("turnStarted")
                || !runtime.optBoolean("harnessAuditComplete")
                || runtime.optBoolean("screenshotAttached", true)) throw unavailable();
    }

    static void checkProjection(JSONObject projection, String thread, String turn)
            throws Exception {
        JSONArray turns = projection.optJSONArray("turns");
        if (!thread.equals(projection.optString("id"))
                || projection.optInt("historyOmittedItems") != 0
                || turns == null
                || turns.length() == 0) throw unavailable();
        JSONObject last = turns.getJSONObject(turns.length() - 1);
        if (!turn.equals(last.optString("id"))
                || !"completed".equals(last.optString("status"))
                || last.optInt("historyOmittedItems") != 0) throw unavailable();
        JSONArray items = last.getJSONArray("items");
        boolean asked = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String type = item.optString("type");
            if (Set.of("userMessage", "agentMessage", "reasoning").contains(type)) continue;
            if (!"dynamicToolCall".equals(type)
                    || !"taku_phone".equals(item.optString("namespace"))
                    || !"ask_user".equals(item.optString("tool"))
                    || !Set.of("completed", "failed").contains(item.optString("status")))
                throw unavailable();
            asked = true;
        }
        if (!asked) throw unavailable();
    }

    static void checkIdle(String thread) throws Exception {
        JSONObject state = NativeClient.state(thread);
        // Broker restarts are lazy: a read initializes the existing runtime without
        // submitting work or manufacturing a new conversation.
        if (!state.optBoolean("ready")) {
            JSONObject existing =
                    NativeClient.rpcSync(
                                    "thread/read",
                                    new JSONObject()
                                            .put("threadId", thread)
                                            .put("includeTurns", false))
                            .optJSONObject("thread");
            if (existing == null || !thread.equals(existing.optString("id")))
                throw new IOException("SOURCE_THREAD_NOT_FOUND");
            state = NativeClient.state(thread);
        }
        JSONArray requests = state.optJSONArray("requests");
        JSONObject cleanup = state.optJSONObject("cancellationCleanup");
        if (!state.optBoolean("ready")
                || !state.optString("activeTurnId").isEmpty()
                || requests == null
                || requests.length() != 0
                || cleanup != null && !cleanup.optBoolean("complete"))
            throw new IOException("THREAD_BUSY");
        File[] pending =
                TaskRuntime.TASKS.listFiles(
                        file ->
                                file.getName().matches("[0-9]{1,20}")
                                        && new File(file, "ready").isFile()
                                        && !new File(file, "done").isFile());
        if (pending == null || pending.length != 0) throw new IOException("THREAD_QUEUED");
    }

    private static JSONObject read(File file, long maximum) throws Exception {
        long size = file.length();
        if (size <= 0 || size > maximum || !file.isFile()) throw unavailable();
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > maximum) throw unavailable();
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static boolean uuid(String id) {
        return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static IOException unavailable() {
        return new IOException("DINING_RIDE_INPUT_RESUME_UNCONFIRMED");
    }
}
