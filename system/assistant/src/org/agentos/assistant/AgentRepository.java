package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/** Device-local client of the existing broker. History is a native thread projection. */
final class AgentRepository implements AutoCloseable {
    interface Callback<T> {
        void ready(T value);

        void failed(String message);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService jevIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final Context context;
    private final File attachmentRoot;
    private final JevCredentialStore jevCredentials;
    private final LinkedHashMap<String, ProjectionCache> projections = new LinkedHashMap<String, ProjectionCache>(4, .75f, true);
    private volatile boolean closed;
    // A replacement view/repository must recognize submissions still publishing from the old view.
    private static final ConcurrentHashMap<String, String> submitting = new ConcurrentHashMap<>();

    private static final class ProjectionCache {
        final long modified, length;
        final JSONObject value;
        ProjectionCache(long modified, long length, JSONObject value) {
            this.modified = modified;
            this.length = length;
            this.value = value;
        }
    }

    AgentRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("agent-settings", 0);
        attachmentRoot = new File(context.getFilesDir(), "composer-attachments");
        jevCredentials = new JevCredentialStore(context.getFilesDir());
    }

    private synchronized JSONObject loadJevModels() throws Exception {
        JevApiClient client = new JevApiClient();
        String imported = preferences.getString("jevImportedProvider", "");
        if (jevCredentials.configured() && !preferences.getBoolean("jevLegacyMigrated", false)) {
            JSONObject receipt = client.importLegacy(jevCredentials.read());
            imported = receipt.getString("provider");
            if (!receipt.optBoolean("imported")
                    || !preferences.edit().putBoolean("jevLegacyMigrated", true)
                            .putString("jevImportedProvider", imported).commit())
                throw new IOException("Migration status was not saved; legacy credentials were preserved.");
            // Magpie's private store was read back before this encrypted legacy copy is removed.
            try { jevCredentials.clear(); } catch (Exception retainedBackup) { /* It is no longer used. */ }
        }
        JSONObject result = client.models();
        JSONArray models = result.getJSONArray("models");
        String legacy = preferences.getString("jevModel", "");
        if (!legacy.isEmpty()) {
            SceneRoutes.bind(context,"jev",legacy,false);
            if (!preferences.edit().remove("jevModel").commit()) throw new IOException("Legacy decision-model migration was not saved.");
        }
        String selected = SceneRoutes.get(context,"jev");
        return result.put("selected", selected).put("enabled", preferences.getBoolean("jevEnabled", false));
    }

    void jevConfig(Callback<JSONObject> callback) {
        jevIo.execute(() -> {
            try { JSONObject config = loadJevModels(); deliver(() -> callback.ready(config)); }
            catch (Exception error) { deliver(() -> callback.failed("Cannot read Magpie decision models. Check the local gateway and refresh; unmigrated legacy credentials were preserved.")); }
        });
    }

    void saveJevConfig(String model, boolean enabled, Callback<Boolean> callback) {
        jevIo.execute(() -> {
            try {
                if (enabled) {
                    JSONArray models = loadJevModels().getJSONArray("models");
                    boolean found = false;
                    for (int i = 0; i < models.length(); i++)
                        found |= model.equals(models.getJSONObject(i).getString("id"));
                    if (!found) throw new IOException("Select an available decision model from the Magpie catalog first.");
                }
                if (!model.isEmpty()) SceneRoutes.bind(context,"jev",model,true);
                if (!preferences.edit().putBoolean("jevEnabled", enabled).commit())
                    throw new IOException("Could not save decision-model settings.");
                deliver(() -> callback.ready(enabled));
            } catch (Exception error) { deliver(() -> callback.failed("Configuration was not saved. Refresh the catalog and select an available decision model.")); }
        });
    }

    void testJev(Callback<JSONObject> callback) {
        jevIo.execute(() -> {
            try {
                JSONObject config = loadJevModels();
                String model = config.getString("selected");
                if (model.isEmpty()) throw new IOException("Select a decision model first.");
                JSONObject result = new JevApiClient().testConnection(model);
                deliver(() -> callback.ready(result));
            } catch (Exception error) { deliver(() -> callback.failed("Magpie decision connection test did not complete. Check the model, service, and quota.")); }
        });
    }

