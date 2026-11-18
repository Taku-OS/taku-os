package org.agentos.lab;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

import static org.agentos.lab.DedicatedScreensTest.*;

/** Spec 4: lowered limits, synchronous idle retirement, and concurrent acquisition. */
public final class DedicatedScreensCapacityTest {
    static void limit(int value) throws Exception {
        WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", value));
    }

    static JSONObject request(DedicatedScreens screens, int n, String pkg) throws Exception {
        return screens.request(session(n), apps(pkg), "capacity test");
    }

    static void end(DedicatedScreens screens, int n) throws Exception {
        Thread worker = screens.turnEnded(session(n));
        check(worker != null, "occupied screen begins cleanup");
        worker.join(5_000);
        check(!worker.isAlive(), "turn cleanup completed");
    }

    static void loweredIdleLimit() throws Exception {
        limit(2);
        Rig rig = new Rig();
        Set<String> old = Set.of(rig.request(1, "old", "com.x").getString("screenKey"),
                rig.request(2, "old", "com.y").getString("screenKey"));
        end(rig.screens, 1);
        end(rig.screens, 2);
        limit(1);
        check(rig.host.stopped.isEmpty(), "lowering the policy alone does not close cached screens");
        JSONObject granted = rig.request(3, "new", "com.z");
        check(old.contains(granted.getString("screenKey")), "one old idle screen is reused");
        check(rig.host.stopped.size() == 1 && rig.host.slots.size() == 2,
                "Spec 4: 2 to 1 closes the excess idle screen before request returns");
        rejects("SCREEN_LIMIT_REACHED", () -> rig.request(4, "new", "com.w"));
        rig.clock.advance(DedicatedScreens.IDLE_MS);
        check(rig.host.stopped.size() == 1, "old idle timers cannot close a retired screen twice or the reused screen");
    }

    static void occupiedAndRejectedCleanup() throws Exception {
        limit(3);
        Rig rig = new Rig();
        JSONObject first = rig.request(1, "old", "com.x");
        JSONObject second = rig.request(2, "old", "com.y");
        String idle = rig.request(3, "old", "com.z").getString("screenKey");
        end(rig.screens, 3);
        limit(1);
        check(rig.request(1, "append", "com.more").getString("screenKey")
                .equals(first.getString("screenKey")), "existing owner can append above the lowered limit");
        check(rig.host.stopped.isEmpty(), "an existing owner does not trigger idle retirement");
        rejects("SCREEN_LIMIT_REACHED", () -> rig.request(4, "new", "com.w"));
        check(rig.host.stopped.equals(List.of(idle)), "a rejected acquisition still closes the selected idle screen");
        check(rig.host.slots.containsKey(first.getString("screenKey"))
                && rig.host.slots.containsKey(second.getString("screenKey")), "occupied screens survive lowering");
    }

