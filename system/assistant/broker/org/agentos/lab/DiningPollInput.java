package org.agentos.lab;

import android.app.ActivityManager;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Routes an authorized poll task to the native Assistant on its exact WhatsApp execution screen. */
final class DiningPollInput {
    private static final String PACKAGE = "com.whatsapp";
    private static final Pattern UUID_TEXT = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Set<String> ACTIONS = Set.of("bind", "send", "inspect");
    private static final Set<String> FIELDS = Set.of("op", "action", "intentId", "executionThreadId");
    private static final long CALLBACK_MS = 35_000;
    private static final int MAX_REPLY = 65_536;

    private DiningPollInput() {}

    record Request(String action, String intentId, String claimedThread) {}
    private record Owner(String requestId, String threadId, String turnId) {
        String workerKey() { return threadId + "\n" + turnId; }
    }

    static Request request(JSONObject input) throws IOException {
        for (Iterator<String> keys = input.keys(); keys.hasNext(); )
            if (!FIELDS.contains(keys.next())) throw error("DINING_POLL_INPUT_INVALID");
        String action = input.optString("action");
        Object id = input.opt("intentId");
        Object claimed = input.opt("executionThreadId");
        if (!"diningPollInput".equals(input.opt("op")) || !ACTIONS.contains(action)
                || !(id instanceof String intent) || !UUID_TEXT.matcher(intent).matches()
                || input.has("executionThreadId") && (!(claimed instanceof String thread) || !UUID_TEXT.matcher(thread).matches()))
            throw error("DINING_POLL_INPUT_INVALID");
        return new Request(action, intent, input.optString("executionThreadId"));
    }

    static void requireIntent(Request request, JSONObject task, String activeThread) throws IOException {
        if (task == null || activeThread.isEmpty() || !request.intentId().equals(task.opt("pollSendIntentId"))
                || !request.claimedThread().isEmpty() && !activeThread.equals(request.claimedThread()))
            throw error("DINING_POLL_EXECUTION_OWNER_CHANGED");
    }

    static JSONObject control(JSONObject input) throws Exception {
        String action = input.optString("action");
        if ("observe".equals(action)) return DiningPollObservationInput.control(input);
        if (DiningBookingInput.handles(action)) return DiningBookingInput.control(input);
        if (DiningRideCalendarObservation.handles(action)) return DiningRideCalendarObservation.control(input);
        Request request = request(input);
        Owner owner = register(request);
        try {
            DedicatedScreens registry = DedicatedScreensOnDevice.shared();
            String thread = owner.threadId();
            JSONObject descriptor = registry.describe(thread);
            PhoneExecutionScreens.Lease lease = registry.exact(thread,
                    descriptor.getString("screenKey"), descriptor.getString("generation"));
            // ACT_HELD checks that WhatsApp is held and that the Agent owns input. It never
            // launches, moves or closes an Android task while validating the callback.
            return registry.withScreen(thread, DedicatedScreens.Use.ACT_HELD, PACKAGE, screen -> {
                if (lease != registry.exact(thread, screen.screenKey, screen.generation)
                        || screen.legacy || screen.displayId <= 0)
                    throw error("DINING_POLL_SCREEN_UNAVAILABLE");
                ReentrantLock automation = PhoneTextInput.AUTOMATION_LOCK;
                if (!automation.tryLock(3, TimeUnit.SECONDS)) throw error("DINING_POLL_INPUT_BUSY");
                try {
                    requireCurrent(request, owner);
                    requireAutomationAvailable();
                    registry.validate(lease);
                    if (screen.userId != PhoneAppCatalog.currentUser()) throw error("ANDROID_USER_CHANGED");
                    PhoneGestures.prepare(screen);
                    PhoneGestures.requireIdle(screen.displayId);
                    JSONObject task = visibleTask(taskInventory(), screen.displayId, screen.userId);
                    requireCurrent(request, owner);
                    WorkspacePolicy.requireAppAllowed(PACKAGE);
                    JSONObject argument = new JSONObject().put("screenKey", screen.screenKey)
                            .put("generation", screen.generation).put("displayId", screen.displayId)
                            .put("userId", screen.userId).put("taskId", task.getInt("taskId"))
                            .put("packageName", PACKAGE).put("legacy", false)
                            .put("action", request.action()).put("intentId", request.intentId())
                            .put("executionThreadId", thread).put("turnId", owner.turnId())
                            .put("requestId", owner.requestId());
                    return nativeCall(argument).put("executionThreadId", thread);
                } finally {
                    automation.unlock();
                }
            });
        } finally {
            unregister(owner);
        }
    }

