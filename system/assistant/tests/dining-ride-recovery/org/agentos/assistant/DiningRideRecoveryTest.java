package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Exercises the production cold-runtime recovery gate without Android or an actual broker. */
public final class DiningRideRecoveryTest {
    private static final String THREAD = "01a20c54-7be4-75b3-8fc9-9a975a5d9e11";
    private static int checks;

    private static JSONObject idle(boolean ready) {
        return new JSONObject().put("ready", ready).put("requests", new JSONArray());
    }

    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static void reject(String code) throws Exception {
        checks++;
        try {
            DiningRideInputRecovery.checkIdle(THREAD);
            throw new AssertionError("Accepted " + code);
        } catch (IOException failure) {
            if (!code.equals(failure.getMessage())) throw failure;
        }
    }

    private static void coldTrace() {
        check(
                NativeClient.calls.equals(
                        List.of("state:" + THREAD, "thread/read:" + THREAD, "state:" + THREAD)),
                "Cold runtime only reads the original thread, then rechecks its state");
    }

    private static void warmTrace() {
        check(
                NativeClient.calls.equals(List.of("state:" + THREAD)),
                "Ready runtime performs no initialization RPC");
    }

    private static void unchangedTasks(List<Path> before) throws Exception {
        try (var paths = Files.walk(TaskRuntime.ROOT.toPath())) {
            check(
                    before.equals(paths.sorted().toList()),
                    "Read-only recovery creates no task or runtime file");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("taku-dining-ride-recovery-");
        try {
            TaskRuntime.ROOT = root.toFile();
            TaskRuntime.TASKS = Files.createDirectory(root.resolve("tasks")).toFile();
            List<Path> baseline;
            try (var paths = Files.walk(root)) {
                baseline = paths.sorted().toList();
            }

            NativeClient.reset(THREAD, idle(false), idle(true));
            DiningRideInputRecovery.checkIdle(THREAD);
            coldTrace();
            unchangedTasks(baseline);

            NativeClient.reset(THREAD, idle(true));
            DiningRideInputRecovery.checkIdle(THREAD);
            warmTrace();
            unchangedTasks(baseline);

            NativeClient.reset(THREAD, idle(false));
            NativeClient.readResult =
                    new JSONObject().put("thread", new JSONObject().put("id", "other-thread"));
            reject("SOURCE_THREAD_NOT_FOUND");
            check(
                    NativeClient.calls.equals(List.of("state:" + THREAD, "thread/read:" + THREAD)),
                    "A different returned thread stops initialization immediately");

            NativeClient.reset(THREAD, idle(false));
            NativeClient.readResult = new JSONObject();
            reject("SOURCE_THREAD_NOT_FOUND");

            NativeClient.reset(THREAD, idle(false));
            NativeClient.readFailure = new IOException("RUNTIME_UNAVAILABLE");
            reject("RUNTIME_UNAVAILABLE");
            check(
                    NativeClient.calls.size() == 2,
                    "A failed read never dispatches work or retries initialization");

            NativeClient.reset(THREAD, idle(false), idle(false));
            reject("THREAD_BUSY");
            coldTrace();

            for (JSONObject busy :
                    new JSONObject[] {
                        idle(true).put("activeTurnId", "active-turn"),
                        idle(true)
                                .put(
                                        "requests",
                                        new JSONArray()
                                                .put(
                                                        new JSONObject()
                                                                .put("key", "pending-request"))),
                        idle(true)
                                .put(
                                        "cancellationCleanup",
                                        new JSONObject().put("complete", false)),
                        new JSONObject().put("ready", true)
                    }) {
                NativeClient.reset(THREAD, idle(false), busy);
                reject("THREAD_BUSY");
                coldTrace();
            }

            NativeClient.reset(
                    THREAD,
                    idle(true).put("cancellationCleanup", new JSONObject().put("complete", true)));
            DiningRideInputRecovery.checkIdle(THREAD);
            warmTrace();
            unchangedTasks(baseline);

            Path queued = Files.createDirectory(root.resolve("tasks/1795507495657"));
            Files.write(queued.resolve("ready"), "1".getBytes(StandardCharsets.UTF_8));
            List<Path> queuedBaseline;
            try (var paths = Files.walk(root)) {
                queuedBaseline = paths.sorted().toList();
            }
            NativeClient.reset(THREAD, idle(false), idle(true));
            reject("THREAD_QUEUED");
            coldTrace();
            unchangedTasks(queuedBaseline);
            check(
                    "1"
                            .equals(
                                    new String(
                                            Files.readAllBytes(queued.resolve("ready")),
                                            StandardCharsets.UTF_8)),
                    "Recovery preserves the existing queued task");

            Files.write(queued.resolve("done"), "completed".getBytes(StandardCharsets.UTF_8));
            NativeClient.reset(THREAD, idle(false), idle(true));
            DiningRideInputRecovery.checkIdle(THREAD);
            coldTrace();
            try (var tasks = Files.list(TaskRuntime.TASKS.toPath())) {
                check(
                        tasks.count() == 1,
                        "A completed existing task permits recovery without another task");
            }

            System.out.println("DiningRideRecoveryTest: " + checks + " checks passed");
        } finally {
            // Delete only this test's newly created isolated temporary tree.
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
