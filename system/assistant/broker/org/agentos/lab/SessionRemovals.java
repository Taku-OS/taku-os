package org.agentos.lab;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.*;

/** One durable intent per session. Workers own attempts, never the only copy of a pending job. */
final class SessionRemovals implements AutoCloseable {
    static final int WORKERS = 3, COMPLETED_LIMIT = 100;
    static final long RETRY_MS = 30_000;
    enum State { ACTIVE, ARCHIVED, ABSENT }
    interface Backend {
        Set<String> family(String root) throws Exception;
        JSONArray resources(Set<String> sessions) throws Exception;
        State state(String root) throws Exception;
        void apply(String root, String target) throws Exception;
        void cancel(String session) throws Exception;
        void cleanup(JSONObject resource) throws Exception;
        void forget(Set<String> sessions) throws Exception;
    }
    private interface Change { void apply(JSONObject job) throws Exception; }
    private final File directory;
    private final Backend backend;
    private final Consumer<String> warn;
    private final LongSupplier now;
    private final long timeoutMs, retryMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, JSONObject> jobs = new LinkedHashMap<>();
    private final Set<String> unconfirmedWrites = new HashSet<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, String> familyOwners = new ConcurrentHashMap<>();
    private final Map<String, Long> deferred = new ConcurrentHashMap<>();
    private volatile Set<String> fenced = Set.of();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final boolean automatic;
    private final AtomicBoolean wakeQueued = new AtomicBoolean();
    private String lastClaimed = "";
    private volatile boolean closed;

