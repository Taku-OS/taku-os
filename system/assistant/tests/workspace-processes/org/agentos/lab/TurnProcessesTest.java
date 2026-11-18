package org.agentos.lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Real production proof against isolated proc fixtures; signals are forbidden by the host stub. */
public final class TurnProcessesTest {
    private static final String THREAD = "00000000-0000-0000-0000-000000000001";
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void process(Path root, int pid, int parent, long started, String thread) throws Exception {
        Path dir = Files.createDirectories(root.resolve(Integer.toString(pid)));
        String[] fields = new String[20]; Arrays.fill(fields, "0");
        fields[0] = "S"; fields[1] = Integer.toString(parent); fields[19] = Long.toString(started);
        Files.writeString(dir.resolve("stat"), pid + " (test worker) " + String.join(" ", fields));
        Files.writeString(dir.resolve("environ"), "TAKU_RUNTIME_INSTANCE=test\u0000CODEX_THREAD_ID=" + thread + "\u0000");
    }
    private static JSONObject event(String method, String id) throws Exception {
        return new JSONObject().put("method", method).put("params", new JSONObject().put("threadId", THREAD)
            .put("turn", new JSONObject().put("id", id).put("status", "completed")));
    }
    private static void unknown(TurnProcesses processes, String message) throws Exception {
        try {
            JSONObject result = processes.workspaceProof(THREAD);
            check(!result.optBoolean("verified") || !result.optBoolean("complete"), message);
        } catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("taku-proc-proof-");
        try {
            process(root, 100, 99, 10, "");
            TurnProcesses processes = new TurnProcesses("test", root.toFile(), 99);
            check(processes.workspaceProof(THREAD).getBoolean("complete"), "empty runtime proves stopped");
            processes.starting(THREAD);
            processes.event(event("turn/started", "one"));
            process(root, 101, 100, 20, THREAD);
            processes.capture(THREAD);
            process(root, 101, 1, 20, THREAD); // captured command detaches before completion
            processes.event(event("turn/completed", "one"));
            check(processes.workspaceProof(THREAD).getInt("remainingProcesses") == 1, "detached command survives completed turn tracking");
            processes.starting(THREAD);
            processes.event(event("turn/started", "two"));
            processes.event(event("turn/completed", "two"));
            check(!processes.workspaceProof(THREAD).getBoolean("complete"), "later turn cannot erase earlier persistent command");
            Files.writeString(root.resolve("101/stat"), "invalid stat");
            unknown(processes, "malformed tracked identity must fail closed");
            Files.delete(root.resolve("101/stat"));
            unknown(processes, "existing process with unreadable stat must fail closed");
            process(root, 101, 1, 21, THREAD); // PID reused outside runtime
            check(processes.workspaceProof(THREAD).getBoolean("complete"), "PID reuse does not claim unrelated process");
            process(root, 102, 100, 30, THREAD);
            Files.delete(root.resolve("102/environ"));
            unknown(processes, "live descendant with unreadable environment must fail closed");
            Files.delete(root.resolve("102/stat")); Files.delete(root.resolve("102"));
            check(processes.workspaceProof(THREAD).getBoolean("complete"), "confirmed exit clears candidate");
            process(root, 103, 100, 40, THREAD);
            Files.writeString(root.resolve("103/stat"), "invalid stat");
            unknown(processes, "new descendant identity inspection cannot silently skip candidate");
            Files.delete(root.resolve("103/stat")); Files.delete(root.resolve("103/environ")); Files.delete(root.resolve("103"));
            process(root, 100, 99, 11, "");
            unknown(processes, "runtime PID reuse must fail closed");
            Files.delete(root.resolve("100/stat")); Files.delete(root.resolve("100/environ")); Files.delete(root.resolve("100"));
            unknown(processes, "runtime disappearance must fail closed");
            System.out.println("TurnProcesses: " + checks + " checks passed (no signals sent)");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
