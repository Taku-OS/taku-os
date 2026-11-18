package org.agentos.lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.*;

/** Durable production coordinator, real files/locks/processes, deterministic native endpoint doubles. */
public final class SessionRemovalsTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String id(int n) { return String.format("00000000-0000-0000-0000-%012d", n); }
    interface Ready { boolean get() throws Exception; }
    static void until(Ready ready, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.get() && System.nanoTime() < end) Thread.sleep(2);
        check(ready.get(), message);
    }
    static void cycle(SessionRemovals store) throws Exception {
        SessionRemovalsRoundRobinTest.drain(store);
    }
    static class Fake implements SessionRemovals.Backend {
        final Map<String, SessionRemovals.State> states = new ConcurrentHashMap<>();
        final List<String> applied = new CopyOnWriteArrayList<>(), cancelled = new CopyOnWriteArrayList<>(), cleaned = new CopyOnWriteArrayList<>();
        final AtomicInteger families = new AtomicInteger(), inFamily = new AtomicInteger(), peak = new AtomicInteger();
        volatile String badCancel = "", badResource = "";
        volatile boolean queryFails, familyFails, blockFamily;
        volatile CompletableFuture<Void> archiveGate;
        final CountDownLatch applying = new CountDownLatch(1);
        Set<String> members = new LinkedHashSet<>(List.of(id(1), id(2), id(3)));
        public Set<String> family(String root) throws Exception {
            families.incrementAndGet(); int active = inFamily.incrementAndGet(); peak.accumulateAndGet(active, Math::max);
            try {
                if (familyFails) throw new IOException("SESSION_FAMILY_INCOMPLETE");
                if (blockFamily) new CompletableFuture<>().get(CleanupWork.timeout(), TimeUnit.MILLISECONDS);
                return root.equals(id(1)) ? new LinkedHashSet<>(members) : Set.of(root);
            } finally { inFamily.decrementAndGet(); }
        }
        public JSONArray resources(Set<String> sessions) {
            JSONArray result = new JSONArray();
            for (String session : sessions) result.put(new JSONObject().put("session", session).put("resourceId", session));
            return result;
        }
        public SessionRemovals.State state(String root) throws Exception {
            if (queryFails) throw new TimeoutException("query timed out");
            return states.getOrDefault(root, SessionRemovals.State.ACTIVE);
        }
        public void apply(String root, String target) throws Exception {
            applied.add(root + ":" + target); applying.countDown();
            CompletableFuture<Void> gate = archiveGate;
            if (target.equals("archive") && gate != null) gate.get(CleanupWork.timeout(), TimeUnit.MILLISECONDS);
            states.put(root, target.equals("delete") ? SessionRemovals.State.ABSENT : SessionRemovals.State.ARCHIVED);
        }
        public void cancel(String session) throws Exception {
            cancelled.add(session); if (session.equals(badCancel)) throw new IOException("local cancellation failed");
        }
        public void cleanup(JSONObject resource) throws Exception {
            String resourceId = resource.getString("resourceId"); cleaned.add(resourceId);
            if (resourceId.equals(badResource)) throw new IOException("resource cleanup failed");
        }
        public void forget(Set<String> sessions) {}
    }
    static SessionRemovals store(Path path, Fake fake, AtomicLong clock) throws Exception {
        return new SessionRemovals(path.toFile(), fake, message -> {}, clock::get, 70, 30_000, false);
    }
    static void durableDedupAndUpgrade() throws Exception {
        Path path = Files.createTempDirectory("removal-dedup"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        // This case checks concurrent durable dedup, not a 70ms disk/lock deadline under build load.
        try (SessionRemovals store = new SessionRemovals(path.toFile(), fake, message -> {}, clock::get, 2000, 30000, false)) {
            JSONObject accepted = store.submit(id(1), "archive");
            check(accepted.getBoolean("accepted") && !accepted.getBoolean("completed"), "acceptance is never native completion");
            check(Files.readString(path.resolve(id(1) + ".json")).contains(accepted.getString("requestId")), "receipt exists on disk before acceptance returns");
            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                List<Future<JSONObject>> replies = new ArrayList<>();
                for (int i = 0; i < 40; i++) replies.add(callers.submit(() -> store.submit(id(1), "archive")));
                for (Future<JSONObject> reply : replies) check(reply.get().getString("requestId").equals(accepted.getString("requestId")), "concurrent duplicates reuse one intent");
            } finally { callers.shutdownNow(); }
            JSONObject upgraded = store.submit(id(1), "delete");
            check(upgraded.getString("requestId").equals(accepted.getString("requestId")) && upgraded.getLong("revision") == 2, "delete atomically upgrades the original intent");
            check(store.submit(id(1), "archive").getString("target").equals("delete"), "archive cannot downgrade a delete");
            check(store.list().getJSONArray("data").length() == 1, "only one effective row exists");
            cycle(store);
            check(fake.applied.equals(List.of(id(1) + ":delete")), "queued archive is skipped after upgrade");
            check(store.query(id(1), "").getBoolean("completed"), "target plus family cleanup completes the row");
            check(!store.pending(id(1)), "completed family no longer fences execution");
            JSONObject disk = new JSONObject(Files.readString(path.resolve(id(1) + ".json")));
            check(disk.getJSONArray("resources").isEmpty() && disk.getJSONArray("sessions").isEmpty(), "completed cleanup evidence is retired");
        }
    }
    static void inFlightUpgrade(boolean timeout) throws Exception {
        Path path = Files.createTempDirectory("removal-upgrade"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        fake.archiveGate = new CompletableFuture<>();
        try (SessionRemovals store = store(path, fake, clock)) {
            store.submit(id(1), "archive"); store.dispatch();
            check(fake.applying.await(2, TimeUnit.SECONDS), "archive attempt entered the native call");
            JSONObject upgraded = store.submit(id(1), "delete");
            store.dispatch(); check(store.activeAttempts() == 1, "upgrading never starts a second attempt for this session");
            if (!timeout) fake.archiveGate.complete(null);
            until(() -> store.activeAttempts() == 0, "old attempt returns after reply or timeout");
            check(!store.query(id(1), "").getBoolean("completed"), "old archive cannot complete the upgraded delete");
            fake.archiveGate.complete(null); fake.states.put(id(1), SessionRemovals.State.ARCHIVED); // late upstream outcome
            clock.addAndGet(30_000); cycle(store);
            JSONObject result = store.query(id(1), upgraded.getString("requestId"));
            check(result.getString("target").equals("delete") && result.getBoolean("completed"), "late archive never replaces the current delete target");
            check(Collections.frequency(fake.applied, id(1) + ":archive") == 1 && Collections.frequency(fake.applied, id(1) + ":delete") == 1,
                    "only the latest target is retried after upgrade");
        }
    }
    static void partialCleanupAndRestart() throws Exception {
        Path path = Files.createTempDirectory("removal-restart"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        fake.badCancel = id(2);
        String request;
        try (SessionRemovals store = store(path, fake, clock)) {
            request = store.submit(id(1), "delete").getString("requestId"); cycle(store);
            check(fake.cancelled.contains(id(3)), "one member's cancellation failure never skips later members");
            check(!fake.cleaned.contains(id(2)) && fake.cleaned.contains(id(3)), "failed cancellation retains that member's resources while others finish");
            JSONObject result = store.query(id(1), request);
            check(result.getBoolean("targetReached") && !result.getBoolean("completed") && store.pending(id(2)), "native absence alone never completes a partial cleanup");
        }
        fake.badCancel = ""; fake.badResource = id(2); fake.familyFails = true;
        try (SessionRemovals recovered = store(path, fake, clock)) {
            check(recovered.pending(id(2)) && recovered.query(id(1), request).getString("requestId").equals(request), "restart restores the family fence and original receipt");
            cycle(recovered);
            check(!recovered.query(id(1), request).getBoolean("completed"), "resource failure stays pending after a successful cancellation");
            check(Collections.frequency(fake.cancelled, id(3)) == 1, "successful cancellation checkpoints survive restart");
            fake.badResource = ""; clock.addAndGet(30_000); cycle(recovered);
            check(recovered.query(id(1), request).getBoolean("completed"), "saved family evidence permits cleanup after upstream metadata disappeared");
            check(Collections.frequency(fake.applied, id(1) + ":delete") == 1, "confirmed absence stops repeated native deletion");
            java.lang.reflect.Field deferred = SessionRemovals.class.getDeclaredField("deferred"); deferred.setAccessible(true);
            check(((Map<?, ?>) deferred.get(recovered)).isEmpty(), "completed work retires its in-memory retry evidence");
        }
    }
    static void failedEnumerationAndQueries() throws Exception {
        Path path = Files.createTempDirectory("removal-unknown"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        try (SessionRemovals store = store(path, fake, clock)) {
            fake.familyFails = true; store.submit(id(1), "delete"); cycle(store);
            check(fake.applied.isEmpty() && store.pending(id(1)), "incomplete family cannot cause deletion or loss of accepted work");
            fake.familyFails = false; fake.queryFails = true; clock.addAndGet(30_000); cycle(store);
            check(fake.applied.isEmpty() && !store.query(id(1), "").getBoolean("targetReached"), "query timeout is never absence");
            fake.queryFails = false; clock.addAndGet(30_000); cycle(store);
            check(store.query(id(1), "").getBoolean("completed"), "the same intent recovers after query failure");
            fake.states.put(id(4), SessionRemovals.State.ABSENT); store.submit(id(4), "archive"); cycle(store);
            check(store.query(id(4), "").getBoolean("completed") && !store.query(id(4), "").getBoolean("targetReached"),
                    "a missing archive ends after cleanup without reporting it as archived");
        }
        PhoneSubAgents.Rpc incomplete = (method, params) -> new JSONObject().put("data", new JSONArray().put(new JSONObject().put("id", id(1)))).put("nextCursor", "again");
        try { PhoneSubAgents.removalState(incomplete, id(4)); throw new AssertionError("incomplete enumeration succeeded"); }
        catch (IOException expected) { check(true, "real pagination rejects repeated cursors"); }
        PhoneSubAgents.Rpc absent = (method, params) -> new JSONObject().put("data", new JSONArray()).put("nextCursor", JSONObject.NULL);
        check(!PhoneSubAgents.removed(absent, id(1), true), "real archived verification rejects mere absence");
    }
    static void boundedWorkers() throws Exception {
        Path path = Files.createTempDirectory("removal-workers"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        fake.blockFamily = true;
        try (SessionRemovals store = store(path, fake, clock)) {
            for (int i = 10; i < 30; i++) store.submit(id(i), "delete");
            store.dispatch(); check(store.activeAttempts() == 3, "only three execution positions are claimed");
            until(() -> store.activeAttempts() == 0, "timed-out native waits really return and free all three positions");
            check(fake.peak.get() <= 3 && fake.families.get() == 3, "the full durable table is not copied into a worker queue");
            check(store.list().getJSONArray("data").length() == 20, "claiming and failure never remove accepted rows");
            fake.blockFamily = false; clock.addAndGet(30_000);
            for (int round = 0; round < 10; round++) cycle(store);
            for (int i = 10; i < 30; i++) check(store.query(id(i), "").getBoolean("completed"), "later work uses the released positions");
        }
    }
    static void locksAndCommands() throws Exception {
        ReentrantLock lock = new ReentrantLock(); lock.lock();
        AtomicBoolean exited = new AtomicBoolean();
        Thread blocked = new Thread(() -> {
            try (CleanupWork scope = new CleanupWork(50); CleanupWork.Held held = CleanupWork.lock(lock)) {
                throw new AssertionError("blocked lock unexpectedly acquired");
            } catch (CleanupWork.WaitFailed expected) { exited.set(true); }
        });
        blocked.start(); blocked.join(2_000); lock.unlock();
        check(exited.get() && !blocked.isAlive(), "a lock timeout exits the actual worker");
        File directory = Files.createTempDirectory("cleanup-command").toFile();
        long start = System.nanoTime();
        try { BoundedCommand.run(directory, 70, "/bin/sh", "-c", "printf started; exec sleep 10"); throw new AssertionError("hung command succeeded"); }
        catch (TimeoutException expected) { check(true, "a non-exiting child times out"); }
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000, "child exit and output capture do not retain the worker");
        start = System.nanoTime();
        check(BoundedCommand.run(directory, 1_000, "/bin/sh", "-c", "sleep 0.5 & printf done").equals("done"), "an inherited open stdout cannot hold the parent reader");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 400, "there is no pipe reader waiting for the descendant to close output");
        check(directory.list().length == 0, "command output files are retired");
    }
    static void completedRetention() throws Exception {
        Path path = Files.createTempDirectory("removal-retention"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        try (SessionRemovals store = store(path, fake, clock)) {
            for (int i = 100; i < 203; i++) { clock.incrementAndGet(); store.submit(id(i), "delete"); cycle(store); }
            check(store.list().getJSONArray("data").length() == SessionRemovals.COMPLETED_LIMIT, "completed receipts have bounded retention");
            check(store.query(id(100), "").getString("status").equals("unavailable"), "an expired receipt is not invented as a success");
        }
    }
    static void crashChild(Path path, String phase) throws Exception {
        Path nativeState = path.resolve("native-state");
        Fake fake = new Fake() {
            public SessionRemovals.State state(String root) throws Exception {
                if (phase.equals("claimed")) Runtime.getRuntime().halt(17);
                return Files.exists(nativeState) ? SessionRemovals.State.ABSENT : SessionRemovals.State.ACTIVE;
            }
            public void apply(String root, String target) throws Exception {
                try (FileOutputStream output = new FileOutputStream(nativeState.toFile())) { output.write(1); output.getFD().sync(); }
                if (phase.equals("native-applied")) Runtime.getRuntime().halt(17);
            }
            public void cancel(String session) throws Exception {
                if (phase.equals("partial-cancel") && session.equals(id(2))) Runtime.getRuntime().halt(17);
                super.cancel(session);
            }
            public void cleanup(JSONObject resource) throws Exception {
                if (phase.equals("partial-cleanup") && resource.getString("session").equals(id(2))) Runtime.getRuntime().halt(17);
            }
        };
        SessionRemovals store = store(path, fake, new AtomicLong(1_000));
        store.submit(id(1), "delete");
        if (phase.equals("accepted")) Runtime.getRuntime().halt(17);
        while (true) { store.dispatch(); Thread.sleep(2); }
    }
    static void abruptProcessRecovery() throws Exception {
        for (String phase : List.of("accepted", "claimed", "native-applied", "partial-cancel", "partial-cleanup")) {
            Path path = Files.createTempDirectory("removal-crash-" + phase);
            java.lang.Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                    "-cp", System.getProperty("java.class.path"), SessionRemovalsTest.class.getName(), "crash", path.toString(), phase)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                check(child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 17, "real JVM halted at " + phase);
            } finally { if (child.isAlive()) child.destroyForcibly(); }
            boolean nativeApplied = Files.exists(path.resolve("native-state"));
            Fake fake = new Fake(); fake.states.put(id(1), nativeApplied ? SessionRemovals.State.ABSENT : SessionRemovals.State.ACTIVE);
            fake.familyFails = nativeApplied;
            try (SessionRemovals recovered = store(path, fake, new AtomicLong(2_000))) {
                check(recovered.pending(id(1)), "restart restores accepted work after " + phase);
                cycle(recovered);
                check(recovered.query(id(1), "").getBoolean("completed"), "real crash recovery completes after " + phase);
                check(fake.applied.size() == (nativeApplied ? 0 : 1), "recovery verifies native state before replay after " + phase);
                if (phase.equals("partial-cleanup")) check(!fake.cleaned.contains(id(1)), "persisted successful cleanup survives abrupt JVM death");
                if (phase.equals("partial-cancel")) check(!fake.cancelled.contains(id(1)), "persisted cancellation survives death between member slices");
            }
        }
    }
    static void fixedProductionTimeout() throws Exception {
        Path path = Files.createTempDirectory("removal-fixed-timeout"); Fake fake = new Fake(); fake.blockFamily = true;
        long start = System.nanoTime();
        try (SessionRemovals store = new SessionRemovals(path.toFile(), fake, message -> {})) {
            for (int n = 10; n < 13; n++) store.submit(id(n), "delete");
            store.dispatch();
            java.lang.reflect.Field field = SessionRemovals.class.getDeclaredField("workers"); field.setAccessible(true);
            ThreadPoolExecutor workers = (ThreadPoolExecutor) field.get(store);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (workers.getCompletedTaskCount() < 3 && System.nanoTime() < end) Thread.sleep(10);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(workers.getCompletedTaskCount() == 3 && store.activeAttempts() == 0, "all three actual Runnable.run calls returned under the default timeout");
            check(elapsed >= 24_000 && elapsed < 32_000, "production fixed wait is 25 seconds, observed " + elapsed + "ms");
            check(fake.peak.get() <= 3 && store.list().getJSONArray("data").length() == 3, "workers stay bounded and timed-out work remains durable");
            System.out.println("SessionRemovals production timeout: " + checks + " checks passed; three real attempts returned in " + elapsed + "ms");
        }
    }
    static void commandTimeoutInsideWorker() throws Exception {
        Path path = Files.createTempDirectory("removal-command-attempt"); AtomicLong clock = new AtomicLong(1_000);
        AtomicBoolean stalled = new AtomicBoolean(true);
        Fake fake = new Fake() {
            public void apply(String root, String target) throws Exception {
                if (stalled.get()) BoundedCommand.run(path.toFile(), CleanupWork.timeout(), "/bin/sh", "-c", "printf started; exec sleep 10");
                super.apply(root, target);
            }
        };
        try (SessionRemovals store = store(path, fake, clock)) {
            store.submit(id(10), "delete"); cycle(store);
            check(!store.query(id(10), "").getBoolean("completed") && fake.cancelled.isEmpty(), "a real command timeout exits the worker without premature cancellation or release");
            stalled.set(false); clock.addAndGet(30_000); cycle(store);
            check(store.query(id(10), "").getBoolean("completed"), "the same execution position processes a later successful command attempt");
        }
    }
    static void overlappingFamilies() throws Exception {
        Path path = Files.createTempDirectory("removal-overlap"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        fake.archiveGate = new CompletableFuture<>();
        try (SessionRemovals store = store(path, fake, clock)) {
            store.submit(id(1), "archive"); store.dispatch();
            check(fake.applying.await(2, TimeUnit.SECONDS), "parent has claimed its saved family");
            store.submit(id(2), "delete"); store.dispatch();
            Thread.sleep(10);
            check(!fake.applied.contains(id(2) + ":delete"), "an overlapping child's local attempt cannot run alongside the parent");
            fake.archiveGate.complete(null); until(() -> store.activeAttempts() == 0, "overlapping attempts return");
            clock.addAndGet(30_000); cycle(store);
            check(store.query(id(2), "").getBoolean("completed"), "the deferred child proceeds after the family owner exits");
        }
    }
    static void corruptAndFailedPersistence() throws Exception {
        Path path = Files.createTempDirectory("removal-corrupt"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        try (SessionRemovals store = store(path, fake, clock)) { store.submit(id(1), "delete"); }
        JSONObject saved = new JSONObject(Files.readString(path.resolve(id(1) + ".json"))); saved.remove("requestId");
        Files.writeString(path.resolve(id(1) + ".json"), saved.toString());
        try { store(path, fake, clock).close(); throw new AssertionError("damaged pending record silently ignored"); }
        catch (Exception expected) { check(true, "a corrupt durable row fails recovery closed"); }
        Path unwritable = Files.createTempDirectory("removal-write-failure");
        try (SessionRemovals store = store(unwritable, fake, clock)) {
            Files.createDirectory(unwritable.resolve(id(10) + ".json.tmp"));
            try { store.submit(id(10), "delete"); throw new AssertionError("failed durable write accepted"); }
            catch (IOException expected) { check(true, "failed file creation cannot report acceptance"); }
            check(store.query(id(10), "").getString("status").equals("unavailable") && fake.applied.isEmpty(), "failed persistence neither creates a visible accepted job nor invokes Codex");
        }
    }
    static void archivedGoalRevalidation() throws Exception {
        Path path = Files.createTempDirectory("removal-archive-state"); Fake fake = new Fake(); AtomicLong clock = new AtomicLong(1_000);
        fake.badCancel = id(2);
        try (SessionRemovals store = store(path, fake, clock)) {
            store.submit(id(1), "archive"); cycle(store);
            check(store.query(id(1), "").getBoolean("targetReached"), "successful archive is recorded before partial cleanup");
            fake.states.put(id(1), SessionRemovals.State.ABSENT); fake.badCancel = ""; clock.addAndGet(30_000); cycle(store);
            JSONObject result = store.query(id(1), "");
            check(!result.getBoolean("targetReached") && result.getBoolean("completed") && result.getString("outcome").equals("no_record"),
                    "confirmed disappearance ends cleanup without retaining a stale archived-success flag");
            store.submit(id(1), "delete"); cycle(store);
            check(store.query(id(1), "").getBoolean("completed"), "upgrading an already absent session continues only its saved cleanup");
        }
    }
    static void emptySessions() throws Exception {
        AtomicBoolean loaded = new AtomicBoolean(true), archived = new AtomicBoolean(), unavailable = new AtomicBoolean();
        PhoneSubAgents.Rpc nativeState = (method, params) -> {
            JSONArray data = new JSONArray();
            if (method.equals("thread/loaded/list")) {
                if (unavailable.get()) throw new TimeoutException("loaded-state query failed");
                if (loaded.get()) data.put(id(4));
            } else if (params.optBoolean("archived") && archived.get()) data.put(new JSONObject().put("id", id(4)));
            return new JSONObject().put("data", data).put("nextCursor", JSONObject.NULL);
        };
        check(PhoneSubAgents.removalState(nativeState, id(4)) == SessionRemovals.State.ACTIVE,
                "loaded empty session is present even before it appears in history lists");
        for (String target : List.of("archive", "delete")) {
            loaded.set(true); archived.set(false); AtomicLong clock = new AtomicLong(1000);
            Fake fake = new Fake() {
                public SessionRemovals.State state(String root) throws Exception { return PhoneSubAgents.removalState(nativeState, root); }
                public void apply(String root, String target) throws Exception {
                    super.apply(root, target); loaded.set(false); archived.set(target.equals("archive"));
                }
            };
            try (SessionRemovals store = store(Files.createTempDirectory("removal-loaded-empty"), fake, clock)) {
                store.submit(id(4), target); cycle(store);
                JSONObject result = store.query(id(4), "");
                check(result.getBoolean("completed") && result.getBoolean("targetReached"), "native " + target + " handles a loaded empty session");
                check(fake.applied.equals(List.of(id(4) + ":" + target)), "loaded empty session actually reaches native " + target);
                check(fake.cleaned.equals(List.of(id(4))), "loaded empty session still releases its local resource");
            }
        }
        loaded.set(false); archived.set(false); unavailable.set(true);
        Path path = Files.createTempDirectory("removal-no-record"); AtomicLong clock = new AtomicLong(1000);
        Fake missing = new Fake() {
            public SessionRemovals.State state(String root) throws Exception { return PhoneSubAgents.removalState(nativeState, root); }
        };
        String request;
        try (SessionRemovals store = store(path, missing, clock)) {
            request = store.submit(id(4), "archive").getString("requestId"); cycle(store);
            check(store.query(id(4), "").getString("phase").equals("retry") && missing.cancelled.isEmpty(),
                    "failed loaded lookup retains work and cannot be mistaken for absence");
        }
        unavailable.set(false); missing.badCancel = id(4);
        try (SessionRemovals store = store(path, missing, clock)) {
            cycle(store);
            check(!store.query(id(4), request).getBoolean("completed") && store.pending(id(4)) && missing.cleaned.isEmpty(),
                    "no-record archive cannot end before failed local cancellation is resolved");
            missing.badCancel = ""; clock.addAndGet(30000); cycle(store);
            JSONObject result = store.query(id(4), request);
            check(result.getBoolean("completed") && !result.getBoolean("targetReached") && result.getString("outcome").equals("no_record"),
                    "a truly absent archive ends explicitly without claiming archival success");
            check(result.getLong("nextAttemptAt") == 0 && !store.pending(id(4)) && missing.applied.isEmpty(),
                    "no-record archive stops native retry after local cleanup");
        }
        try (SessionRemovals recovered = store(path, missing, clock)) {
            cycle(recovered);
            check(recovered.query(id(4), request).getString("outcome").equals("no_record") && missing.cleaned.size() == 1,
                    "terminal no-record result survives restart without repeating cleanup");
        }
        PhoneSubAgents.Rpc partial = (method, params) -> new JSONObject().put("data", new JSONArray())
                .put("nextCursor", method.equals("thread/loaded/list") ? "more" : JSONObject.NULL);
        try { PhoneSubAgents.removalState(partial, id(4)); throw new AssertionError("partial loaded listing became absence"); }
        catch (IOException expected) { check(true, "incomplete loaded listing never authorizes a no-record result"); }
    }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].equals("crash")) { crashChild(Path.of(arguments[1]), arguments[2]); return; }
        if (arguments.length > 0 && arguments[0].equals("fixed-timeout")) { fixedProductionTimeout(); return; }
        if (arguments.length > 0 && arguments[0].equals("empty-sessions")) {
            emptySessions(); System.out.println("Empty session removals: " + checks + " checks passed"); return;
        }
        durableDedupAndUpgrade(); inFlightUpgrade(false); inFlightUpgrade(true); partialCleanupAndRestart();
        failedEnumerationAndQueries(); boundedWorkers(); locksAndCommands(); completedRetention(); abruptProcessRecovery();
        commandTimeoutInsideWorker(); overlappingFamilies(); corruptAndFailedPersistence();
        archivedGoalRevalidation();
        emptySessions();
        System.out.println("SessionRemovalsTest: " + checks + " checks passed (real durable coordinator, locks and child processes)");
    }
}
