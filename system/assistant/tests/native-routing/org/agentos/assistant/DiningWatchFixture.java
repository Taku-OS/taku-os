package org.agentos.assistant;

/** Exposes the actual Assistant watch-key producer to the Broker contract test. */
public final class DiningWatchFixture {
    private DiningWatchFixture() {}

    public static String watchId(String intentId) {
        return DiningPollBookingFlow.watchId(intentId);
    }
}
