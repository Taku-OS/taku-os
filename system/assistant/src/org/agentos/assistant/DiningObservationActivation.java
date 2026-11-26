package org.agentos.assistant;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Only a current-process explicit operation activates observation; persisted history stays inert.
 */
final class DiningObservationActivation {
    private final Set<String> ids = ConcurrentHashMap.newKeySet();

    void activate(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("INVALID_DINING_ID");
        ids.add(id);
    }

    boolean contains(String id) {
        return id != null && ids.contains(id);
    }
}