    private static Owner register(Request request) throws Exception {
        try (CleanupWork.Held held = NativeBridge.lock()) {
            JSONObject task = CodexAppServer.currentRequest;
            requireIntent(request, task, CodexAppServer.activeThread);
            Owner owner = new Owner(task.optString("id"), CodexAppServer.activeThread, CodexAppServer.activeTurn);
            requireCurrent(request, owner);
            NativeBridge.phoneWorkers.computeIfAbsent(owner.workerKey(), ignored -> new HashSet<>()).add(Thread.currentThread());
            return owner;
        }
    }

    private static void unregister(Owner owner) {
        try (CleanupWork.Held held = NativeBridge.lock()) {
            Set<Thread> workers = NativeBridge.phoneWorkers.get(owner.workerKey());
            if (workers == null) return;
            workers.remove(Thread.currentThread());
            if (workers.isEmpty()) NativeBridge.phoneWorkers.remove(owner.workerKey());
        }
    }

    private static void requireCurrent(Request request, Owner owner) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            JSONObject task = CodexAppServer.currentRequest;
            requireIntent(request, task, CodexAppServer.activeThread);
            if (!owner.requestId().equals(task.optString("id"))
                    || !owner.threadId().equals(CodexAppServer.activeThread)
                    || !owner.turnId().equals(CodexAppServer.activeTurn)
                    || !NativeBridge.phoneTurnActive(new JSONObject()
                        .put("threadId", owner.threadId()).put("turnId", owner.turnId())))
                throw error("DINING_POLL_EXECUTION_OWNER_CHANGED");
            if (!"full".equals(task.optString("permissionMode", "full"))
                    || !"full".equals(NativeBridge.phonePermissions.getOrDefault(owner.threadId(), "full")))
                throw error("DINING_POLL_PERMISSION_REQUIRED");
            if (!owner.requestId().matches("[0-9]{13,17}")
                    || new File(new File(TaskBroker.TASKS, owner.requestId()), "cancel").exists())
                throw error("DINING_POLL_EXECUTION_CANCELLED");
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        method.setAccessible(true);
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    static JSONArray taskInventory() throws Exception {
        // The SDK's public task API cannot list tasks across displays and Android users.
        Object service = invoke(Class.forName("android.app.ActivityTaskManager").getMethod("getService"), null);
        Method tasks = Class.forName("android.app.IActivityTaskManager")
                .getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
        Object raw = invoke(tasks, service, 200, false, true, -1);
        if (!(raw instanceof List<?> inventory) || inventory.size() >= 200)
            throw error("DINING_POLL_SCREEN_UNAVAILABLE");
        JSONArray out = new JSONArray();
        for (Object entry : inventory) {
            if (!(entry instanceof ActivityManager.RunningTaskInfo task)) throw error("DINING_POLL_SCREEN_UNAVAILABLE");
            out.put(new JSONObject().put("taskId", task.taskId)
                    .put("userId", task.getClass().getField("userId").getInt(task))
                    .put("displayId", task.getClass().getField("displayId").getInt(task))
                    .put("packageName", task.baseActivity == null ? "" : task.baseActivity.getPackageName())
                    .put("topPackage", task.topActivity == null ? "" : task.topActivity.getPackageName())
                    .put("visible", task.getClass().getField("isVisible").getBoolean(task)));
        }
        return out;
    }

    static JSONObject visibleTask(JSONArray tasks, int display, int user) throws Exception {
        return visibleTask(tasks, display, user, PACKAGE);
    }

