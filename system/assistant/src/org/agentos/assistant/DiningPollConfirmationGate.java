package org.agentos.assistant;

import java.io.IOException;
import java.util.*;

/**
 * Serializes confirmation admission against passive reads, without holding the booking executor.
 */
final class DiningPollConfirmationGate {
    private final Map<String, Ticket> active = new HashMap<>();
    private int readers;
    private long revision;

    static final class Ticket {
        final String id, key, operation;
        boolean entered;

        Ticket(String id, String key) {
            this.id = id;
            this.key = key;
            operation = UUID.randomUUID().toString();
        }
    }

    synchronized Ticket claim(String id, String key) {
        if (active.containsKey(id)) return null;
        Ticket t = new Ticket(id, key);
        active.put(id, t);
        revision++;
        return t;
    }

    synchronized Ticket enter(String id, String key, String operation) throws IOException {
        Ticket t = active.get(id);
        if (t == null) {
            if (!operation.isEmpty()) throw new IOException("CONFIRMATION_EXPIRED");
            t = claim(id, key);
        } else if (operation.isEmpty()
                || !t.key.equals(key)
                || !t.operation.equals(operation)
                || t.entered) throw new IOException("CONFIRMATION_IN_PROGRESS");
        t.entered = true;
        return t;
    }

    synchronized void release(Ticket ticket) {
        if (active.get(ticket.id) == ticket) {
            active.remove(ticket.id);
            notifyAll();
        }
    }

    synchronized boolean owns(Ticket ticket) {
        return active.get(ticket.id) == ticket;
    }

    synchronized boolean active(String id) {
        return active.containsKey(id);
    }

    synchronized boolean busy() {
        return !active.isEmpty();
    }

    synchronized long revision() {
        return revision;
    }

    synchronized boolean sameRevision(long value) {
        return value == revision && !busy();
    }

    synchronized boolean beginRead() {
        if (busy()) return false;
        readers++;
        return true;
    }

    synchronized void endRead() {
        if (readers < 1) throw new IllegalStateException("passive read not admitted");
        readers--;
        notifyAll();
    }

    synchronized void awaitReaders(long timeoutMs) throws Exception {
        long until = System.nanoTime() + timeoutMs * 1000000L;
        while (readers > 0) {
            long left = until - System.nanoTime();
            if (left <= 0) throw new IOException("DINING_POLL_OBSERVER_BUSY");
            wait(Math.max(1, left / 1000000L));
        }
    }
}