    void analyzeJev(android.graphics.Bitmap image, String pageText, String request, String foreground, long capturedAt,
            Callback<JevScreenDecision> callback) {
        jevIo.execute(() -> {
            try {
                if (!preferences.getBoolean("jevEnabled", false)) throw new IllegalStateException("Enable Jev in Agent settings first.");
                JSONObject config = loadJevModels();
                String selected = config.getString("selected");
                JSONObject model = null;
                JSONArray models = config.getJSONArray("models");
                for (int i = 0; i < models.length(); i++)
                    if (selected.equals(models.getJSONObject(i).getString("id"))) model = models.getJSONObject(i);
                if (model == null) throw new IOException("Select a Magpie decision model in settings first.");
                JevScreenDecision result = JevScreenDecision.analyze(model, image, pageText, request, foreground, capturedAt);
                if(result.actionable) result=ImageScreenSuggestion.generate(image,foreground,capturedAt,result.receipt);
                final JevScreenDecision ready=result;
                deliver(() -> callback.ready(ready));
            } catch (Exception error) {
                String message = error.getMessage();
                deliver(() -> callback.failed(message == null ? "Page analysis did not complete. Check Magpie configuration and retry." : message));
            }
        });
    }

    void analyzeScreen(android.graphics.Bitmap image,String foreground,long capturedAt,Callback<JevScreenDecision> callback) {
        jevIo.execute(() -> {
            try { JevScreenDecision result=ImageScreenSuggestion.generate(image,foreground,capturedAt,null);
                deliver(() -> callback.ready(result));
            } catch(Exception error) { deliver(() -> callback.failed(error.getMessage()==null?"Image suggestion was not generated. Retry":error.getMessage())); }
        });
    }

    void submitJev(String threadId, JevScreenDecision decision, Callback<String> callback) {
        String token = java.util.UUID.randomUUID().toString();
        if (closed || !validId(threadId) || submitting.putIfAbsent(threadId, token) != null) {
            callback.failed("This conversation is sending a request. Retry later.");
            return;
        }
        try { io.execute(() -> {
            try {
                boolean generated=decision.receipt.optBoolean("generated");
                if(generated) ImageScreenSuggestion.requireCurrent(decision);
                boolean gateValid=generated && decision.receipt.optBoolean("userTriggered")
                        || preferences.getBoolean("jevEnabled",false) && (generated?decision.receipt.optString("gateModel"):decision.receipt.optString("model")).equals(SceneRoutes.get(context,"jev"));
                if (!gateValid || decision.expired() || !decision.actionable)
                    throw new IllegalStateException("This suggestion is no longer valid. Analyze the current screen again.");
                if (!validId(threadId) || !NativeClient.state(threadId).optString("activeTurnId").isEmpty())
                    throw new IllegalStateException("This conversation is running. Retry later.");
                JSONObject options = new JSONObject().put("includeScreenshot", true)
                        .put("responseStyle", preferences.getString("responseStyle", "concise"));
                ComposerPreferences.apply(preferences, threadId, options);
                // The hint authorizes this capture, never the conversation's unsent attachments.
                options.remove("attachments");
                String task = TaskRequests.submit(decision.prompt(), decision.foreground, decision.screenshot, threadId, options,
                        id -> {
                            if (!preferences.edit().putString("task-" + threadId, id).commit())
                                throw new IOException("Cannot persist Jev task receipt");
                        });
                deliver(() -> callback.ready(task));
            } catch (Exception error) { deliver(() -> callback.failed("Sending failed. The suggestion was preserved; you can retry.")); }
            finally { submitting.remove(threadId, token); }
        }); } catch (java.util.concurrent.RejectedExecutionException closedDuringSubmit) {
            submitting.remove(threadId, token);
            callback.failed("Sending did not start. The suggestion was preserved.");
        }
    }

    SharedPreferences preferences() {
        return preferences;
    }

    boolean isSubmitting(String thread) {
        return submitting.containsKey(thread);
    }

    void account(Callback<JSONObject> callback) {
        io.execute(
                () -> {
                    try (android.net.LocalSocket socket = new android.net.LocalSocket()) {
                        socket.connect(new android.net.LocalSocketAddress("taku-agent-account"));
                        if (socket.getPeerCredentials().getUid() != 0)
                            throw new IOException("Unexpected account endpoint owner");
                        socket.setSoTimeout(3000);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int count;
                        while ((count = socket.getInputStream().read(buffer)) != -1) {
                            bytes.write(buffer, 0, count);
                            if (bytes.size() > 32768)
                                throw new IOException("Account view too large");
                        }
                        JSONObject view = new JSONObject(bytes.toString("UTF-8"));
                        deliver(() -> callback.ready(view));
                    } catch (Exception error) {
                        deliver(() -> callback.failed("Account service is temporarily unavailable. Retry later."));
                    }
                });
    }

