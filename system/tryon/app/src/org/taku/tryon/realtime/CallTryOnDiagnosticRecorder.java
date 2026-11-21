// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** A run-owned journal. One coalescing writer survives Service teardown without holding camera resources. */
final class CallTryOnDiagnosticRecorder {
    interface Sink { void write(long attempt, CallTryOnDiagnosticStore store, JSONObject report); }
    private static final AtomicLong CURRENT = new AtomicLong();
    private static final AtomicReference<Write> PENDING = new AtomicReference<>();
    private static final AtomicBoolean DRAINING = new AtomicBoolean();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "tryon-diagnostic-writer");
        thread.setDaemon(true); return thread;
    });
    private final long attempt = CURRENT.incrementAndGet();
    private final CallTryOnDiagnosticStore store;
    private final LongSupplier elapsed, wall;
    private final Sink sink;
    private final long began;
    private final JSONObject fields = new JSONObject();
    private long segment = -1, rtcRevision = -1, lastSave = Long.MIN_VALUE;
    private boolean terminal, rtcTerminal, providerConfigured;

    CallTryOnDiagnosticRecorder(CallTryOnDiagnosticStore store, String mode, String phase,
            LongSupplier elapsed, LongSupplier wall) {
        this(store, mode, phase, elapsed, wall, CallTryOnDiagnosticRecorder::enqueue);
    }

    CallTryOnDiagnosticRecorder(CallTryOnDiagnosticStore store, String mode, String phase,
            LongSupplier elapsed, LongSupplier wall, Sink sink) {
        this.store = store; this.elapsed = elapsed; this.wall = wall; this.sink = sink;
        began = elapsed.getAsLong();
        put("attempt", attempt); put("startedAtMs", wall.getAsLong()); put("mode", mode);
        put("phase", phase); put("state", "starting"); put("stage", "source_wait");
        put("failureCode", "none"); put("inputs", 0L); put("outputs", 0L); put("recoveries", 0);
        put("terminal", false); save(true);
    }

    /** Captures one fixed provider/region pair for the entire run, including camera handoffs. */
    synchronized void provider(String provider, String environment) {
        if (!current() || terminal || providerConfigured) return;
        if (!("lucy".equals(provider) || "xmax".equals(provider)) ||
                !("china".equals(environment) || "global".equals(environment))) return;
        put("provider", provider); put("environment", environment);
        providerConfigured = true; save(true);
    }

    synchronized void segment(long id) {
        if (!current() || terminal || id <= segment) return;
        segment = id; rtcRevision = -1; rtcTerminal = false;
        fields.remove("rtc"); fields.remove("tokenHttpStatus");
        put("segment", id); put("stage", "source_wait"); save(true);
    }

    synchronized void stage(long id, String stage, int httpStatus) {
        if (!accepts(id)) return;
        put("stage", stage);
        if (httpStatus >= 100 && httpStatus <= 599) put("tokenHttpStatus", httpStatus);
        save(true);
    }

    synchronized void rtc(long id, Map<String, Object> value) {
        if (!accepts(id) || value == null || rtcTerminal) return;
        Object revision = value.get("diagnosticRevision");
        if (!(revision instanceof Long || revision instanceof Integer)) return;
        long next = ((Number) revision).longValue();
        if (next < 0 || next > 9007199254740991L || next <= rtcRevision) return;
        JSONObject candidate = new JSONObject();
        try { candidate.put("rtc", value); } catch (Exception ignored) { return; }
        JSONObject safe = CallTryOnDiagnosticStore.sanitize(candidate).optJSONObject("rtc");
        if (safe == null) return;
        rtcRevision = next;
        String previous = fields.optString("stage", "unknown");
        String stage = safe.optString("stage", previous);
        String state = safe.optString("state", "unknown");
        rtcTerminal = "failed".equals(state) || "closed".equals(state);
        put("rtc", safe); put("stage", stage);
        save(rtcTerminal || !previous.equals(stage));
    }

    synchronized void progress(String phase, String state, long inputs, long outputs, int recoveries) {
        if (!current() || terminal) return;
        boolean changed = !state.equals(fields.optString("state")) || !phase.equals(fields.optString("phase"));
        put("phase", phase); put("state", state); put("inputs", inputs); put("outputs", outputs);
        put("recoveries", recoveries); save(changed);
    }

    synchronized void finish(String phase, String state, String code, long inputs, long outputs, int recoveries) {
        if (!current() || terminal) return;
        terminal = true;
        put("phase", phase); put("state", state); put("failureCode", CallTryOnFailures.safeCode(code));
        put("inputs", inputs); put("outputs", outputs); put("recoveries", recoveries);
        put("terminal", true); save(true);
    }

    synchronized void restored(boolean restored) {
        if (!current() || !terminal) return;
        if (!restored) {
            put("state", "failed");
            if ("none".equals(fields.optString("failureCode"))) put("failureCode", "restore_unconfirmed");
        }
        put("restoreConfirmed", restored); save(true);
    }

    /** Retains an unconfirmed cloud close from any segment, including cleanup after the run ended. */
    synchronized void cloudCleanup(boolean confirmed) {
        if (!current()) return;
        Object previous = fields.opt("cloudCleanupConfirmed");
        if (Boolean.FALSE.equals(previous) || Boolean.valueOf(confirmed).equals(previous)) return;
        put("cloudCleanupConfirmed", confirmed); save(true);
    }

    synchronized JSONObject snapshot() { return CallTryOnDiagnosticStore.sanitize(fields); }

    private boolean current() { return CURRENT.get() == attempt; }
    private boolean accepts(long id) { return current() && !terminal && segment == id; }
    private void put(String key, Object value) {
        try { fields.put(key, value); } catch (Exception ignored) { }
    }
    private void save(boolean force) {
        if (!current()) return;
        long now = elapsed.getAsLong();
        if (!force && lastSave != Long.MIN_VALUE && now - lastSave < 2000) return;
        lastSave = now;
        put("elapsedMs", Math.max(0, now - began)); put("capturedAtMs", wall.getAsLong());
        try { sink.write(attempt, store, CallTryOnDiagnosticStore.sanitize(fields)); }
        catch (RuntimeException ignored) { /* Evidence collection must not interrupt a user session. */ }
    }

    private static void enqueue(long attempt, CallTryOnDiagnosticStore store, JSONObject value) {
        Write next = new Write(attempt, store, value);
        while (CURRENT.get() == attempt) {
            Write previous = PENDING.get();
            if (previous != null && previous.attempt > attempt) return;
            if (PENDING.compareAndSet(previous, next)) { schedule(); return; }
        }
    }
    private static void schedule() {
        if (!DRAINING.compareAndSet(false, true)) return;
        IO.execute(() -> {
            try {
                Write value;
                while ((value = PENDING.getAndSet(null)) != null) {
                    if (value.attempt == CURRENT.get()) value.store.save(value.report);
                }
            } finally {
                DRAINING.set(false);
                if (PENDING.get() != null) schedule();
            }
        });
    }
    private static final class Write {
        final long attempt; final CallTryOnDiagnosticStore store; final JSONObject report;
        Write(long attempt, CallTryOnDiagnosticStore store, JSONObject report) {
            this.attempt = attempt; this.store = store; this.report = report;
        }
    }
}
