package org.agentos.lab;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import static org.agentos.lab.SessionRemovalsTest.*;

/** Exercises production scheduling and durable checkpoints, with only native/device endpoints replaced. */
public final class SessionRemovalsRoundRobinTest {
    static void step(SessionRemovals store) throws Exception {
        store.dispatch(); until(() -> store.activeAttempts() == 0, "one scheduled slice returns");
    }
    static void drain(SessionRemovals store) throws Exception {
        for (int n = 0; n < 300; n++) {
            step(store);
            JSONArray rows = store.list().getJSONArray("data"); boolean ready = false;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                ready |= !row.getBoolean("completed") && !row.getString("phase").equals("retry");
            }
            if (!ready) return;
        }
        throw new AssertionError("ready work failed to drain");
    }
    static void fairness() throws Exception {
        Fake fake = new Fake() {
            public Set<String> family(String root) {
                int base = Integer.parseInt(root.substring(24)); Set<String> result = new LinkedHashSet<>();
                for (int n = 0; n < (base == 900 ? 1 : 12); n++) result.add(id(base + n));
                return result;
            }
        };
        try (SessionRemovals store = store(Files.createTempDirectory("removal-fairness"), fake, new AtomicLong(1000))) {
            for (int n : List.of(100, 200, 300)) store.submit(id(n), "delete");
            step(store);
            store.submit(id(900), "delete");
            for (int n = 0; n < 100 && !store.query(id(900), "").getBoolean("completed"); n++) step(store);
            check(store.query(id(900), "").getBoolean("completed"), "small job completes without advancing the retry clock");
            for (int n : List.of(100, 200, 300))
                check(!store.query(id(n), "").getBoolean("completed"), "small job finishes before each earlier large family");
            drain(store);
            for (int n : List.of(100, 200, 300)) check(store.query(id(n), "").getBoolean("completed"), "large jobs also finish without starvation");
        }
    }
    static void singleStepAndFailureRecovery() throws Exception {
        Path path = Files.createTempDirectory("removal-slices"); Fake fake = new Fake(); AtomicLong now = new AtomicLong(1000);
        fake.badCancel = id(1);
        try (SessionRemovals store = store(path, fake, now)) {
            store.submit(id(1), "delete"); step(store);
            check(fake.cancelled.isEmpty() && fake.cleaned.isEmpty(), "native slice yields before local cancellation");
            step(store);
            check(fake.cancelled.equals(List.of(id(1))), "one slice attempts only one cancellation");
            drain(store);
            check(fake.cancelled.equals(List.of(id(1), id(2), id(3))), "failed first member cannot starve later members");
            check(!fake.cleaned.contains(id(1)) && fake.cleaned.containsAll(List.of(id(2), id(3))), "only stopped members release resources");
            check(store.pending(id(1)) && store.query(id(1), "").getString("phase").equals("retry"), "failed member keeps the original fence and retry");
            check(store.query(id(1), "").getLong("nextAttemptAt") == 31000, "only a failed round enters the 30 second backoff");
        }
        fake.badCancel = ""; fake.familyFails = true;
        try (SessionRemovals recovered = store(path, fake, now)) {
            drain(recovered);
            check(recovered.query(id(1), "").getBoolean("completed"), "restart resumes the durable family after metadata is gone");
            check(Collections.frequency(fake.cancelled, id(2)) == 1 && Collections.frequency(fake.cleaned, id(2)) == 1,
                    "restart skips persisted successful cancellation and cleanup");
            check(fake.applied.size() == 1, "restart never repeats a confirmed native delete");
        }
    }
    static void overlapAndUpgradeBetweenSlices() throws Exception {
        Fake fake = new Fake(); AtomicLong now = new AtomicLong(1000);
        try (SessionRemovals store = store(Files.createTempDirectory("removal-slice-owner"), fake, now)) {
            store.submit(id(1), "archive"); step(store);
            store.submit(id(2), "delete");
            step(store);
            check(!fake.applied.contains(id(2) + ":delete"), "family ownership survives a yield between different worker threads");
            JSONObject upgrade = store.submit(id(1), "delete");
            check(upgrade.getLong("revision") == 2 && !upgrade.getBoolean("completed"), "between-slice upgrade retains the original intent");
            drain(store);
            check(store.query(id(1), "").getBoolean("completed") && store.query(id(2), "").getBoolean("completed"), "overlapping jobs eventually finish without concurrent ownership");
            check(Collections.frequency(fake.applied, id(1) + ":archive") == 1
                    && Collections.frequency(fake.applied, id(1) + ":delete") == 1, "new revision advances deletion without replaying archive");
        }
    }
    static void automaticWakeup() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        AtomicBoolean concurrent = new AtomicBoolean(); Set<String> inCall = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Fake checked = new Fake() {
            public void cancel(String session) throws Exception {
                if (!inCall.add(session)) concurrent.set(true);
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { Thread.sleep(3); super.cancel(session); }
                finally { active.decrementAndGet(); inCall.remove(session); }
            }
        };
        long start = System.nanoTime();
        try (SessionRemovals store = new SessionRemovals(Files.createTempDirectory("removal-wakeup").toFile(), checked,
                message -> {}, System::currentTimeMillis, 1000, 30000, true)) {
            for (int n = 10; n < 16; n++) store.submit(id(n), "delete");
            until(() -> store.query(id(15), "").getBoolean("completed"), "automatic workers promptly resume yielded steps");
            check((System.nanoTime() - start) / 1_000_000 < 2500, "successful slices do not each wait for the one-second poll");
            check(!concurrent.get() && peak.get() <= 3, "same member never runs concurrently and the global pool stays bounded");
        }
    }
    static void failuresYieldToOtherJobs() throws Exception {
        AtomicBoolean interrupt = new AtomicBoolean(true); AtomicLong now = new AtomicLong(1000);
        Fake fake = new Fake() {
            public void cancel(String session) throws Exception {
                if (session.equals(id(1)) && interrupt.get()) {
                    cancelled.add(session); Thread.currentThread().interrupt(); throw new InterruptedException("cancel interrupted");
                }
                super.cancel(session);
            }
        };
        fake.badResource = id(2);
        try (SessionRemovals store = store(Files.createTempDirectory("removal-failed-slices"), fake, now)) {
            store.submit(id(1), "delete"); store.submit(id(10), "delete"); drain(store);
            check(store.query(id(10), "").getBoolean("completed"), "other job finishes while interrupted cancellation and cleanup fail");
            check(fake.cancelled.contains(id(3)) && fake.cleaned.contains(id(3)), "interrupted cancellation and failed cleanup cannot skip later members/resources");
            check(!fake.cleaned.contains(id(1)) && Collections.frequency(fake.cleaned, id(2)) == 1,
                    "failed members retain protection and a failed resource is not hot-looped");
            interrupt.set(false); fake.badResource = ""; now.addAndGet(30000); drain(store);
            check(store.query(id(1), "").getBoolean("completed"), "original job resumes after failed steps recover");
            check(Collections.frequency(fake.cleaned, id(3)) == 1, "retry skips an already successful later resource");
        }
    }
    public static void main(String[] args) throws Exception {
        fairness(); singleStepAndFailureRecovery(); overlapAndUpgradeBetweenSlices(); automaticWakeup(); failuresYieldToOtherJobs();
        System.out.println("SessionRemovalsRoundRobinTest: " + checks + " checks passed (production Java scheduling and files)");
    }
}
