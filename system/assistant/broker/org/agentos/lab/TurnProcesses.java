package org.agentos.lab;

import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.JSONObject;

/** Android cleanup supplement to native turn cancellation, scoped to this server's children.
 * Native command processId values are session handles, never Linux PIDs.
 */
final class TurnProcesses {
    static final String OWNER_ENV = "TAKU_RUNTIME_INSTANCE";
    private final String owner;
    private final File procRoot;
    private final Identity server;
    private final Map<String, Turn> turns = new HashMap<>();
    private final Map<String, Set<String>> preserved = new HashMap<>();

    private static final class Identity {
        final int pid, parent;
        final long started;
        final char state;
        Identity(int pid, String stat) {
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" +");
            this.pid = pid;
            state = fields[0].charAt(0);
            parent = Integer.parseInt(fields[1]);
            started = Long.parseLong(fields[19]);
        }
        boolean same(Identity other) { return other != null && pid == other.pid && started == other.started; }
        String key() { return pid + ":" + started; }
    }

    private static final class Turn {
        final Set<String> baseline;
        final Map<String, Identity> captured = new LinkedHashMap<>();
        String id = "";
        boolean inspectionFailed;
        Turn(Set<String> baseline) { this.baseline = baseline; }
    }

    TurnProcesses(String owner) throws IOException {
        this(owner, new File("/proc"), android.os.Process.myPid());
    }

    /** Isolated proc fixture support; production always uses /proc. */
    TurnProcesses(String owner, File procRoot, int brokerPid) throws IOException {
        this.owner = owner;
        this.procRoot = procRoot;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        Identity found;
        do {
            found = null;
            for (Identity candidate : snapshot().values()) {
                if (candidate.parent == brokerPid
                        && environment(candidate.pid, OWNER_ENV).equals(owner)) {
                    if (found != null) throw new IOException("Ambiguous runtime process owner");
                    found = candidate;
                }
            }
            if (found != null) break;
            if (System.nanoTime() >= deadline)
                throw new IOException("Cannot establish runtime process owner");
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Cannot establish runtime process owner", interrupted);
            }
        } while (true);
        server = found;
    }

    synchronized void starting(String thread) throws IOException {
        Set<String> baseline = new HashSet<>();
        for (Identity process : owned(thread).values()) baseline.add(process.key());
        turns.put(thread, new Turn(baseline));
    }

    synchronized void capture(String thread) {
        Turn turn = turns.get(thread);
        if (turn == null) return;
        try {
            for (Identity process : owned(thread).values())
                if (!turn.baseline.contains(process.key())) turn.captured.put(process.key(), process);
        } catch (IOException unavailable) { turn.inspectionFailed = true; }
    }

    synchronized void interrupting(String thread, String turnId) throws IOException {
        Turn turn = turns.get(thread);
        if (turn == null || !turn.id.equals(turnId))
            throw new IOException("The target turn has ended. The stop request was not sent to the new turn.");
        capture(thread);
    }

    synchronized JSONObject event(JSONObject event) throws Exception {
        JSONObject params = event.optJSONObject("params");
        if (params == null) return null;
        String thread = params.optString("threadId");
        String method = event.optString("method");
        // Native queues can begin turns without a client turn/start request.
        if (method.equals("turn/started") && !turns.containsKey(thread))
            turns.put(thread, new Turn(preserved.getOrDefault(thread, Collections.emptySet())));
        Turn turn = turns.get(thread);
        if (turn == null) return null;
        if (method.equals("turn/started")) turn.id = params.getJSONObject("turn").getString("id");
        if (method.equals("item/started")) capture(thread);
        if (!method.equals("turn/completed")) return null;
        JSONObject ended = params.getJSONObject("turn");
        if (!turn.id.isEmpty() && !turn.id.equals(ended.getString("id"))) return null;
        try {
            if (!ended.optString("status").equals("interrupted")) return null;
            capture(thread);
            JSONObject result = terminate(turn.captured.values()).put("turnId", ended.getString("id"));
            if (turn.inspectionFailed) result.put("complete", false).put("reason", "process-inspection-unavailable");
            return result;
        } finally {
            // Retain known identities after reparenting and across later turns. A fresh
            // descendant-only snapshot must never erase a still-live earlier command.
            Set<String> tracked = new HashSet<>(preserved.getOrDefault(thread, Collections.emptySet()));
            tracked.addAll(turn.baseline);
            tracked.addAll(turn.captured.keySet());
            preserved.put(thread, tracked);
            turns.remove(thread);
            tracked.addAll(owned(thread).keySet());
            Iterator<String> identities = tracked.iterator();
            while (identities.hasNext()) {
                String key = identities.next();
                Identity current = readIdentityChecked(Integer.parseInt(key.substring(0, key.indexOf(':'))));
                if (current == null || current.state == 'Z' || !current.key().equals(key)) identities.remove();
            }
        }
    }

    /** Close owns the entire runtime, including persistent sessions, but no sibling services. */
    synchronized void close() {
        try { terminate(owned(null).values()); } catch (Exception ignored) { }
        turns.clear();
        preserved.clear();
    }

    /** A read-only handoff check, including persistent processes from earlier turns.
     * This proves the managed runtime scope, not isolation from arbitrary root code.
     * Never kill a prior command merely to make a workspace available to the user.
     */
    synchronized JSONObject workspaceProof(String thread) throws Exception {
        if (thread == null || !thread.matches("[a-f0-9-]{36}")) throw new IOException("INVALID_SCREEN_THREAD");
        JSONObject result = new JSONObject().put("observedAt", System.currentTimeMillis())
                .put("scope", "runtime-tagged-thread-descendants-and-previously-tracked-processes");
        Map<Integer, Identity> snapshot = snapshot();
        if (!server.same(snapshot.get(server.pid)))
            return result.put("verified", false).put("complete", false).put("reason", "runtime-identity-unavailable");
        Set<Integer> descendants = new HashSet<>(); descendants.add(server.pid);
        boolean changed;
        do {
            changed = false;
            for (Identity process : snapshot.values())
                if (descendants.contains(process.parent) && descendants.add(process.pid)) changed = true;
        } while (changed);
        Set<String> remaining = new HashSet<>();
        for (int pid : descendants) {
            Identity identity = snapshot.get(pid);
            if (pid == server.pid || identity.state == 'Z') continue;
            String environment;
            try { environment = read(new File(procRoot, pid + "/environ"), 1_048_576); }
            catch (IOException unavailable) {
                if (identity.same(readIdentityChecked(pid)))
                    return result.put("verified", false).put("complete", false).put("reason", "process-inspection-unavailable");
                continue;
            }
            boolean own = false, inThread = false;
            for (String entry : environment.split("\u0000")) {
                if (entry.equals(OWNER_ENV + "=" + owner)) own = true;
                if (entry.equals("CODEX_THREAD_ID=" + thread)) inThread = true;
            }
            if (own && inThread && identity.same(readIdentityChecked(pid))) remaining.add(identity.key());
        }
        Set<String> tracked = new HashSet<>(preserved.getOrDefault(thread, Collections.emptySet()));
        Turn turn = turns.get(thread);
        if (turn != null) {
            if (turn.inspectionFailed)
                return result.put("verified", false).put("complete", false).put("reason", "process-inspection-unavailable");
            tracked.addAll(turn.captured.keySet());
        }
        for (String key : tracked) {
            int separator = key.indexOf(':');
            Identity current = readIdentityChecked(Integer.parseInt(key.substring(0, separator)));
            if (current != null && current.state != 'Z' && current.key().equals(key)) remaining.add(key);
        }
        if (!server.same(readIdentityChecked(server.pid)))
            return result.put("verified", false).put("complete", false).put("reason", "runtime-identity-changed");
        return result.put("verified", true).put("complete", remaining.isEmpty())
                .put("remainingProcesses", remaining.size());
    }

    private Map<String, Identity> owned(String thread) throws IOException {
        Map<Integer, Identity> processes = snapshot();
        Map<String, Identity> result = new LinkedHashMap<>();
        if (!server.same(processes.get(server.pid))) return result;
        Set<Integer> descendants = new HashSet<>();
        descendants.add(server.pid);
        boolean changed;
        do {
            changed = false;
            for (Identity process : processes.values()) {
                if (descendants.contains(process.parent) && descendants.add(process.pid)) changed = true;
            }
        } while (changed);
        for (int pid : descendants) {
            if (pid == server.pid) continue;
            Identity process = processes.get(pid);
            if (process.state == 'Z' || !environment(pid, OWNER_ENV).equals(owner)) continue;
            if (thread == null || environment(pid, "CODEX_THREAD_ID").equals(thread)) result.put(process.key(), process);
        }
        return result;
    }

    private JSONObject terminate(Collection<Identity> identities) throws Exception {
        List<Identity> targets = new ArrayList<>(identities);
        // Signal children before parents; keep identities after reparenting and reject PID reuse.
        Collections.reverse(targets);
        for (Identity target : targets) signal(target, OsConstants.SIGTERM);
        long deadline = System.nanoTime() + 300_000_000L;
        while (alive(targets) > 0 && System.nanoTime() < deadline) Thread.sleep(20);
        for (Identity target : targets) signal(target, OsConstants.SIGKILL);
        deadline = System.nanoTime() + 500_000_000L;
        while (alive(targets) > 0 && System.nanoTime() < deadline) Thread.sleep(20);
        int remaining = alive(targets);
        return new JSONObject().put("complete", remaining == 0).put("trackedProcesses", targets.size())
                .put("remainingProcesses", remaining).put("scope", "runtime-owned-new-processes-in-turn");
    }

    private int alive(Collection<Identity> targets) {
        int remaining = 0;
        for (Identity target : targets) {
            Identity current = readIdentity(target.pid);
            if (target.same(current) && current.state != 'Z') remaining++;
        }
        return remaining;
    }

    private void signal(Identity target, int signal) {
        Identity current = readIdentity(target.pid);
        if (!target.same(current) || current.state == 'Z') return;
        try { Os.kill(target.pid, signal); } catch (android.system.ErrnoException ignored) { }
    }

    private Identity readIdentity(int pid) {
        try { return readIdentityChecked(pid); }
        catch (IOException unavailable) { return null; } // Signal path never targets an unverified identity.
    }

    /** Null means a confirmed vanished process; permission/parse/I/O errors remain unknown. */
    private Identity readIdentityChecked(int pid) throws IOException {
        File directory = new File(procRoot, Integer.toString(pid));
        try { return new Identity(pid, read(new File(directory, "stat"), 8192)); }
        catch (IOException | RuntimeException unavailable) {
            try { Files.readAttributes(directory.toPath(), BasicFileAttributes.class); }
            catch (NoSuchFileException exited) { return null; }
            catch (IOException unknown) { throw new IOException("Cannot verify process identity", unknown); }
            throw new IOException("Cannot read process identity", unavailable);
        }
    }

    private Map<Integer, Identity> snapshot() throws IOException {
        File[] entries = procRoot.listFiles();
        if (entries == null) throw new IOException("Cannot inspect runtime children");
        Map<Integer, Identity> processes = new LinkedHashMap<>();
        for (File entry : entries) {
            if (!entry.getName().matches("[0-9]+")) continue;
            Identity identity = readIdentityChecked(Integer.parseInt(entry.getName()));
            if (identity != null) processes.put(identity.pid, identity);
        }
        return processes;
    }

    /** Examine only the requested tag of owned candidate processes. Never log environments. */
    private String environment(int pid, String key) {
        try {
            String prefix = key + "=";
            for (String entry : read(new File(procRoot, pid + "/environ"), 1_048_576).split("\u0000"))
                if (entry.startsWith(prefix)) return entry.substring(prefix.length());
        } catch (IOException exited) { }
        return "";
    }

    private static String read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > limit) throw new IOException("Process metadata exceeds limit");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
}