    static void protectedEmptyAndQueue() throws Exception {
        limit(2);
        Rig rig = new Rig();
        JSONObject empty = rig.request(1, "empty", "com.x");
        release(rig, 1, "com.x");
        rig.request(2, "busy", "com.y");
        limit(1);
        rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "new", "com.z"));
        rig.clock.advance(DedicatedScreens.HOLD_MS);
        rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "new", "com.z"));
        check(rig.host.stopped.isEmpty(), "a session's empty screen is not an idle screen, even after 30 seconds");
        check(rig.request(1, "continue", "com.x").getString("screenKey")
                .equals(empty.getString("screenKey")), "the existing empty owner can continue above the limit");
        end(rig.screens, 2);
        rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "new", "com.z"));
        check(rig.host.stopped.size() == 1, "only the fully returned screen is retired");

        limit(3);
        Rig queued = new Rig();
        queued.request(1, "holder", "com.x");
        Call waiting = new Call(queued, 2, "waiting", "com.x");
        awaitWaiting(queued, "com.x", 1);
        String idle = queued.request(3, "idle", "com.y").getString("screenKey");
        end(queued.screens, 3);
        limit(1);
        rejects("SCREEN_LIMIT_REACHED", () -> queued.request(4, "new", "com.z"));
        check(queued.host.stopped.equals(List.of(idle)) && !waiting.done(),
                "App queues retain their occupied screens during idle retirement");
        end(queued.screens, 1);
        check(waiting.granted().equals(List.of("com.x")), "the original queue still receives its App");
    }

    static void pendingOpeningCounts() throws Exception {
        limit(3);
        Rig rig = new Rig();
        rig.request(1, "old", "com.x");
        rig.request(2, "old", "com.y");
        rig.host.startGate = new CountDownLatch(1);
        Call opening = new Call(rig, 3, "starting", "com.z");
        try {
            awaitWaiting(rig, "com.z", 1);
            end(rig.screens, 1);
            end(rig.screens, 2);
            limit(1);
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(4, "new", "com.w"));
            check(rig.host.stopped.size() == 2, "a pending opening counts while both excess idle screens retire");
        } finally {
            rig.host.startGate.countDown();
        }
        check(opening.granted().equals(List.of("com.z")), "the reserved opening can complete after lowering");
        rejects("SCREEN_LIMIT_REACHED", () -> rig.request(4, "new", "com.w"));
    }

    static void synchronousCloseOutsideLock() throws Exception {
        limit(3);
        Rig rig = new Rig();
        for (int n = 1; n <= 3; n++) rig.request(n, "old", "com.app" + n);
        for (int n = 1; n <= 3; n++) end(rig.screens, n);
        limit(1);
        CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        rig.host.beforeStop = key -> {
            check(!rig.screens.lockHeld(), "idle close is outside the ledger lock");
            entered.countDown();
            try {
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("close gate timed out");
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        };
        Call first = new Call(rig, 4, "new", "com.x");
        try {
            check(entered.await(2, TimeUnit.SECONDS), "new acquisition reaches physical close");
            check(!first.done(), "the acquiring request waits for physical close");
            Call second = new Call(rig, 5, "concurrent", "com.y");
            check(second.refused().contains("SCREEN_LIMIT_REACHED"),
                    "another request can check capacity while close is blocked");
            check(!first.done(), "concurrent refusal does not finish the blocked close");
        } finally {
            go.countDown();
        }
        check(first.granted().equals(List.of("com.x")), "acquisition returns after synchronous close");
        check(rig.host.stopped.size() == 2 && new HashSet<>(rig.host.stopped).size() == 2,
                "concurrent requests never select the same retired screen twice");
    }

    static void failedCloseContinuesBatch() throws Exception {
        limit(3);
        Rig rig = new Rig();
        List<String> warnings = new CopyOnWriteArrayList<>();
        DedicatedScreens screens = new DedicatedScreens(rig.host, rig.tasks, rig.clock,
                rig.codex::call, () -> "en", warnings::add);
        Set<String> old = new HashSet<>();
        for (int n = 1; n <= 3; n++) old.add(request(screens, n, "com.app" + n).getString("screenKey"));
        for (int n = 1; n <= 3; n++) end(screens, n);
        limit(1);
        AtomicInteger attempts = new AtomicInteger();
        List<String> selected = new CopyOnWriteArrayList<>();
        rig.host.beforeStop = key -> {
            selected.add(key);
            rig.host.stopFailure = attempts.incrementAndGet() == 1 ? "injected close failure" : "";
        };
        JSONObject grant = request(screens, 4, "com.x");
        check(attempts.get() == 2 && rig.host.stopped.size() == 1, "one close failure does not skip the remaining selected screen");
        check(warnings.size() == 1 && warnings.get(0).contains(selected.get(0))
                && warnings.get(0).contains("injected close failure"), "unconfirmed close logs its physical identity and error");
        check(old.contains(grant.getString("screenKey")) && !selected.contains(grant.getString("screenKey")),
                "failed or stopped retired screens are never handed out");
        end(screens, 4);
        check(request(screens, 5, "com.y").getString("screenKey").equals(grant.getString("screenKey")),
                "later acquisitions can reuse only the retained screen");
    }

    static void controlAndCleanupAreNotIdle() throws Exception {
        for (String owner : List.of("user", "paused")) {
            limit(2);
            Rig rig = new Rig();
            String kept = rig.request(1, "controlled", "com.x").getString("screenKey");
            rig.request(2, "idle", "com.y");
            end(rig.screens, 2);
            release(rig, 1, "com.x");
            control(rig, 1, owner);
            rig.clock.advance(DedicatedScreens.HOLD_MS);
            limit(1);
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "new", "com.z"));
            check(rig.host.slots.containsKey(kept) && !rig.host.stopped.contains(kept),
                    owner + " keeps its empty screen while excess idle screens retire");
        }

        limit(2);
        Rig rig = new Rig();
        JSONObject cleaning = rig.request(1, "cleaning", "com.x");
        rig.tasks.open("com.x", cleaning.getInt("displayId"));
        rig.request(2, "idle", "com.y");
        end(rig.screens, 2);
        rig.tasks.removing = new CountDownLatch(1);
        rig.tasks.removeGate = new CountDownLatch(1);
        Thread worker = rig.screens.turnEnded(session(1));
        try {
            check(rig.tasks.removing.await(2, TimeUnit.SECONDS), "turn cleanup is in flight");
            limit(1);
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "new", "com.z"));
            check(!rig.host.stopped.contains(cleaning.getString("screenKey")),
                    "an in-flight release cannot be selected as excess idle capacity");
        } finally {
            rig.tasks.removeGate.countDown();
            worker.join(5_000);
        }
        check(!worker.isAlive(), "in-flight cleanup finishes normally");
    }

    static void interruptedCloseContinuesBatch() throws Exception {
        for (boolean interruptedBefore : new boolean[] {true, false}) {
            limit(3);
            Rig rig = new Rig();
            CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();
            List<String> warnings = new CopyOnWriteArrayList<>();
            DedicatedScreens screens = new DedicatedScreens(new PhoneExecutionScreens.Host() {
                public JSONObject start(String key) throws Exception { return rig.host.start(key); }
                public JSONObject status(String key) throws Exception { return rig.host.status(key); }
                public void stop(String key, String generation) throws Exception {
                    check(!Thread.currentThread().isInterrupted(), "batch close defers caller cancellation");
                    if (attempts.incrementAndGet() == 1) {
                        entered.countDown();
                        if (!interruptedBefore) go.await();
                    }
                    rig.host.stop(key, generation);
                }
            }, rig.tasks, rig.clock, rig.codex::call, () -> "en", warnings::add);
            for (int n = 1; n <= 3; n++) request(screens, n, "com.app" + n);
            for (int n = 1; n <= 3; n++) end(screens, n);
            limit(1);
            CompletableFuture<String> outcome = new CompletableFuture<>();
            Thread caller = new Thread(() -> {
                if (interruptedBefore) Thread.currentThread().interrupt();
                try {
                    request(screens, 4, "com.x");
                    outcome.complete("unexpected grant");
                } catch (Exception error) {
                    outcome.complete(error.getClass().getSimpleName());
                }
            });
            caller.setDaemon(true);
            caller.start();
            try {
                check(entered.await(2, TimeUnit.SECONDS), "caller reaches the synchronous close batch");
                if (!interruptedBefore) caller.interrupt();
                check(outcome.get(3, TimeUnit.SECONDS).equals("InterruptedException"),
                        "cancellation reaches await after the batch, not a successful grant");
                check(attempts.get() == 2, "interruption never skips another selected screen");
                check(warnings.size() == (interruptedBefore ? 0 : 1),
                        "an interrupted physical stop is recorded as unconfirmed");
                check(screens.status(session(0)).getJSONArray("apps").isEmpty(),
                        "the cancelled grant leaves no App held or queued");
            } finally {
                go.countDown();
                caller.join(5_000);
            }
            end(screens, 4);
        }
    }

    public static void main(String[] args) throws Exception {
        int before = checks;
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(
                Files.createTempDirectory("dedicated-capacity").resolve("policy.json").toFile());
        try {
            loweredIdleLimit();
            occupiedAndRejectedCleanup();
            protectedEmptyAndQueue();
            pendingOpeningCounts();
            synchronousCloseOutsideLock();
            failedCloseContinuesBatch();
            controlAndCleanupAreNotIdle();
            interruptedCloseContinuesBatch();
            System.out.println("DedicatedScreensCapacityTest: " + (checks - before) + " checks passed");
        } finally {
            WorkspacePolicy.store = previous;
        }
    }
}
