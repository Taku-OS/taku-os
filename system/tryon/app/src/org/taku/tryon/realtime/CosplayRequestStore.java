// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.taku.tryon.realtime;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.taku.cosplay.api.CosplayContract;

/** Process-local JPEG ownership: prepare copies, take transfers once, cancel/expiry wipe pending data. */
public final class CosplayRequestStore {
    public record Request(byte[] jpeg, String requestId, String sourcePackage,
            long invokedAtElapsedMs, long capturedAtElapsedMs, String target) {
        public Request {
            if (!CosplayPolicy.validTarget(target)) throw new IllegalArgumentException("Invalid voice preview target.");
        }
        public Request(byte[] jpeg, String requestId, String sourcePackage,
                long invokedAtElapsedMs, long capturedAtElapsedMs) {
            this(jpeg, requestId, sourcePackage, invokedAtElapsedMs, capturedAtElapsedMs, CosplayPolicy.TARGET_CHARACTER);
        }
        @Override public String toString() { return "CosplayRequest"; }
    }
    public record Prepared(String ticket, long expiresAtElapsedMs) {
        @Override public String toString() { return "PreparedCosplay"; }
    }
    private static final Slots pending = new Slots();
    private static final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cosplay-ticket-expiry"); thread.setDaemon(true); return thread;
    });

    private CosplayRequestStore() { }

    static Prepared prepare(CosplayPolicy.Request request, long now) {
        Prepared prepared = pending.put(request, now);
        try { cleanup.schedule(() -> discard(prepared.ticket()), CosplayContract.TICKET_TTL_MS, TimeUnit.MILLISECONDS); }
        catch (RuntimeException unavailable) { discard(prepared.ticket()); throw unavailable; }
        return prepared;
    }

    /** Caller owns the returned JPEG and must erase it when preparation has ended. */
    public static Request take(String ticket, long now) { return pending.take(ticket, now); }
    public static void discard(String ticket) { pending.discard(ticket); }

    /** The same production state machine is exercised by host tests without Android or real time. */
    static final class Slots {
        private static final int MAX_SEEN = 128;
        private static final long REPLAY_WINDOW_MS = CosplayContract.MAX_INVOCATION_AGE_MS + CosplayContract.TICKET_TTL_MS;
        private record Entry(Request request, long expiresAt) { }
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final Map<String, Long> seen = new LinkedHashMap<>();
        private final SecureRandom random = new SecureRandom();
        private long lastNow = -1;

        synchronized Prepared put(CosplayPolicy.Request request, long now) {
            if (!advance(now) || now > Long.MAX_VALUE - REPLAY_WINDOW_MS) throw expired();
            if (request == null || request.jpeg() == null || !CosplayPolicy.validTicket(request.requestId()))
                throw new IllegalArgumentException("This cosplay request is invalid.");
            if (seen.containsKey(request.requestId())) throw new IllegalStateException("This voice request was already used. Ask again to start a new cosplay.");
            if (entries.size() >= CosplayContract.MAX_PENDING || seen.size() >= MAX_SEEN)
                throw new IllegalStateException("Another cosplay request is being prepared. Please wait and ask again.");
            byte[] tokenBytes = new byte[16]; String ticket;
            do { random.nextBytes(tokenBytes); ticket = hex(tokenBytes); } while (entries.containsKey(ticket));
            Arrays.fill(tokenBytes, (byte) 0);
            Request owned = new Request(request.jpeg().clone(), request.requestId(), request.sourcePackage(),
                    request.invokedAtElapsedMs(), request.capturedAtElapsedMs(), request.target());
            long expiresAt = now + CosplayContract.TICKET_TTL_MS;
            entries.put(ticket, new Entry(owned, expiresAt));
            seen.put(request.requestId(), now + REPLAY_WINDOW_MS);
            return new Prepared(ticket, expiresAt);
        }
        synchronized Request take(String ticket, long now) {
            if (!advance(now) || !CosplayPolicy.validTicket(ticket)) return null;
            Entry entry = entries.remove(ticket);
            return entry == null ? null : entry.request();
        }
        synchronized void discard(String ticket) {
            Entry entry = ticket == null ? null : entries.remove(ticket);
            if (entry != null) wipe(entry);
        }
        private boolean advance(long now) {
            if (now < 0 || now < lastNow) {
                for (Entry entry : entries.values()) wipe(entry);
                entries.clear(); return false;
            }
            lastNow = now;
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (now >= entry.expiresAt()) { iterator.remove(); wipe(entry); }
            }
            seen.values().removeIf(expires -> now >= expires);
            return true;
        }
        private static void wipe(Entry entry) { Arrays.fill(entry.request().jpeg(), (byte) 0); }
        private static String hex(byte[] bytes) {
            char[] digits = "0123456789abcdef".toCharArray(), encoded = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                encoded[i * 2] = digits[(bytes[i] & 255) >>> 4]; encoded[i * 2 + 1] = digits[bytes[i] & 15];
            }
            return new String(encoded);
        }
        private static IllegalStateException expired() {
            return new IllegalStateException("This cosplay request expired. Ask again from the current TikTok look.");
        }
    }
}