    SessionRemovals(File directory, Backend backend, Consumer<String> warn) throws Exception {
        this(directory, backend, warn, System::currentTimeMillis, CleanupWork.TIMEOUT_MS, RETRY_MS, true);
    }
    SessionRemovals(File directory, Backend backend, Consumer<String> warn, LongSupplier now,
            long timeoutMs, long retryMs, boolean automatic) throws Exception {
        this.directory = directory; this.backend = backend; this.warn = warn; this.now = now;
        this.timeoutMs = timeoutMs; this.retryMs = retryMs; this.automatic = automatic;
        Files.createDirectories(directory.toPath());
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"));
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) throw new IOException("SESSION_REMOVALS_UNREADABLE");
        for (File file : files) {
            if (file.length() > 8_000_000) throw new IOException("SESSION_REMOVAL_TOO_LARGE");
            JSONObject job = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            validate(job.getString("threadId"));
            validate(job.getString("requestId"));
            if (!file.getName().equals(job.getString("threadId") + ".json")
                    || job.getInt("schemaVersion") != 1
                    || !Set.of("archive", "delete").contains(job.getString("target"))
                    || !Set.of("pending", "running", "complete").contains(job.getString("status"))
                    || !Set.of("queued", "verifying", "cleanup", "retry", "complete").contains(job.getString("phase"))
                    || job.getLong("revision") < 1 || job.getLong("acceptedAt") < 0
                    || job.getLong("updatedAt") < 0 || job.getLong("nextAttemptAt") < 0)
                throw new IOException("INVALID_SESSION_REMOVAL");
            job.getJSONArray("sessions"); job.getJSONArray("resources"); job.getJSONArray("cancelled");
            job.getBoolean("familySaved");
            boolean targetReached = job.getBoolean("targetReached");
            if (done(job) && !targetReached
                    && !(job.getString("target").equals("archive") && job.optString("outcome").equals("no_record"))
                    || !done(job) && !strings(job.getJSONArray("sessions")).contains(job.getString("threadId")))
                throw new IOException("INVALID_SESSION_REMOVAL");
            for (String member : strings(job.getJSONArray("sessions"))) validate(member);
            for (String member : strings(job.getJSONArray("cancelled"))) validate(member);
            if (!done(job)) {
                job.put("status", "pending").put("nextAttemptAt", now.getAsLong()).put("step", "verify");
                job.remove("cancelCursor"); job.remove("cleanupCursor");
            }
            jobs.put(job.getString("threadId"), job);
        }
        publish();
        workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORKERS), runnable -> daemon(runnable, "session-removal-worker"));
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "session-removal-scheduler"));
        if (automatic) timer.scheduleWithFixedDelay(this::dispatch, 0, 1_000, TimeUnit.MILLISECONDS);
    }
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }
    static void validate(String root) throws IOException {
        if (root == null || !root.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("INVALID_SCREEN_THREAD");
    }
    private static boolean done(JSONObject job) { return job.optString("status").equals("complete"); }
    private static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(value.toString()); }
    private static Set<String> strings(JSONArray values) throws JSONException {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }

    JSONObject submit(String root, String target) throws Exception {
        validate(root);
        if (!Set.of("archive", "delete").contains(target)) throw new IOException("INVALID_SESSION_REMOVAL_TARGET");
        try (CleanupWork scope = new CleanupWork(timeoutMs); CleanupWork.Held held = CleanupWork.lock(lock)) {
            if (closed) throw new IOException("SESSION_REMOVALS_CLOSED");
            JSONObject current = jobs.get(root);
            if (current != null && !done(current)) {
                if (target.equals("delete") && current.getString("target").equals("archive")) {
                    JSONObject upgraded = copy(current).put("target", "delete")
                            .put("revision", current.getLong("revision") + 1).put("targetReached", false)
                            .put("nextAttemptAt", now.getAsLong()).put("phase", "queued").put("step", "verify");
                    save(upgraded);
                    deferred.remove(root);
                    current = upgraded;
                }
                // Retrying the durable write also resolves an earlier directory-fsync failure.
                JSONObject durable = copy(current); save(durable);
                return receipt(durable);
            }
            JSONObject job = new JSONObject().put("schemaVersion", 1).put("threadId", root).put("requestId", UUID.randomUUID().toString())
                    .put("target", target).put("revision", 1).put("status", "pending").put("phase", "queued")
                    .put("acceptedAt", now.getAsLong()).put("updatedAt", now.getAsLong())
                    .put("nextAttemptAt", now.getAsLong()).put("familySaved", false).put("targetReached", false)
                    .put("sessions", new JSONArray().put(root)).put("resources", new JSONArray()).put("cancelled", new JSONArray());
            save(job);
            return receipt(job);
        } finally { wake(); }
    }
    boolean pending(String session) { return fenced.contains(session); }
    JSONObject query(String root, String requestId) throws Exception {
        validate(root);
        try (CleanupWork scope = new CleanupWork(timeoutMs); CleanupWork.Held held = CleanupWork.lock(lock)) {
            JSONObject job = jobs.get(root);
            if (job == null || !requestId.isEmpty() && !requestId.equals(job.getString("requestId")))
                return new JSONObject().put("threadId", root).put("status", "unavailable").put("completed", false);
            return receipt(job);
        }
    }
    JSONObject list() throws Exception {
        try (CleanupWork scope = new CleanupWork(timeoutMs); CleanupWork.Held held = CleanupWork.lock(lock)) {
            JSONArray data = new JSONArray();
            for (JSONObject job : jobs.values()) data.put(receipt(job));
            return new JSONObject().put("data", data).put("workers", WORKERS)
                    .put("waitTimeoutMs", timeoutMs).put("retryDelayMs", retryMs).put("completedLimit", COMPLETED_LIMIT);
        }
    }
    private JSONObject receipt(JSONObject job) throws JSONException {
        int cleaned = 0; JSONArray resources = job.getJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) if (resources.getJSONObject(i).optBoolean("cleaned")) cleaned++;
        boolean durable = !unconfirmedWrites.contains(job.getString("threadId"));
        return new JSONObject().put("threadId", job.getString("threadId")).put("requestId", job.getString("requestId"))
                .put("target", job.getString("target")).put("revision", job.getLong("revision"))
                .put("accepted", durable).put("completed", durable && done(job)).put("status", durable ? job.getString("status") : "pending")
                .put("phase", durable ? job.getString("phase") : "persistence").put("targetReached", job.optBoolean("targetReached"))
                .put("outcome", job.optString("outcome"))
                .put("acceptedAt", job.getLong("acceptedAt")).put("updatedAt", job.getLong("updatedAt"))
                .put("nextAttemptAt", job.getLong("nextAttemptAt"))
                .put("sessionCount", job.optInt("sessionCount", job.getJSONArray("sessions").length()))
                .put("cancelledCount", job.optInt("cancelledCount", job.getJSONArray("cancelled").length()))
                .put("resourceCount", job.optInt("resourceCount", resources.length()))
                .put("cleanedCount", job.optInt("cleanedCount", cleaned));
    }

    /** Coalesce completion/acceptance wakeups; the periodic tick is only a retry fallback. */
    private void wake() { wake(0); }
    private void wake(long delayMs) {
        if (!automatic || closed || !wakeQueued.compareAndSet(false, true)) return;
        try { timer.schedule(() -> { wakeQueued.set(false); dispatch(); }, delayMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException stopped) { wakeQueued.set(false); }
    }
    private boolean blocked(JSONObject job) {
        JSONArray sessions = job.optJSONArray("sessions");
        if (sessions == null) return false;
        for (int i = 0; i < sessions.length(); i++) {
            String owner = familyOwners.get(sessions.optString(i));
            if (owner != null && !owner.equals(job.optString("requestId"))) return true;
        }
        return false;
    }
    /** Resume after the last claim, including when earlier jobs are still ready after yielding. */
    void dispatch() {
        if (closed) return;
        if (!lock.tryLock()) { wake(10); return; }
        try {
            List<String> roots = new ArrayList<>(jobs.keySet());
            int start = roots.indexOf(lastClaimed) + 1;
            for (int i = 0; i < roots.size(); i++) {
                if (running.size() >= WORKERS) break;
                String root = roots.get((start + i) % roots.size()); JSONObject job = jobs.get(root);
                if (done(job) && !unconfirmedWrites.contains(root) || Math.max(job.optLong("nextAttemptAt"), deferred.getOrDefault(root, 0L)) > now.getAsLong()
                        || blocked(job) || !running.add(root)) continue;
                try { workers.execute(() -> attempt(root)); lastClaimed = root; }
                catch (RejectedExecutionException full) { running.remove(root); }
            }
        } finally { lock.unlock(); }
    }
    private JSONObject current(String root) throws JSONException {
        try (CleanupWork.Held held = CleanupWork.lock(lock)) { return copy(jobs.get(root)); }
    }
    private boolean checkpoint(String root, String id, long revision, Change change) throws Exception {
        try (CleanupWork.Held held = CleanupWork.lock(lock)) {
            JSONObject current = jobs.get(root);
            if (!current.getString("requestId").equals(id) || current.getLong("revision") != revision) return false;
            JSONObject updated = copy(current); change.apply(updated); save(updated); return true;
        }
    }
    private void attempt(String root) {
        long revision = -1; String id = "";
        boolean release = false;
        try (CleanupWork scope = new CleanupWork(timeoutMs)) {
            JSONObject job = current(root); revision = job.getLong("revision"); id = job.getString("requestId");
            if (done(job)) { checkpoint(root, id, revision, value -> {}); release = true; return; }
            Set<String> sessions = strings(job.getJSONArray("sessions"));
            claim(sessions, id);
            String step = job.optString("step", "verify");
            if (step.equals("verify")) {
                if (!checkpoint(root, id, revision, value -> value.put("status", "running").put("phase", "verifying").put("targetReached", false))) return;
                State state = backend.state(root);
                // While metadata exists, refresh the family before every destructive attempt.
                // Once it is gone, the durable snapshot is the cleanup source of truth.
                if (state != State.ABSENT || !job.getBoolean("familySaved")) {
                    sessions.addAll(backend.family(root));
                    if (!checkpoint(root, id, revision, value -> value.put("sessions", new JSONArray(sessions)).put("familySaved", true))) return;
                }
                claim(sessions, id);
                JSONArray captured = backend.resources(sessions);
                if (!checkpoint(root, id, revision, value -> mergeResources(value, captured))) return;
                job = current(root);
                if (job.getLong("revision") != revision) return;
                String target = job.getString("target");
                if (state != State.ABSENT && !reached(state, target)) {
                    backend.apply(root, target);
                    if (current(root).getLong("revision") != revision) return;
                    state = backend.state(root);
                    if (!reached(state, target)) throw new IOException("SESSION_TARGET_UNCONFIRMED");
                }
                boolean nativeReached = reached(state, target);
                checkpoint(root, id, revision, value -> value.put("targetReached", nativeReached).put("phase", "cleanup")
                        .put("step", "cancel").put("cancelCursor", 0).put("cleanupCursor", 0));
                return;
            }
            Set<String> cancelled = strings(job.getJSONArray("cancelled"));
            if (step.equals("cancel")) {
                List<String> members = new ArrayList<>(sessions);
                for (int i = job.optInt("cancelCursor"); i < members.size(); i++) {
                    String session = members.get(i);
                    if (cancelled.contains(session)) continue;
                    boolean stopped = false;
                    try {
                        backend.cancel(session);
                        stopped = true;
                    } catch (Exception error) {
                        Thread.interrupted(); warn.accept("session cancellation incomplete: " + session + " " + error);
                    }
                    final boolean confirmed = stopped; final int next = i + 1;
                    checkpoint(root, id, revision, value -> {
                        value.put("cancelCursor", next);
                        if (confirmed) value.getJSONArray("cancelled").put(session);
                    });
                    return;
                }
                // A cancellation may have waited for an opening in flight. Capture its final ownership too.
                JSONArray afterCancellation = backend.resources(sessions);
                checkpoint(root, id, revision, value -> {
                    mergeResources(value, afterCancellation); value.put("step", "cleanup").put("cleanupCursor", 0);
                });
                return;
            }
            JSONArray resources = job.getJSONArray("resources");
            for (int i = job.optInt("cleanupCursor"); i < resources.length(); i++) {
                JSONObject resource = resources.getJSONObject(i);
                if (resource.optBoolean("cleaned")) continue;
                if (!cancelled.contains(resource.getString("session"))) continue;
                boolean cleaned = false;
                try {
                    backend.cleanup(copy(resource));
                    cleaned = true;
                } catch (Exception error) {
                    Thread.interrupted(); warn.accept("session resource cleanup incomplete: " + root + " " + error);
                }
                String key = resource.getString("resourceId");
                final boolean confirmed = cleaned; final int next = i + 1;
                checkpoint(root, id, revision, value -> {
                    value.put("cleanupCursor", next);
                    JSONArray saved = value.getJSONArray("resources");
                    for (int n = 0; n < saved.length(); n++)
                        if (confirmed && saved.getJSONObject(n).getString("resourceId").equals(key)) saved.getJSONObject(n).put("cleaned", true);
                });
                return;
            }
            if (!cancelled.containsAll(sessions)) throw new IOException("SESSION_CLEANUP_INCOMPLETE");
            for (int i = 0; i < resources.length(); i++)
                if (!resources.getJSONObject(i).optBoolean("cleaned")) throw new IOException("SESSION_CLEANUP_INCOMPLETE");
            State finalState = backend.state(root);
            boolean nativeReached = reached(finalState, job.getString("target"));
            if (finalState != State.ABSENT && !nativeReached) {
                checkpoint(root, id, revision, value -> value.put("targetReached", false));
                throw new IOException("SESSION_TARGET_UNCONFIRMED");
            }
            backend.forget(sessions);
            boolean finished = checkpoint(root, id, revision, value -> {
                JSONArray saved = value.getJSONArray("resources");
                value.put("sessionCount", sessions.size()).put("cancelledCount", sessions.size())
                        .put("resourceCount", saved.length()).put("cleanedCount", saved.length())
                        .put("status", "complete").put("phase", "complete").put("nextAttemptAt", 0)
                        .put("targetReached", nativeReached).put("outcome", nativeReached ? "" : "no_record")
                        .put("sessions", new JSONArray()).put("cancelled", new JSONArray()).put("resources", new JSONArray());
                value.remove("step"); value.remove("cancelCursor"); value.remove("cleanupCursor");
            });
            release = true;
            if (finished) deferred.remove(root);
            try { prune(); } catch (Exception pruneFailed) { warn.accept("completed removal receipt pruning deferred: " + pruneFailed); }
        } catch (Exception error) {
            release = true;
            warn.accept("session removal attempt deferred: " + root + " " + error);
            // Clear an interrupt before the bounded, independent persistence attempt below.
            Thread.interrupted();
            try (CleanupWork scope = new CleanupWork(timeoutMs); CleanupWork.Held held = CleanupWork.lock(lock)) {
                JSONObject latest = jobs.get(root);
                if (latest.getString("requestId").equals(id) && latest.getLong("revision") == revision) {
                    deferred.put(root, now.getAsLong() + retryMs);
                    JSONObject retry = copy(latest).put("status", "pending").put("phase", "retry")
                            .put("step", "verify").put("nextAttemptAt", now.getAsLong() + retryMs);
                    save(done(latest) ? copy(latest) : retry);
                }
            } catch (Exception saveFailed) { warn.accept("session removal retry checkpoint unavailable: " + root + " " + saveFailed); }
        } finally {
            // Ownership spans yielded slices, but never outlives a failed round or old revision.
            if (!release) try (CleanupWork scope = new CleanupWork(timeoutMs); CleanupWork.Held held = CleanupWork.lock(lock)) {
                JSONObject latest = jobs.get(root);
                release = latest == null || latest.optLong("revision") != revision;
            } catch (Exception unlockFailed) { release = true; warn.accept("session family slice ended: " + root + " " + unlockFailed); }
            if (release) {
                final String owner = id;
                familyOwners.entrySet().removeIf(entry -> entry.getValue().equals(owner));
            }
            running.remove(root);
            Thread.interrupted();
            wake();
        }
    }
    private void claim(Set<String> sessions, String id) throws IOException {
        try (CleanupWork.Held held = CleanupWork.lock(lock)) {
            for (String session : sessions) {
                String owner = familyOwners.get(session);
                if (owner != null && !owner.equals(id)) throw new IOException("SESSION_FAMILY_ATTEMPT_BUSY");
            }
            for (String session : sessions) familyOwners.put(session, id);
        }
    }
    private static boolean reached(State state, String target) {
        return target.equals("delete") ? state == State.ABSENT : state == State.ARCHIVED;
    }
    private static void mergeResources(JSONObject job, JSONArray captured) throws JSONException {
        JSONArray saved = job.getJSONArray("resources"); Set<String> ids = new HashSet<>();
        for (int i = 0; i < saved.length(); i++) ids.add(saved.getJSONObject(i).getString("resourceId"));
        for (int i = 0; i < captured.length(); i++) {
            JSONObject item = captured.getJSONObject(i);
            if (ids.add(item.getString("resourceId"))) saved.put(copy(item));
        }
    }
    private void save(JSONObject job) throws Exception {
        job.put("updatedAt", now.getAsLong());
        File destination = new File(directory, job.getString("threadId") + ".json");
        File temporary = new File(destination.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(job.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------"));
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        String root = job.getString("threadId");
        jobs.put(root, job); unconfirmedWrites.add(root); publish();
        syncDirectory();
        unconfirmedWrites.remove(root); publish();
    }
    private void syncDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { channel.force(true); }
    }
    private void publish() throws JSONException {
        Set<String> pending = new HashSet<>();
        for (JSONObject job : jobs.values()) if (!done(job) || unconfirmedWrites.contains(job.getString("threadId"))) {
            pending.add(job.getString("threadId")); pending.addAll(strings(job.getJSONArray("sessions")));
        }
        fenced = Set.copyOf(pending);
    }
    private void prune() throws Exception {
        try (CleanupWork.Held held = CleanupWork.lock(lock)) {
            List<JSONObject> completed = new ArrayList<>();
            for (JSONObject job : jobs.values()) if (done(job) && !unconfirmedWrites.contains(job.getString("threadId"))) completed.add(job);
            completed.sort(Comparator.comparingLong(value -> value.optLong("updatedAt")));
            for (int i = 0; i < completed.size() - COMPLETED_LIMIT; i++) {
                String root = completed.get(i).getString("threadId");
                Files.deleteIfExists(new File(directory, root + ".json").toPath()); jobs.remove(root);
            }
            if (completed.size() > COMPLETED_LIMIT) syncDirectory();
        }
    }
    int activeAttempts() { return running.size(); }
    public void close() {
        closed = true; timer.shutdownNow(); workers.shutdownNow();
    }
}