    void rpc(String method, JSONObject params, Callback<JSONObject> callback) {
        io.execute(
                () -> {
                    try {
                        JSONObject result = TaskRequests.control(method, params);
                        deliver(() -> callback.ready(result));
                    } catch (Exception error) {
                        String errorMessage = error.getMessage();
                        boolean credentialError = errorMessage != null
                                && (errorMessage.startsWith("No GitHub credentials are configured for this private repository")
                                        || errorMessage.startsWith("Cannot access the private repository")
                                        || errorMessage.startsWith("Invalid GitHub credentials"));
                        String reason = publicReason(error);
                        String message = credentialError ? error.getMessage() : method.equals("thread/read")
                                ? "Cannot read the original conversation: " + reason
                                : "Conversation service request failed: " + reason;
                        deliver(() -> callback.failed(message));
                    }
                });
    }

    void snapshot(String threadId, Callback<JSONObject> callback) {
        io.execute(
                () -> {
                    try {
                        JSONObject result = new JSONObject();
                        if (validId(threadId)) {
                            result.put("composerSubmitting", isSubmitting(threadId));
                            result.put("composerSteering", ComposerPreferences.isSteering(preferences, threadId));
                            File file =
                                    new File(
                                            AssistantSession.ROOT,
                                            "agent-view/" + threadId + ".json");
                            if (file.isFile()) {
                                long modified = file.lastModified(), length = file.length();
                                result.put("thread", readProjection(threadId, file, modified, length));
                                result.put("projectionStamp", modified + ":" + length);
                            } else projections.remove(threadId);
                            // A confirmation-card continuation can advance the display pointer before
                            // the original composer delivery has observed its own terminal receipt.
                            reconcileDeliveryTask(threadId);
                            JSONObject nativeState;
                            try { nativeState = NativeClient.state(threadId); }
                            catch (Exception unavailable) { nativeState = new JSONObject(); }
                            result.put("native", nativeState);
                            String taskId = preferences.getString("task-" + threadId, "");
                            if (taskId.matches("[0-9]+")) {
                                File task = new File(AssistantSession.TASKS, taskId);
                                result.put(
                                        "task", new JSONObject(read(new File(task, "state.json")))
                                                .put("published", new File(task, "ready").isFile()));
                                if (!new File(task, "ready").isFile() && !isSubmitting(threadId))
                                    result.getJSONObject("task").put("state", "failed")
                                            .put("message", "Sending did not complete. The draft was preserved; check it before sending again.");
                                result.put(
                                        "request",
                                        new JSONObject(read(new File(task, "request.json"))));
                                File runtime = new File(task, "runtime-info.json");
                                if (runtime.isFile()) {
                                    JSONObject info = new JSONObject(read(runtime));
                                    result.put("runtime", info);
                                    result.getJSONObject("task").put("turnStarted", info.optBoolean("turnStarted"));
                                }
                            }
                        }
                        deliver(() -> callback.ready(result));
                    } catch (Exception error) {
                        String message = error instanceof JSONException
                                ? "Conversation display record format is damaged. Native history is preserved."
                                : error instanceof IOException && error.getMessage() != null
                                        && (error.getMessage().contains("safety limit")
                                                || error.getMessage().contains("safe limit"))
                                                ? "Conversation display records exceed the safety limit. Native history is preserved."
                                                : "Failed to read conversation display records. Native history was not changed.";
                        deliver(() -> callback.failed(message));
                    }
                });
    }

    /** Resolve the task referenced by the send receipt, independently of the latest-task pointer. */
    private void reconcileDeliveryTask(String threadId) {
        try {
            JSONObject receipt = new JSONObject(preferences.getString(
                    ComposerPreferences.key(threadId, "delivery"), "{}"));
            String taskId = receipt.optString("taskId");
            if (!receipt.has("token") || !taskId.matches("[0-9]{1,20}")) return;
            File directory = new File(AssistantSession.TASKS, taskId);
            JSONObject request = new JSONObject(read(new File(directory, "request.json")));
            JSONObject task = new JSONObject(read(new File(directory, "state.json")));
            if (!taskId.equals(request.optString("id")) || !taskId.equals(task.optString("id"))
                    || !threadId.equals(request.optString("conversationId"))
                    || !new File(directory, "ready").isFile()) return;
            String state = task.optString("state");
            if (!state.equals("completed") && !state.equals("cancelled")
                    && !state.equals("failed") && !state.equals("needs_user")) return;
            task.put("published", true);
            File runtime = new File(directory, "runtime-info.json");
            try {
                if (runtime.isFile()) task.put("turnStarted",
                        new JSONObject(read(runtime)).optBoolean("turnStarted"));
            } catch (Exception unavailable) { /* The verified terminal task state remains authoritative. */ }
            synchronized (ComposerPreferences.class) {
                JSONObject current = new JSONObject(preferences.getString(
                        ComposerPreferences.key(threadId, "delivery"), "{}"));
                if (!receipt.optString("token").equals(current.optString("token"))
                        || !taskId.equals(current.optString("taskId"))) return;
                ComposerPreferences.reconcile(preferences, threadId, task, isSubmitting(threadId));
            }
        } catch (Exception unavailable) {
            // An unreadable/missing receipt is not proof of completion. Keep it and the draft.
        }
    }

