package org.agentos.lab;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

import static org.agentos.lab.DedicatedScreensTest.*;

/** Spec 3/5: real grants and delayed await, controlled cleanup failures, existing host fixtures. */
public final class DedicatedScreensLifecycleTest {
    static void resetPolicy() throws Exception {
        WorkspacePolicy.store = new WorkspacePolicy.Store(
                Files.createTempDirectory("dedicated-lifecycle").resolve("policy.json").toFile());
    }
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    // Construct only the delay between grantReady and await. Both execute production code;
    // this is not a reproduction of Android/tool-thread scheduling.
    static Object deferredGrant(Rig rig, String pkg) throws Exception {
        Class<?> type = Class.forName("org.agentos.lab.DedicatedScreens$Ticket");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, Map.class, String.class);
        constructor.setAccessible(true);
        Object ticket = constructor.newInstance(session(1), apps(pkg), "delayed request");
        Field screen = type.getDeclaredField("screen");
        screen.setAccessible(true);
        JSONObject row = rig.screens.list(session(1)).getJSONObject(0);
        screen.set(ticket, rig.screens.exact(session(1), row.getString("screenKey"), row.getString("generation")));
        Method grant = DedicatedScreens.class.getDeclaredMethod("grantReady");
        grant.setAccessible(true);
        try (CleanupWork.Held ledgerHeld = rig.screens.lock()) {
            @SuppressWarnings("unchecked")
            List<Object> waiting = (List<Object>) field(rig.screens, "waiting");
            waiting.add(ticket);
            grant.invoke(rig.screens);
        }
        check((boolean) field(ticket, "granted"), "deferred ticket was granted by grantReady");
        return ticket;
    }

    static void cancel(Rig rig, Object ticket) throws Exception {
        Method await = DedicatedScreens.class.getDeclaredMethod("await", ticket.getClass());
        await.setAccessible(true);
        Thread.currentThread().interrupt();
        try {
            await.invoke(rig.screens, ticket);
            throw new AssertionError("interrupted await returned success");
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof InterruptedException)) throw error;
        } finally {
            Thread.interrupted();
        }
        check((boolean) field(ticket, "withdrawn"), "late ticket is withdrawn even when its occupancy expired");
        check(((List<?>) field(rig.screens, "waiting")).isEmpty(), "withdraw leaves no ticket in the waiting room");
    }

    static void spec3() throws Exception {
        for (int next : new int[] {1, 2}) {
            Rig rig = new Rig();
            JSONObject first = rig.request(1, "old turn", "com.base");
            Object ticket = deferredGrant(rig, "com.clock");
            Object screen = field(ticket, "screen");
            rig.screens.turnEnded(session(1)).join(5_000);
            JSONObject second = rig.request(next, "next turn", "com.clock");
            check(first.getString("screenKey").equals(second.getString("screenKey")), "next turn reuses the physical screen");
            cancel(rig, ticket);
            check(holder(rig, "com.clock").equals(next == 1 ? "001" : "002"),
                    "Spec 3: late await must preserve the new occupancy, including the same session");
            check(field(ticket, "occupancy") != field(screen, "occupancy"), "idle reuse renews occupancy");
        }

        Rig current = new Rig();
        current.request(1, "current turn", "com.base");
        Object append = deferredGrant(current, "com.clock");
        Object screen = field(append, "screen");
        Object occupancy = field(screen, "occupancy");
        check(occupancy != null && occupancy == field(append, "occupancy"), "new screen and grant share one occupancy");
        cancel(current, append);
        check(holder(current, "com.base").equals("001") && app(current, "com.clock") == null,
                "current-occupancy cancellation removes only this grant");
        current.screens.release(session(1), List.of("com.base"));
        check(field(screen, "occupancy") == occupancy, "partial/last App release preserves occupancy");
        WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 1));
        try {
            current.clock.advance(DedicatedScreens.HOLD_MS);
            current.request(2, "reclaimed", "com.clock");
            check(field(screen, "occupancy") != occupancy, "30-second reclaim renews occupancy");
            cancel(current, append);
            check(holder(current, "com.clock").equals("002"), "expired grant cannot undo a reclaimed screen's grant");
        } finally {
            resetPolicy();
        }

        for (String ending : List.of("turn", "archive", "close", "lost", "codex")) {
            Rig rig = new Rig();
            JSONObject got = rig.request(1, "lifecycle", "com.base");
            Object ticket = deferredGrant(rig, "com.clock");
            PhoneExecutionScreens.Lease lease = (PhoneExecutionScreens.Lease) field(ticket, "screen");
            if (ending.equals("turn") || ending.equals("archive")) {
                lease.inputLock().lock();
                Thread release;
                try {
                    release = ending.equals("archive") ? rig.screens.sessionGone(session(1))
                            : rig.screens.turnEnded(session(1));
                    awaitParked(release);
                    check(field(lease, "occupancy") == null, "leave invalidates occupancy before cleanup");
                    cancel(rig, ticket);
                    check(holder(rig, "com.clock").equals("001"), "old ticket cannot release Apps still awaiting cleanup");
                    check(rig.screens.turnEnded(session(1)) == null, "another turn end skips the release already in flight");
                } finally {
                    lease.inputLock().unlock();
                }
                release.join(5_000);
            } else if (ending.equals("close")) {
                rig.screens.close(session(1), got.getString("screenKey"), got.getString("generation"), null);
            } else if (ending.equals("lost")) {
                rig.host.unplug(got.getString("screenKey"));
                rejects("SCREEN_LOST", () -> rig.screens.withScreen(session(1), DedicatedScreens.Use.LOOK,
                        null, s -> rig.host.status(s.screenKey)));
            } else {
                rig.screens.codexGone().join(5_000);
            }
            check(field(lease, "occupancy") == null, ending + " invalidates occupancy");
        }

        Rig paused = new Rig();
        paused.request(1, "borrowed", "com.clock");
        String[] keys = lease(paused, 1);
        Object borrowed = paused.screens.exact(session(1), keys[0], keys[1]);
        Object original = field(borrowed, "occupancy");
        for (String owner : List.of("paused", "user", "agent")) {
            paused.screens.beginPause(session(1), keys[0], keys[1]);
            JSONObject pausing = paused.screens.list().getJSONObject(0);
            check(pausing.getString("status").equals("stopping") && pausing.getBoolean("homePreviewEligible"),
                    "Home keeps the screen during pause and takeover");
            JSONObject controlled = paused.screens.finishControl(session(1), keys[0], keys[1], owner);
            check(controlled.getBoolean("homePreviewEligible"), owner + " keeps the screen on Home");
            check(field(borrowed, "occupancy") == original, owner + " retains occupancy");
        }

        Object unmarked = deferredGrant(paused, "com.base");
        Field mark = unmarked.getClass().getDeclaredField("occupancy");
        mark.setAccessible(true);
        mark.set(unmarked, null);
        cancel(paused, unmarked);
        check(holder(paused, "com.base").equals("001"), "an unmarked granted ticket cannot remove a live grant");

        Rig closing = new Rig();
        JSONObject closeGrant = closing.request(1, "close", "com.base");
        Object closeTicket = deferredGrant(closing, "com.clock");
        Object closeScreen = field(closeTicket, "screen");
        Object closeOccupancy = field(closeScreen, "occupancy");
        closing.screens.beginClose(session(1), closeGrant.getString("screenKey"), closeGrant.getString("generation"));
        JSONObject closingRow = closing.screens.list().getJSONObject(0);
        check(closingRow.getString("status").equals("stopping") && !closingRow.getBoolean("homePreviewEligible"),
                "Home hides the screen when close begins, before physical cleanup");
        check(field(closeScreen, "occupancy") == null, "beginClose invalidates occupancy before stopping the old tool thread");
        closing.host.stopFailure = "unconfirmed close";
        CountDownLatch stopping = new CountDownLatch(1), proceed = new CountDownLatch(1);
        closing.host.beforeStop = key -> {
            stopping.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("close gate timed out"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        CompletableFuture<String> outcome = new CompletableFuture<>();
        Thread close = daemon(() -> {
            try { closing.screens.close(session(1), closeGrant.getString("screenKey"), closeGrant.getString("generation"), null, true); }
            catch (Exception error) { outcome.complete(error.getMessage()); }
        });
        check(stopping.await(2, TimeUnit.SECONDS), "explicit close waits for physical confirmation");
        check(field(closeScreen, "occupancy") == null, "explicit close invalidates occupancy while cleanup is in flight");
        cancel(closing, closeTicket);
        check(holder(closing, "com.clock").equals("001"), "late ticket cannot release Apps during explicit close");
        proceed.countDown(); join(close);
        closing.screens.controlFailed(session(1), closeGrant.getString("screenKey"), closeGrant.getString("generation"), "unconfirmed close");
        check(outcome.get(2, TimeUnit.SECONDS).equals("unconfirmed close")
                        && field(closeScreen, "occupancy") == closeOccupancy,
                "failed confirmed close restores the retained allocation's occupancy");
        check(closing.screens.list().getJSONObject(0).getBoolean("homePreviewEligible"),
                "the retained screen returns to Home after a failed close");
    }

    static final class Cleanup {
        final Rig parts = new Rig();
        final AtomicInteger lists = new AtomicInteger(), removes = new AtomicInteger();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        volatile int listFailures, removeFailureAt;
        volatile boolean failLists, failRemoves;
        volatile java.util.function.IntConsumer beforeList = attempt -> {};
        final DedicatedScreens screens = new DedicatedScreens(parts.host, new PhoneTasks() {
            public List<Task> list() throws Exception {
                int attempt = lists.incrementAndGet();
                beforeList.accept(attempt);
                if (failLists || attempt <= listFailures) throw new IOException("injected task query failure");
                return parts.tasks.list();
            }
            public void remove(int id) throws Exception {
                int attempt = removes.incrementAndGet();
                if (failRemoves || attempt == removeFailureAt) throw new IOException("injected task removal failure");
                parts.tasks.remove(id);
            }
        }, parts.clock, parts.codex::call, () -> "en", warnings::add);

        JSONObject request(int n, String pkg) throws Exception {
            return screens.request(session(n), apps(pkg), "cleanup test");
        }
        String holder(String pkg) throws Exception {
            for (Object row : screens.status(session(0)).getJSONArray("apps"))
                if (((JSONObject) row).getString("packageName").equals(pkg)) return ((JSONObject) row).optString("heldBy");
            return "";
        }
    }

    static void join(Thread thread) throws Exception {
        thread.join(5_000);
        check(!thread.isAlive(), "cleanup worker completed");
    }

    static synchronized boolean scheduled(Clock clock, long at) {
        synchronized (clock) {
            return clock.due.stream().anyMatch(due -> due.at() == at);
        }
    }

    static void spec5() throws Exception {
        for (String failure : List.of("query", "partial removal")) {
            Cleanup rig = new Cleanup();
            JSONObject got = rig.request(1, "com.clock");
            int display = got.getInt("displayId");
            rig.parts.tasks.open("com.clock", display);
            rig.parts.tasks.open("com.jump", display);
            if (failure.equals("query")) rig.listFailures = 1;
            else rig.removeFailureAt = 2;
            CountDownLatch retry = new CountDownLatch(1), proceed = new CountDownLatch(1);
            rig.beforeList = attempt -> {
                if (attempt != 2) return;
                retry.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("retry gate timed out"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            };
            Thread release = rig.screens.turnEnded(session(1));
            check(retry.await(2, TimeUnit.SECONDS), "Spec 5: first " + failure + " failure must retry task cleanup");
            check(rig.holder("com.clock").equals(session(1)), "retry keeps App reserved");
            check(rig.screens.turnEnded(session(1)) == null, "duplicate turn end cannot start another cleanup");
            proceed.countDown();
            join(release);
            check(rig.parts.tasks.list().isEmpty(), "retry clears every remaining task");
            check(rig.request(2, "com.clock").getString("screenKey").equals(got.getString("screenKey")), "successful retry reuses clean screen");
            check(rig.parts.host.stopped.isEmpty(), "successful cleanup retry keeps cached screen");
        }

        Cleanup closed = new Cleanup();
        JSONObject got = closed.request(1, "com.clock");
        closed.parts.tasks.open("com.clock", got.getInt("displayId"));
        closed.failRemoves = true;
        CountDownLatch stopping = new CountDownLatch(1), confirm = new CountDownLatch(1);
        closed.parts.host.beforeStop = key -> {
            check(!closed.screens.lockHeld(), "physical close runs outside ledger lock");
            stopping.countDown();
            try { if (!confirm.await(5, TimeUnit.SECONDS)) throw new AssertionError("close gate timed out"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 1));
        try {
            Thread release = closed.screens.turnEnded(session(1));
            check(stopping.await(2, TimeUnit.SECONDS), "Spec 5: two task failures must fall back to host.stop");
            check(closed.holder("com.clock").equals(session(1)), "App stays reserved until physical close confirms");
            rejects("SCREEN_LIMIT_REACHED", () -> closed.request(2, "com.other"));
            confirm.countDown();
            join(release);
            check(closed.parts.host.stopped.equals(List.of(got.getString("screenKey"))) && closed.holder("com.clock").isEmpty(), "confirmed close frees App and capacity");
            check(!closed.request(2, "com.clock").getString("screenKey").equals(got.getString("screenKey")), "fallback creates a new screen instead of reusing old page");
        } finally { resetPolicy(); }

        Cleanup failed = new Cleanup();
        JSONObject bad = failed.request(1, "com.clock");
        failed.parts.tasks.open("com.clock", bad.getInt("displayId"));
        failed.failLists = true;
        failed.parts.host.stopFailure = "injected unconfirmed host stop";
        join(failed.screens.turnEnded(session(1)));
        check(failed.holder("com.clock").equals(session(1)), "unconfirmed close retains App");
        JSONObject row = failed.screens.list(session(1)).getJSONObject(0);
        check(row.getString("status").equals("cleanup_failed") && !row.getBoolean("canResume")
                        && row.getString("lastError").contains("injected unconfirmed host stop"), "failure remains discoverable without Android task query");
        check(failed.screens.describe(session(1)).getString("status").equals("cleanup_failed"), "session state exposes same failure");
        check(failed.screens.status(session(0)).getInt("idleScreens") == 0, "faulted screen is never reported idle");
        check(!failed.warnings.isEmpty(), "cleanup failure is logged");
        check(field(((List<?>) field(failed.screens, "ledger")).get(0), "occupancy") == null,
                "failed cleanup keeps resources reserved with the ended occupancy invalidated");
        failed.screens.sessionGone(session(1)).join(5_000);
        failed.screens.codexGone().join(5_000);
        check(failed.parts.host.stopped.isEmpty(), "archive/Codex disconnect do not bypass failed cleanup");
        failed.request(1, "com.other");
        JSONArray mixed = failed.screens.list(session(1));
        check(mixed.length() == 2 && mixed.getJSONObject(0).getString("status").equals("running")
                        && mixed.getJSONObject(1).getString("status").equals("cleanup_failed"),
                "new same-session occupancy and old fault stay separate even with task query unavailable");
        check(failed.screens.status(session(0)).getJSONArray("screens").length() == 2,
                "screen_status also preserves mixed failure visibility");
        CompletableFuture<String> waiting = new CompletableFuture<>();
        daemon(() -> {
            try { failed.screens.request(session(1), apps("com.clock"), "new queued request"); }
            catch (Exception error) { waiting.complete(error.getMessage()); }
        });
        long queued = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (failed.screens.status(session(0)).getJSONArray("apps").getJSONObject(0).getInt("waiting") == 0
                && System.nanoTime() < queued) Thread.yield();
        check(failed.screens.status(session(0)).getJSONArray("screens").getJSONObject(0)
                        .getString("taskTitle").equals("cleanup test"),
                "the old fault never borrows its session's new queued task title");
        failed.failLists = false;
        join(failed.screens.turnEnded(session(1)));
        check(waiting.get(2, TimeUnit.SECONDS).contains("TURN_ENDED"), "new queued request is withdrawn independently of the old fault");
        failed.failLists = true;
        failed.parts.clock.advance(29_999);
        check(failed.parts.host.stopped.isEmpty(), "retry waits 30 seconds");
        failed.parts.clock.advance(1);
        long reschedule = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!scheduled(failed.parts.clock, 60_000) && System.nanoTime() < reschedule) Thread.yield();
        check(scheduled(failed.parts.clock, 60_000), "a repeated failed stop schedules another bounded-delay retry");
        failed.parts.host.stopFailure = "";
        CountDownLatch recovered = new CountDownLatch(1);
        failed.parts.host.beforeStop = key -> recovered.countDown();
        failed.parts.clock.advance(30_000);
        check(recovered.await(2, TimeUnit.SECONDS), "scheduled recovery retries physical close");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!failed.screens.list(session(1)).isEmpty() && System.nanoTime() < deadline) Thread.yield();
        check(failed.screens.list(session(1)).isEmpty() && failed.holder("com.clock").isEmpty(), "retry confirmation removes fault and releases resources");
    }

    static void nativeStart(Rig rig, String turn) throws Exception {
        check(rig.screens.turnStarted(session(1), null), "ordinary native turn is admitted");
        rig.screens.nativeTurnStarted(session(1), turn);
    }

    static void openApp(Rig rig, String turn, String pkg) throws Exception {
        rig.screens.withScreen(session(1), turn, DedicatedScreens.Use.OPEN, pkg, screen -> {
            rig.tasks.open(pkg, screen.displayId);
            return new JSONObject();
        });
    }

    static void returnTurn(Rig rig, String turn, boolean subAgent) throws Exception {
        String[] key = lease(rig, 1);
        rig.screens.beginReturn(session(1), key[0], key[1]);
        rig.screens.finishControl(session(1), key[0], key[1], "agent");
        if (subAgent) rig.screens.resumeGranted(session(1), key[0], key[1]);
        check(rig.screens.turnStarted(session(1), subAgent ? null : rig.screens.admission(session(1))),
                "the handback's native admission is accepted");
        rig.screens.nativeTurnStarted(session(1), turn);
    }

    static void ready(Rig rig, JSONObject allocation) throws Exception {
        JSONObject row = rig.screens.list(session(1)).getJSONObject(0);
        check(row.getString("screenKey").equals(allocation.getString("screenKey"))
                        && row.getString("generation").equals(allocation.getString("generation"))
                        && row.getInt("displayId") == allocation.getInt("displayId")
                        && row.getBoolean("admitted") && row.getBoolean("homePreviewEligible")
                        && row.getString("status").equals("ready") && !row.getBoolean("stopping"),
                "completed workspace stays on Home with its exact live binding and ready status");
        check(!rig.tasks.list().isEmpty() && holder(rig, "com.game").equals("001"),
                "retention keeps the Android task and its App reservation");
    }

    static void completionRetention() throws Exception {
        Rig opened = new Rig();
        nativeStart(opened, "open-only");
        JSONObject allocation = opened.request(1, "reopen game", "com.game");
        openApp(opened, "open-only", "com.game");
        opened.screens.withScreen(session(1), DedicatedScreens.Use.LOOK, null, screen -> new JSONObject());
        check(opened.screens.turnEnded(session(1), "open-only", true) == null,
                "open plus observe retains the workspace on successful completion");
        ready(opened, allocation);
        check(opened.screens.turnEnded(session(1), "open-only", true) == null,
                "duplicate native completion does not start cleanup");
        ready(opened, allocation);
        nativeStart(opened, "ordinary-next");
        check(opened.screens.list(session(1)).getJSONObject(0).getString("status").equals("running"),
                "the next native turn leaves ready status");
        opened.screens.withScreen(session(1), DedicatedScreens.Use.LOOK, null, screen -> new JSONObject());
        join(opened.screens.turnEnded(session(1), "ordinary-next", true));
        check(opened.screens.list().isEmpty() && opened.tasks.list().isEmpty(),
                "open-only retention does not leak to the next ordinary turn");

        for (String ending : List.of("mutation", "failed-mutation", "release", "failure")) {
            Rig rig = new Rig();
            nativeStart(rig, ending);
            rig.request(1, "open then " + ending, "com.game", "com.other");
            openApp(rig, ending, "com.game");
            if (ending.equals("mutation") || ending.equals("failed-mutation")) {
                try {
                    rig.screens.withScreen(session(1), DedicatedScreens.Use.ACT, null, screen -> {
                        if (ending.equals("failed-mutation")) throw new IOException("uncertain input");
                        return new JSONObject();
                    });
                } catch (IOException error) {
                    check(error.getMessage().equals("uncertain input"), "failed mutation reaches its caller");
                }
            } else if (ending.equals("release")) {
                rig.screens.release(session(1), List.of("com.other"));
            }
            join(rig.screens.turnEnded(session(1), ending, !ending.equals("failure")));
            check(rig.screens.list().isEmpty() && rig.tasks.list().isEmpty(),
                    ending + " preserves ordinary turn cleanup");
        }

        Rig misdirected = new Rig();
        nativeStart(misdirected, "misdirected");
        misdirected.request(1, "open fails", "com.game");
        rejects("APP_NOT_OPENED_ON_SCREEN", () -> misdirected.screens.withScreen(session(1), "misdirected",
                DedicatedScreens.Use.OPEN, "com.game", screen -> {
                    misdirected.tasks.open("com.game", 99);
                    return new JSONObject();
                }));
        join(misdirected.screens.turnEnded(session(1), "misdirected", true));
        check(misdirected.screens.list().isEmpty() && misdirected.tasks.list().get(0).displayId() == 99,
                "an App on another display neither arms retention nor gets removed");

        Rig stale = new Rig();
        nativeStart(stale, "old");
        stale.request(1, "old task", "com.game");
        openApp(stale, "old", "com.game");
        nativeStart(stale, "new");
        check(stale.screens.turnEnded(session(1), "old", true) == null,
                "old completion cannot release the new turn's allocation");
        stale.screens.withScreen(session(1), "old", DedicatedScreens.Use.OPEN, "com.game", screen -> {
            return new JSONObject();
        });
        join(stale.screens.turnEnded(session(1), "new", true));
        check(stale.screens.list().isEmpty(), "a late open result cannot arm the new native turn");

        Rig policy = new Rig();
        nativeStart(policy, "policy-turn");
        policy.request(1, "open before revocation", "com.game", "com.other");
        openApp(policy, "policy-turn", "com.game");
        try {
            List<DedicatedScreens.RevokedScreen> revoked = policy.screens.configurePolicy(
                    new JSONObject().put("blockedPackages", new JSONArray().put("com.other")));
            policy.screens.releaseRevoked(revoked.get(0), screen -> new JSONObject());
            join(policy.screens.turnEnded(session(1), "policy-turn", true));
            check(policy.screens.list().isEmpty() && policy.tasks.list().isEmpty(),
                    "policy revocation disarms open-only retention before successful completion");
        } finally {
            resetPolicy();
        }

        Rig reused = new Rig();
        nativeStart(reused, "retained");
        JSONObject original = reused.request(1, "first allocation", "com.game");
        openApp(reused, "retained", "com.game");
        reused.screens.turnEnded(session(1), "retained", true);
        JSONObject oldRow = reused.screens.list(session(1)).getJSONObject(0);
        reused.screens.release(session(1), List.of("com.game"));
        check(reused.screens.list(session(1)).getJSONObject(0).getString("status").equals("ready"),
                "release after completion does not claim the native turn is still running");
        nativeStart(reused, "cleanup-empty");
        join(reused.screens.turnEnded(session(1), "cleanup-empty", true));
        nativeStart(reused, "reallocated");
        JSONObject next = reused.request(1, "new allocation", "com.game");
        JSONObject nextRow = reused.screens.list(session(1)).getJSONObject(0);
        check(next.getString("screenKey").equals(original.getString("screenKey"))
                        && nextRow.getString("generation").equals(oldRow.getString("generation")),
                "fixture reuses the physical display for a new allocation");
        check(!nextRow.getString("allocationId").equals(oldRow.getString("allocationId")),
                "a viewer can distinguish the replacement from the released allocation");
        reused.tasks.open("com.game", next.getInt("displayId"));
        check(reused.screens.turnEnded(session(1), "retained", true) == null,
                "old completion cannot affect a reused display");
        join(reused.screens.turnEnded(session(1), "reallocated", true));
        check(reused.screens.list().isEmpty() && reused.tasks.list().isEmpty(),
                "retention disposition never carries into a replacement allocation");

        Rig stopping = new Rig();
        nativeStart(stopping, "original");
        JSONObject beforeReturn = stopping.request(1, "return while stopping", "com.game");
        stopping.tasks.open("com.game", beforeReturn.getInt("displayId"));
        control(stopping, 1, "user");
        String[] returning = lease(stopping, 1);
        stopping.screens.beginReturn(session(1), returning[0], returning[1]);
        check(stopping.screens.turnEnded(session(1), "original", false) == null,
                "the interrupted original turn cannot consume a pending handback");
        stopping.screens.finishControl(session(1), returning[0], returning[1], "agent");
        check(stopping.screens.turnStarted(session(1), stopping.screens.admission(session(1))),
                "handback remains admitted after the original turn stops");
        stopping.screens.nativeTurnStarted(session(1), "after-stop");
        check(stopping.screens.turnEnded(session(1), "after-stop", true) == null,
                "the successful handback retains the allocation after that interruption");
        ready(stopping, beforeReturn);

        for (boolean subAgent : new boolean[]{false, true}) {
            Rig rig = new Rig();
            allocation = rig.request(1, "completed original task", "com.game");
            rig.tasks.open("com.game", allocation.getInt("displayId"));
            control(rig, 1, "user");
            returnTurn(rig, "return-one", subAgent);
            control(rig, 1, "user");
            check(rig.screens.turnEnded(session(1), "return-one", false) == null,
                    "taking over an interrupted handback keeps the user's screen");
            returnTurn(rig, "return-two", subAgent);
            check(rig.screens.turnEnded(session(1), "return-one", true) == null,
                    "a late first handback completion cannot end the second");
            rig.screens.withScreen(session(1), DedicatedScreens.Use.LOOK, null, screen -> new JSONObject());
            rig.screens.withScreen(session(1), DedicatedScreens.Use.ACT, null, screen -> new JSONObject());
            check(rig.screens.turnEnded(session(1), "return-two", true) == null,
                    "a successful explicit handback retains its live allocation");
            ready(rig, allocation);
            control(rig, 1, "user");
            returnTurn(rig, "return-failed", subAgent);
            join(rig.screens.turnEnded(session(1), "return-failed", false));
            check(rig.screens.list().isEmpty() && rig.tasks.list().isEmpty(),
                    "failed handback still releases an agent-owned workspace");
        }

        Rig explicit = new Rig();
        nativeStart(explicit, "open-close");
        allocation = explicit.request(1, "open and close", "com.game");
        openApp(explicit, "open-close", "com.game");
        explicit.screens.turnEnded(session(1), "open-close", true);
        explicit.screens.beginClose(session(1), allocation.getString("screenKey"), allocation.getString("generation"));
        explicit.screens.close(session(1), allocation.getString("screenKey"), allocation.getString("generation"), null);
        check(explicit.screens.list().isEmpty() && explicit.tasks.list().isEmpty(),
                "explicit close removes a retained workspace and its task");
    }

    public static void main(String[] args) throws Exception {
        resetPolicy();
        if (args.length == 0 || args[0].equals("spec3")) spec3();
        if (args.length == 0 || args[0].equals("spec5")) spec5();
        if (args.length == 0 || args[0].equals("completion")) completionRetention();
        System.out.println("DedicatedScreensLifecycleTest: " + checks + " checks passed");
    }
}