    static JSONObject visibleTask(JSONArray tasks, int display, int user, String packageName) throws Exception {
        if (display <= 0) throw error("DINING_POLL_SCREEN_UNAVAILABLE");
        JSONObject top = null;
        int matchingTasks = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if (task.getInt("userId") != user || task.getInt("displayId") != display) continue;
            if (top == null) top = task;
            if (packageName.equals(task.optString("packageName"))) matchingTasks++;
        }
        if (matchingTasks != 1 || top == null || !packageName.equals(top.optString("packageName"))
                || !packageName.equals(top.optString("topPackage")) || !Boolean.TRUE.equals(top.opt("visible")))
            throw error("DINING_POLL_SCREEN_UNAVAILABLE");
        return top;
    }

    private static IOException error(String code) { return new IOException(code); }

    private static final class CleanupOwners {
        static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

        static {
            try {
                restoreCleanupOwners(PENDING);
            } catch (Exception unreadable) {
                PENDING.add("PERSISTED_AUTOMATION_CLEANUP_UNVERIFIED");
            }
        }
    }

    static void requireAutomationAvailable() throws IOException {
        for (String token : CleanupOwners.PENDING) {
            if (!UUID_TEXT.matcher(token).matches() || !reconcileCleanup(token))
                throw error("AUTOMATION_CLEANUP_UNVERIFIED");
            CleanupOwners.PENDING.remove(token);
        }
    }

    private static boolean reconcileCleanup(String token) {
        File operation = new File(operationDirectory(), token);
        if (!cleanupConfirmed(new File(operation, "cleanup.json"), token)
                && !cancelledAndStopped(new File(operation, "cancel"))) return false;
        forgetPending(token);
        return true;
    }

    private static File pendingDirectory() {
        return new File(TaskBroker.ROOT, "dining-poll-input-pending-v1");
    }

    private static File operationDirectory() {
        return new File(TaskBroker.ROOT, "dining-poll-input");
    }

    private static void markPending(String token) throws Exception {
        File directory = pendingDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) throw error("DINING_POLL_OPERATION_UNAVAILABLE");
        Os.chown(directory.getPath(), 0, 0);
        Os.chmod(directory.getPath(), 0700);
        File marker = new File(directory, token);
        // The root-owned filename is sufficient after a crash, even with a partial body.
        if (!marker.createNewFile()) throw error("DINING_POLL_OPERATION_UNAVAILABLE");
        Os.chmod(marker.getPath(), 0600);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write((token + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void forgetPending(String token) {
        try {
            Files.deleteIfExists(new File(pendingDirectory(), token).toPath());
        } catch (IOException unavailable) {
            // Keep the matching cleanup receipt for startup reconciliation.
        }
    }

    private static void restoreCleanupOwners(Set<String> owners) throws Exception {
        File pending = pendingDirectory();
        if (!pending.exists()) return;
        File[] markers = pending.listFiles();
        if (markers == null) throw error("DINING_POLL_PENDING_CLEANUP_UNREADABLE");
        for (File marker : markers) {
            String token = marker.getName();
            if (!UUID_TEXT.matcher(token).matches() || !marker.isFile())
                throw error("DINING_POLL_PENDING_CLEANUP_UNREADABLE");
            if (!reconcileCleanup(token)) owners.add(token);
        }
    }

    /** A cancellation never detaches the remote Binder worker from its input/cleanup fence. */
    static JSONObject nativeCall(JSONObject argument) throws Exception {
        String token = UUID.randomUUID().toString();
        File directory = new File(operationDirectory(), token);
        if (!directory.mkdirs()) throw error("DINING_POLL_OPERATION_UNAVAILABLE");
        // Only root and the system-UID native provider can create cleanup evidence.
        Os.chmod(directory.getParent(), 0711);
        Os.chown(directory.getPath(), 1000, 1000);
        Os.chmod(directory.getPath(), 0700);
        File cancel = new File(directory, "cancel");
        File cleanup = new File(directory, "cleanup.json");
        File inputLock = new File(directory, "input.lock");
        if (!inputLock.createNewFile()) throw error("DINING_POLL_OPERATION_UNAVAILABLE");
        Os.chown(inputLock.getPath(), 1000, 1000);
        Os.chmod(inputLock.getPath(), 0600);
        argument.put("operationToken", token).put("cancelPath", cancel.getPath()).put("cleanupPath", cleanup.getPath());
        markPending(token);
        Process process;
        try {
            process = new ProcessBuilder("/system/bin/content", "call", "--uri",
                    "content://org.agentos.assistant.open-access", "--method", "dining/poll/send-native-v1",
                    "--arg", argument.toString()).redirectErrorStream(true).start();
        } catch (Exception notStarted) {
            forgetPending(token);
            throw notStarted;
        }
        return awaitNative(process, cancel, cleanup, token);
    }

    private static JSONObject awaitNative(Process process, File cancel, File cleanup, String token) throws Exception {
        boolean interrupted = false;
        boolean confirmed = false;
        try {
            process.getOutputStream().close();
            FutureTask<String> output = new FutureTask<>(() -> {
                try (InputStream input = process.getInputStream()) {
                    return readReply(input);
                }
            });
            Thread reader = new Thread(output, "dining-poll-native-reply");
            reader.setDaemon(true);
            reader.start();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALLBACK_MS);
            for (;;) {
                if (Thread.interrupted()) {
                    interrupted = true;
                    cancel(cancel);
                }
                if (output.isDone() && !process.isAlive()) {
                    JSONObject envelope;
                    try {
                        envelope = parseReply(output.get());
                    } catch (ExecutionException failed) {
                        if (failed.getCause() instanceof IOException io) throw io;
                        throw error("DINING_POLL_NATIVE_REPLY_INVALID");
                    }
                    confirmed = cleanupConfirmed(cleanup, token)
                            || Boolean.TRUE.equals(envelope.opt("cleanupConfirmed"))
                                    && token.equals(envelope.opt("operationToken"));
                    if (!confirmed) throw error("DINING_POLL_CLEANUP_UNVERIFIED");
                    if (!envelope.optBoolean("ok"))
                        throw error(envelope.optString("code", "DINING_POLL_NATIVE_FAILED"));
                    JSONObject result = envelope.getJSONObject("result");
                    if (interrupted) result.put("cancelled", true);
                    return result;
                }
                if (System.nanoTime() >= deadline) throw error("DINING_POLL_NATIVE_RESULT_UNKNOWN");
                try {
                    Thread.sleep(20);
                } catch (InterruptedException stopped) {
                    interrupted = true;
                    cancel(cancel);
                }
            }
        } finally {
            // The content client is not the Assistant worker. A cancelled callback is fenced
            // until its own cleanup is confirmed or the Assistant's operation lock can be taken.
            if (confirmed || cleanupConfirmed(cleanup, token) || cancelledAndStopped(cancel)) {
                forgetPending(token);
            } else {
                CleanupOwners.PENDING.add(token);
            }
            if (process.isAlive()) process.destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String readReply(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(MAX_REPLY + 1);
        if (bytes.length > MAX_REPLY) throw error("DINING_POLL_NATIVE_REPLY_INVALID");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    static JSONObject parseReply(String frame) throws Exception {
        String value = frame.trim();
        String prefix = "Bundle[{result=";
        if (value.startsWith("Result: ")) value = value.substring("Result: ".length());
        if (!value.startsWith(prefix) || !value.endsWith("}]"))
            throw error("DINING_POLL_NATIVE_REPLY_INVALID");
        try {
            JSONTokener parser = new JSONTokener(value.substring(prefix.length(), value.length() - 2));
            Object result = parser.nextValue();
            if (!(result instanceof JSONObject) || parser.nextClean() != 0)
                throw error("DINING_POLL_NATIVE_REPLY_INVALID");
            return (JSONObject) result;
        } catch (JSONException malformed) {
            throw error("DINING_POLL_NATIVE_REPLY_INVALID");
        }
    }

    private static boolean cancel(File path) {
        try {
            Files.write(path.toPath(), "cancel\n".getBytes(StandardCharsets.UTF_8));
            Os.chmod(path.getPath(), 0644);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static boolean cancelledAndStopped(File cancel) {
        // A callback which acquires the lock after this check must see cancellation before doing work.
        return cancel(cancel) && operationUnlocked(new File(cancel.getParentFile(), "input.lock"));
    }

    static boolean operationUnlocked(File inputLock) {
        try (FileChannel channel = FileChannel.open(inputLock.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static boolean cleanupConfirmed(File path, String token) {
        try {
            if (!path.isFile() || path.length() > 1024) return false;
            JSONObject value = new JSONObject(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
            return token.equals(value.opt("operationToken")) && Boolean.TRUE.equals(value.opt("cleanupConfirmed"));
        } catch (Exception unavailable) {
            return false;
        }
    }

}
