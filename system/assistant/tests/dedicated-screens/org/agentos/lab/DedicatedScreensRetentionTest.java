package org.agentos.lab;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import static org.agentos.lab.DedicatedScreensTest.*;

/** Production cache eviction with fake time, real admission locks and saved family metadata. */
public final class DedicatedScreensRetentionTest {
    private static int checks;
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static void verify(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> map(Rig rig, String name) throws Exception {
        Field field = DedicatedScreens.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<String, V>) field.get(rig.screens);
    }
    private static Object invoke(Rig rig, String name, String session) throws Exception {
        Method method = DedicatedScreens.class.getDeclaredMethod(name, String.class); method.setAccessible(true);
        try { return method.invoke(rig.screens, session); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    private static void warm(Rig rig, int number) throws Exception {
        invoke(rig, "family", session(number)); invoke(rig, "mainSession", session(number));
    }
    private static void count() throws Exception {
        Rig rig = new Rig();
        for (int n = 1; n <= 200; n++) warm(rig, n);
        verify(map(rig, "mainSessions").size() == 120, "200 cache sessions are reclaimed in one batch to 120");
        verify(map(rig, "sessionFamilies").size() == 120, "both maps lose the same oldest session range");
        verify(!map(rig, "mainSessions").containsKey(session(80)) && map(rig, "mainSessions").containsKey(session(81)), "oldest 80, not arbitrary entries, are evicted");
        for (int n = 201; n <= 279; n++) warm(rig, n);
        verify(map(rig, "mainSessions").size() == 199, "79 further writes use the reserved headroom without eviction");
        warm(rig, 280);
        verify(map(rig, "mainSessions").size() == 120, "the next high-water crossing triggers another full batch");
    }
    private static void ageAndBytes() throws Exception {
        Rig rig = new Rig(); warm(rig, 1); rig.clock.advance(2 * DAY); warm(rig, 2);
        rig.clock.advance(2 * DAY); warm(rig, 3); rig.clock.advance(3 * DAY);
        verify(!map(rig, "mainSessions").containsKey(session(1)) && !map(rig, "mainSessions").containsKey(session(2)), "the seven-day timer removes the full range back to five days");
        verify(map(rig, "mainSessions").containsKey(session(3)), "younger entries survive the age batch");
        Rig bytes = new Rig();
        try (CleanupWork.Held held = bytes.screens.lock()) {
            // Large synthetic cached strings exercise the byte budget independently of the count limit.
            DedicatedScreensRetentionTest.<String>map(bytes, "mainSessions").put(session(1), "a".repeat(600_000));
            invoke(bytes, "cached", session(1));
            DedicatedScreensRetentionTest.<String>map(bytes, "mainSessions").put(session(2), "b".repeat(500_000));
            invoke(bytes, "cached", session(2));
        }
        verify(map(bytes, "mainSessions").size() == 1 && map(bytes, "mainSessions").containsKey(session(2)), "byte pressure evicts enough old data to reach the low byte budget");
    }
    private static void protectedFamilies() throws Exception {
        Rig rig = new Rig(); rig.codex.parents.put(session(2), session(1));
        rig.request(1, "root", "com.clock"); rig.request(2, "child", "com.files"); warm(rig, 1); warm(rig, 2);
        rig.codex.parents.put(session(4), session(3)); warm(rig, 4);
        rig.screens.removalFence(value -> value.equals(session(3)));
        for (int n = 100; n < 300; n++) warm(rig, n);
        rig.clock.advance(8 * DAY); rig.codex.parents.clear();
        verify(rig.screens.removalFamily(session(1)).contains(session(2)), "active family ancestry survives after native parent metadata is gone");
        verify(rig.screens.removalFamily(session(3)).contains(session(4)), "a pending ancestor removal protects descendant evidence");
        verify(map(rig, "mainSessions").containsKey(session(1)), "the occupied root keeps its cached main session");
        verify(!map(rig, "mainSessions").containsKey(session(299)), "inactive entries still expire alongside protected entries");
        rig.screens.forgetRemoved(Set.of(session(1), session(2)));
        verify(!map(rig, "cacheTimes").containsKey(session(2)), "explicit family cleanup also removes retention metadata");
    }
    private static void inFlightAndLocking() throws Exception {
        Rig rig = new Rig(); rig.codex.parents.put(session(2), session(1));
        rig.host.startGate = new CountDownLatch(1);
        Call request = new Call(rig, 2, "opening", "com.clock");
        try {
            DedicatedScreensFamilyTest.until(() -> {
                try (CleanupWork.Held held = rig.screens.lock()) { return map(rig, "sessionFamilies").containsKey(session(2)); }
            }, "family is saved while screen opening waits");
            rig.clock.advance(8 * DAY);
            verify(rig.screens.removalFamily(session(1)).contains(session(2)), "an opening request pins family evidence before any ledger screen exists");
        } finally { rig.host.startGate.countDown(); }
        request.result.get(2, TimeUnit.SECONDS);

        Rig serialized = new Rig(); warm(serialized, 1);
        synchronized (serialized.clock) { serialized.clock.now = 8 * DAY; }
        Method sweep = DedicatedScreens.class.getDeclaredMethod("sweepCaches"); sweep.setAccessible(true);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try (CleanupWork.Held held = serialized.screens.lock()) {
            Thread worker = new Thread(() -> {
                try { sweep.invoke(serialized.screens); finished.complete(null); }
                catch (Exception error) { finished.completeExceptionally(error); }
            }, "retention-lock-test");
            worker.setDaemon(true); worker.start();
            Thread.sleep(20);
            verify(!finished.isDone(), "background eviction waits for the same ledger lock as writes");
            DedicatedScreensRetentionTest.<String>map(serialized, "mainSessions").put(session(2), session(2));
            invoke(serialized, "cached", session(2));
        }
        finished.get(2, TimeUnit.SECONDS);
        verify(map(serialized, "mainSessions").containsKey(session(2)), "a concurrent fresh write survives the older eviction scan");
    }
    public static void main(String[] args) throws Exception {
        count();
        if (args.length == 0) { ageAndBytes(); protectedFamilies(); inFlightAndLocking(); }
        System.out.println("DedicatedScreensRetentionTest: " + checks + " checks passed (fake clock/tasks/Codex)");
    }
}
