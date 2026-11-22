// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import java.util.LinkedHashMap;

/** Monotonic-time state machine. Permissions and actual model/UI work belong to the Android caller. */
public final class ObservationPolicy {
    public static final long ACCEPTED_RETRY_TTL_MS = 120000;
    public enum Kind { TEXT, CLASSIFY, VISION }
    public record Candidate(Kind kind, long generation, LivePage page, long createdAt) {}
    public record Config(long textDwellMs, long visionDwellMs, long cooldownMs, long dedupMs,
                         long offerTtlMs, long visionIntervalMs, long visionTtlMs) {
        public Config {
            if (textDwellMs < 0 || visionDwellMs < textDwellMs || cooldownMs < 0 || dedupMs <= 0
                    || offerTtlMs <= 0 || visionIntervalMs <= 0 || visionTtlMs <= 0)
                throw new IllegalArgumentException("Invalid observation policy");
        }
        public static Config defaults() { return new Config(4000, 8000, 60000, 1800000, 60000, 60000, 30000); }
    }

    private final Config config;
    private final LinkedHashMap<String, Long> visionRooms = new LinkedHashMap<>();
    private boolean enabled, classificationEnabled, visionEnabled, acknowledged;
    private long generation, enteredAt, attemptedGeneration = -1;
    private long lastPublished = -1, lastVision = -1;
    private LivePage page;
    private Candidate pendingOffer, activeModel, expiredClassification;
    private Candidate acceptedOffer;
    private long acceptedAt;
    private ProductTypeAnswer.Result classificationResult;

    public ObservationPolicy() { this(Config.defaults()); }
    public ObservationPolicy(Config config) { this.config = java.util.Objects.requireNonNull(config); }

    /** Title-only AI and no-card screenshots have independent, explicit opt-ins. */
    public synchronized void configure(boolean enabled, boolean classificationEnabled, boolean visionEnabled, long now) {
        time(now);
        if (this.enabled != enabled || this.classificationEnabled != classificationEnabled
                || this.visionEnabled != visionEnabled) invalidate();
        this.enabled = enabled;
        this.classificationEnabled = classificationEnabled;
        this.visionEnabled = visionEnabled;
        prune(now);
    }

    /** Pending presenter offers follow the room; unfinished model work still follows its exact product context. */
    public synchronized boolean observe(LivePage next, long now) {
        time(now);
        if (!enabled) next = null;
        if (same(page, next)) { page = next; return false; }
        if ((pendingOffer != null && pendingOffer.kind() == Kind.TEXT && isCurrent(pendingOffer, now)
                || acceptedCurrent(now))
                && sameRoom(page, next)) { page = next; return false; }
        invalidate(); page = next; enteredAt = now; return true;
    }

    public synchronized Candidate poll(long now) {
        time(now); prune(now);
        if (!enabled || page == null || activeModel != null) return null;
        if (pendingOffer != null) {
            if (!isCurrent(pendingOffer, now)) { pendingOffer = null; acknowledged = false; }
            else return null;
        }
        if (attemptedGeneration == generation || !canOffer(page, now)) return null;
        if (page.hasProduct()) {
            if (!classificationEnabled || now - enteredAt < config.textDwellMs()) return null;
            attemptedGeneration = generation;
            expiredClassification = null;
            activeModel = new Candidate(Kind.CLASSIFY, generation, page, now);
            return activeModel;
        }
        if (!visionEnabled || now - enteredAt < config.visionDwellMs()
                || !elapsed(lastVision, now, config.visionIntervalMs())
                || visionRooms.containsKey(page.hostKey())) return null;
        attemptedGeneration = generation; lastVision = now;
        remember(visionRooms, page.hostKey(), now);
        activeModel = new Candidate(Kind.VISION, generation, page, now);
        return activeModel;
    }

    /** Apparel may be offered; a valid uncertain result offers neutral, explicit image confirmation. */
    public synchronized Candidate resolveClassification(Candidate request, ProductTypeAnswer.Result result, long now) {
        time(now);
        if (request == null || activeModel != request || request.kind() != Kind.CLASSIFY) return null;
        activeModel = null;
        boolean expired = expiredClassification == request;
        if (!classificationEnabled || !matchesContext(request) || now < request.createdAt()) return null;
        classificationResult = expired || now - request.createdAt() >= config.visionTtlMs()
            ? ProductTypeAnswer.Result.TIMED_OUT : result == null ? ProductTypeAnswer.Result.UNAVAILABLE : result;
        if (classificationResult == ProductTypeAnswer.Result.TIMED_OUT) expiredClassification = request;
        if ((classificationResult != ProductTypeAnswer.Result.APPAREL && classificationResult != ProductTypeAnswer.Result.UNKNOWN)
                || !isCurrent(request, now)
                || !canOffer(page, now)) return null;
        return newOffer(now);
    }

    /** A UI timeout invalidates the result without pretending the HTTP request already ended. */
    public synchronized boolean classificationTimedOut(Candidate request, long now) {
        time(now);
        if (request == null || activeModel != request || request.kind() != Kind.CLASSIFY
                || now < request.createdAt() || expiredClassification == request) return false;
        expiredClassification = request;
        if (classificationEnabled && matchesContext(request)) classificationResult = ProductTypeAnswer.Result.TIMED_OUT;
        return true;
    }

    /** Always releases this model lease, even when the result is late, negative, or no longer current. */
    public synchronized Candidate resolveVision(Candidate request, boolean apparel, long now) {
        time(now);
        if (request == null || activeModel != request || request.kind() != Kind.VISION) return null;
        activeModel = null;
        if (!visionEnabled || !apparel || !isCurrent(request, now)
                || !canOffer(page, now)) return null;
        return newOffer(now);
    }

