package org.agentos.environment;

/** Immutable snapshot. Elapsed timestamps belong to the current device boot. */
public final class CountryObservation {
    public enum Availability { AVAILABLE, UNKNOWN }
    public enum Reason { NONE, NOT_OBSERVED, EMPTY_COUNTRY, INVALID_COUNTRY, SECURITY_DENIED, UNSUPPORTED, READ_FAILED }
    public static final String SOURCE = "cellular_network";
    public static final String SOURCE_DESCRIPTION = "Registered operator MCC or nearby cellular network; not GPS, IP, SIM home country, or proof of landing";
    private final String countryIso;
    private final Reason reason;
    private final long observedAtMillis, observedElapsedRealtimeMillis, continuousSinceElapsedRealtimeMillis;
    private final boolean stable;

    CountryObservation(String countryIso, Reason reason, long wall, long elapsed, long since, boolean stable) {
        this.countryIso = countryIso;
        this.reason = reason;
        observedAtMillis = wall;
        observedElapsedRealtimeMillis = elapsed;
        continuousSinceElapsedRealtimeMillis = since;
        this.stable = stable;
    }
    public String countryIso() { return countryIso; }
    public String source() { return SOURCE; }
    public String sourceDescription() { return SOURCE_DESCRIPTION; }
    public Availability availability() { return countryIso.isEmpty() ? Availability.UNKNOWN : Availability.AVAILABLE; }
    public Reason reason() { return reason; }
    public long observedAtMillis() { return observedAtMillis; }
    public long observedElapsedRealtimeMillis() { return observedElapsedRealtimeMillis; }
    /** -1 when unknown; never restored from a previous process or subscription period. */
    public long continuousSinceElapsedRealtimeMillis() { return continuousSinceElapsedRealtimeMillis; }
    public boolean stable() { return stable; }
}
