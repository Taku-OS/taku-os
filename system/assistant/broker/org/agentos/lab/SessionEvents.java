package org.agentos.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Bounded, in-memory relay only. Call subscribe under the same lock as the thread reducer. */
final class SessionEvents {
    static final String SCHEMA = "taku.session-events/1";
    static final int MAX_HISTORY_EVENTS = 1024, MAX_HISTORY_BYTES = 4 * 1024 * 1024;
    static final int MAX_QUEUE_EVENTS = 256, MAX_QUEUE_BYTES = 2 * 1024 * 1024;
    static final int MAX_EVENT_BYTES = 8 * 1024 * 1024, MAX_SUBSCRIBERS = 8;
    private String epoch = UUID.randomUUID().toString();
    private final Map<String, Long> sequences = new HashMap<>();
    private final ArrayDeque<Entry> history = new ArrayDeque<>();
    private final Set<Subscription> subscribers = new HashSet<>();
    private int historyBytes;

    private static final class Entry {
        final String thread, line;
        final boolean snapshot;
        final long seq;
        final int bytes;
        Entry(String thread, long seq, String method, String line) {
            this.thread = thread; this.seq = seq; this.line = line;
            snapshot = method.equals("session/snapshot");
            bytes = line.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    final class Subscription implements AutoCloseable {
        final String thread;
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private int bytes;
        private boolean ended;
        Subscription(String thread) { this.thread = thread; }

        private synchronized void offer(Entry entry, boolean initialSnapshot) throws Exception {
            if (ended) return;
            // One authoritative snapshot may be larger than the incremental queue budget.
            int snapshotBytes = !queue.isEmpty() && queue.peekFirst().snapshot ? queue.peekFirst().bytes : 0;
            if ((!initialSnapshot && (queue.size() >= MAX_QUEUE_EVENTS || bytes - snapshotBytes + entry.bytes > MAX_QUEUE_BYTES))
                    || entry.bytes > MAX_EVENT_BYTES) {
                resync("slow-consumer");
                return;
            }
            queue.addLast(entry); bytes += entry.bytes; notifyAll();
        }

        private synchronized void resync(String reason) throws Exception {
            queue.clear(); bytes = 0;
            Entry marker = entry(thread, sequences.getOrDefault(thread, 0L), "session/resync",
                    new JSONObject().put("reason", reason));
            queue.add(marker); bytes = marker.bytes; ended = true; notifyAll();
        }

        synchronized String next(long timeoutMillis) throws InterruptedException {
            if (queue.isEmpty() && !ended) wait(timeoutMillis);
            Entry entry = queue.pollFirst();
            if (entry == null) return null;
            bytes -= entry.bytes;
            return entry.line;
        }

        synchronized boolean ended() { return ended && queue.isEmpty(); }

        @Override public void close() {
            synchronized (SessionEvents.this) { subscribers.remove(this); }
            synchronized (this) { ended = true; queue.clear(); bytes = 0; notifyAll(); }
        }
    }

    private Entry entry(String thread, long seq, String method, JSONObject params) throws Exception {
        return new Entry(thread, seq, method, new JSONObject().put("schema", SCHEMA).put("brokerEpoch", epoch)
                .put("threadId", thread).put("seq", seq).put("method", method).put("params", params).toString());
    }

    synchronized void publish(String thread, String method, JSONObject params) throws Exception {
        long seq = sequences.getOrDefault(thread, 0L) + 1;
        sequences.put(thread, seq);
        Entry entry = entry(thread, seq, method, params);
        history.addLast(entry); historyBytes += entry.bytes;
        while (history.size() > MAX_HISTORY_EVENTS || historyBytes > MAX_HISTORY_BYTES)
            historyBytes -= history.removeFirst().bytes;
        for (Subscription subscriber : subscribers)
            if (thread.equals(subscriber.thread)) subscriber.offer(entry, false);
    }

    synchronized Subscription subscribe(String thread, JSONObject snapshot, String requestedEpoch, long afterSeq)
            throws Exception {
        if (subscribers.size() >= MAX_SUBSCRIBERS) throw new IOException("STREAM_BUSY");
        long current = sequences.getOrDefault(thread, 0L);
        ArrayList<Entry> replay = new ArrayList<>();
        boolean resume = epoch.equals(requestedEpoch) && afterSeq >= 0 && afterSeq <= current;
        if (resume) {
            for (Entry entry : history) if (thread.equals(entry.thread) && entry.seq > afterSeq) replay.add(entry);
            resume = afterSeq == current || (!replay.isEmpty() && replay.get(0).seq == afterSeq + 1);
            int replayBytes = 0;
            for (Entry entry : replay) replayBytes += entry.bytes;
            resume &= replay.size() < MAX_QUEUE_EVENTS && replayBytes < MAX_QUEUE_BYTES;
        }
        Subscription subscription = new Subscription(thread);
        if (resume) {
            subscription.offer(entry(thread, afterSeq, "session/ready", new JSONObject()), false);
            for (Entry entry : replay) subscription.offer(entry, false);
        } else subscription.offer(entry(thread, current, "session/snapshot",
                new JSONObject().put("thread", snapshot)), true);
        subscribers.add(subscription);
        return subscription;
    }

    synchronized String heartbeat(String thread) throws Exception {
        return entry(thread, sequences.getOrDefault(thread, 0L), "session/heartbeat", new JSONObject()).line;
    }

    synchronized void reset() throws Exception {
        for (Subscription subscriber : subscribers) subscriber.resync("runtime-restarted");
        subscribers.clear(); history.clear(); historyBytes = 0; sequences.clear();
        epoch = UUID.randomUUID().toString();
    }
}