    private Candidate newOffer(long now) {
        pendingOffer = new Candidate(Kind.TEXT, generation, page, now);
        acknowledged = false;
        return pendingOffer;
    }

    /** Context freshness only; an accepted ticket remains usable for a bounded asynchronous screenshot. */
    public synchronized boolean isCurrent(Candidate candidate, long now) {
        if (!matchesContext(candidate) || now < candidate.createdAt()) return false;
        if (candidate.kind() == Kind.CLASSIFY && !classificationEnabled
                || candidate.kind() == Kind.VISION && !visionEnabled) return false;
        if (candidate == expiredClassification) return false;
        long ttl = candidate.kind() == Kind.TEXT ? config.offerTtlMs() : config.visionTtlMs();
        return now - candidate.createdAt() < ttl;
    }

    /** Only a real presentation ACK starts the global reminder cooldown. */
    public synchronized boolean published(Candidate candidate, long now) {
        time(now);
        if (candidate == null || pendingOffer != candidate || candidate.kind() != Kind.TEXT
                || acknowledged || !isCurrent(candidate, now)) return false;
        acknowledged = true; lastPublished = now;
        return true;
    }

    /** A direct, authenticated user command may request the current room without an automatic reminder.
     * It does not alter the reminder cooldown or persistent suppression history. */
    public synchronized Candidate explicit(long now) {
        time(now);
        if (!enabled || page == null || activeModel != null) return null;
        pendingOffer = new Candidate(Kind.TEXT, generation, page, now);
        acknowledged = true;
        return pendingOffer;
    }

    /** Successful acceptance consumes this offer exactly once; it does not grant any device action. */
    public synchronized boolean accept(Candidate candidate, long now) {
        time(now);
        if (candidate == null || pendingOffer != candidate || !acknowledged || !isCurrent(candidate, now)) return false;
        consume(); acceptedOffer = candidate; acceptedAt = now; return true;
    }

    /**
     * One explicit failure-control gesture republishes consent for this exact accepted room.
     * It grants no screenshot/model work and does not reset the ordinary reminder cooldown.
     */
    public synchronized Candidate retryAccepted(Candidate failed, long now) {
        time(now);
        if (failed == null || failed != acceptedOffer) return null;
        if (now < acceptedAt || now - acceptedAt >= ACCEPTED_RETRY_TTL_MS) {
            clearAccepted(); return null;
        }
        if (!matchesContext(failed) || pendingOffer != null || activeModel != null) return null;
        clearAccepted();
        return newOffer(now);
    }

    /** A dismissal consumes this page's offer without extending the global cooldown. */
    public synchronized void dismiss(Candidate candidate, long now) {
        time(now);
        if (candidate != null && pendingOffer == candidate && isCurrent(candidate, now)) consume();
        if (candidate != null && acceptedOffer == candidate) clearAccepted();
    }

    private void consume() {
        pendingOffer = null; acknowledged = false;
        clearAccepted();
    }

    public synchronized void invalidate() {
        generation++; page = null; enteredAt = 0; attemptedGeneration = -1;
        pendingOffer = null; acknowledged = false;
        clearAccepted();
        classificationResult = null;
        // A running HTTP request remains the sole model lease until its real completion/finally.
    }

    public synchronized long generation() { return generation; }
    public synchronized LivePage currentPage() { return page; }
    public synchronized boolean visionInFlight() { return activeModel != null && activeModel.kind() == Kind.VISION; }
    public synchronized boolean classificationInFlight() { return activeModel != null && activeModel.kind() == Kind.CLASSIFY; }
    public synchronized boolean modelInFlight() { return activeModel != null; }
    /** Null until this page's classification completes; failures never imply apparel. */
    public synchronized ProductTypeAnswer.Result classificationResult() { return classificationResult; }
    public Config config() { return config; }

    private boolean canOffer(LivePage page, long now) {
        return page != null && elapsed(lastPublished, now, config.cooldownMs());
    }
    private boolean acceptedCurrent(long now) {
        return acceptedOffer != null && now >= acceptedAt && now - acceptedAt < ACCEPTED_RETRY_TTL_MS
            && matchesContext(acceptedOffer);
    }
    private void clearAccepted() { acceptedOffer = null; acceptedAt = 0; }
    private boolean matchesContext(Candidate candidate) {
        return candidate != null && enabled && page != null && candidate.page() != null
            && generation == candidate.generation()
            && (candidate.kind() == Kind.TEXT ? sameRoom(page, candidate.page()) : same(page, candidate.page()));
    }
    private static boolean same(LivePage left, LivePage right) {
        if (left == null || right == null) return left == right;
        return left.windowId() == right.windowId() && left.packageName().equals(right.packageName())
            && left.contentKey().equals(right.contentKey());
    }
    private static boolean sameRoom(LivePage left, LivePage right) {
        return left != null && right != null && left.windowId() == right.windowId()
            && left.packageName().equals(right.packageName()) && left.hostKey().equals(right.hostKey());
    }
    private static boolean elapsed(long since, long now, long interval) { return since < 0 || (now >= since && now - since >= interval); }
    private void prune(long now) {
        visionRooms.entrySet().removeIf(e -> now >= e.getValue() && now - e.getValue() >= config.dedupMs());
    }
    private static void remember(LinkedHashMap<String, Long> map, String key, long now) {
        map.remove(key); map.put(key, now);
        while (map.size() > 128) map.remove(map.keySet().iterator().next());
    }
    private static void time(long now) { if (now < 0) throw new IllegalArgumentException("Use nonnegative monotonic time"); }
}