    void submit(
            ComposerDraft draft,
            String text,
            String foreground,
            android.graphics.Bitmap screenshot,
            Callback<String> callback) {
        String threadId = draft.thread;
        final JSONObject options;
        final String token;
        try {
            if (closed || isSubmitting(threadId)) throw new IOException("A send is already in progress");
            // Capture options before leaving the UI thread, alongside the immutable draft.
            options = new JSONObject()
                    .put("includeScreenshot", preferences.getBoolean("includeScreenshot", false))
                    .put("responseStyle", preferences.getString("responseStyle", "concise"));
            ComposerPreferences.apply(preferences, threadId, options);
            options.put("attachments", new JSONArray(draft.attachments));
            token = ComposerPreferences.beginDelivery(preferences, draft);
            submitting.put(threadId, token);
        } catch (Exception error) {
            callback.failed("Sending did not start. The draft was preserved.");
            return;
        }
        try { io.execute(
                () -> {
                    try {
                        validateAttachments(new JSONArray(draft.attachments));
                        boolean includeScreenshot = options.optBoolean("includeScreenshot");
                        if (includeScreenshot && screenshot == null) {
                            ComposerPreferences.deliveryFailed(preferences, threadId, token);
                            deliver(() -> callback.failed("The system did not supply a screen from before invocation. Reopen the assistant or disable screenshots."));
                            return;
                        }
                        String task =
                                TaskRequests.submit(
                                        text,
                                        foreground,
                                        includeScreenshot ? screenshot : null,
                                        threadId,
                                        options,
                                        id -> ComposerPreferences.queued(preferences, threadId, token, id));
                        deliver(() -> callback.ready(task));
                    } catch (Exception error) {
                        ComposerPreferences.deliveryFailed(preferences, threadId, token);
                        String message = error instanceof InvalidAttachment
                                ? "The attachment is unavailable. The draft was preserved. Select the attachment again." : "Sending failed. The draft was preserved.";
                        deliver(() -> callback.failed(message));
                    } finally {
                        submitting.remove(threadId, token);
                    }
                });
        } catch (java.util.concurrent.RejectedExecutionException closedDuringSubmit) {
            submitting.remove(threadId, token);
            ComposerPreferences.deliveryFailed(preferences, threadId, token);
            callback.failed("Sending did not start. The draft was preserved.");
        }
    }

    static final class InvalidAttachment extends IOException {}

    void validateAttachments(JSONArray attachments) throws InvalidAttachment {
        try {
            String root = attachmentRoot.getCanonicalPath() + "/";
            for (int i = 0; i < attachments.length(); i++) {
                File file = new File(attachments.getJSONObject(i).getString("path"));
                if (!file.getCanonicalPath().startsWith(root) || !file.isFile() || !file.canRead()
                        || file.length() > 20_000_000) throw new InvalidAttachment();
            }
        } catch (Exception invalid) { throw new InvalidAttachment(); }
    }

    void cancel(String taskId, Callback<Boolean> callback) {
        io.execute(
                () -> {
                    try {
                        if (!taskId.matches("[0-9]+")) throw new IOException("Invalid task");
                        TaskRequests.write(
                                new File(new File(AssistantSession.TASKS, taskId), "cancel"), "1");
                        deliver(() -> callback.ready(true));
                    } catch (Exception error) {
                        deliver(() -> callback.failed("Stop request was not delivered. Retry."));
                    }
                });
    }

    static boolean validId(String id) {
        return id != null && id.matches("[a-f0-9-]{36}");
    }

    private static String read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 1024 * 1024) throw new IOException("Projection exceeds safe limit");
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private JSONObject readProjection(String threadId, File file, long modified, long length)
            throws IOException, JSONException {
        ProjectionCache cached = projections.get(threadId);
        if (cached != null && cached.modified == modified && cached.length == length) return cached.value;
        JSONObject value = new JSONObject(read(file));
        projections.put(threadId, new ProjectionCache(modified, length, value));
        while (projections.size() > 2) projections.remove(projections.keySet().iterator().next());
        return value;
    }

    private static String publicReason(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Unknown error. Retry.";
        message = message.replaceAll("[\\r\\n\\t]", " ").trim();
        return message.length() > 220 ? message.substring(0, 220) + "…" : message;
    }

    private void deliver(Runnable action) {
        main.post(
                () -> {
                    if (!closed) action.run();
                });
    }

    @Override
    public void close() {
        closed = true;
        // Finish registered submissions so their durable receipt is resolved even without a UI callback.
        io.shutdown();
        jevIo.shutdownNow();
    }
}
