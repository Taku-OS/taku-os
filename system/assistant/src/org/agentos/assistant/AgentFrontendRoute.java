package org.agentos.assistant;

import java.util.Map;
import java.util.Set;

/** A navigation-only route. Any context not yet owned by the React UI stays native. */
final class AgentFrontendRoute {
    static final String PACKAGE = "org.agentos.agent";
    static final String NATIVE_CONTROLS_ONLY = "nativeControlsOnly";

    /** Set by the gesture handle's double tap (image/launcher/NavHandleDoubleTap.java). */
    static final String EXTRA_TRIGGER = "org.agentos.assistant.extra.TRIGGER";
    /**
     * Arguments an ordinary call may carry: the thread, SystemUI invocation metadata
     * (type, phone state, and time), and the gesture handle's trigger. Framework
     * extras under `android.` (input device, show-session id) are allowed too.
     */
    private static final Set<String> ORDINARY_ARGUMENTS =
            Set.of("conversationId", "invocation_type", "invocation_phone_state", "invocation_time_ms", EXTRA_TRIGGER);

    private AgentFrontendRoute() {}

    static boolean ordinary(Set<String> argumentKeys, Map<String, ?> preferences, String thread) {
        // Unknown launch arguments must retain their existing native semantics.
        for (String key : argumentKeys)
            if (!ORDINARY_ARGUMENTS.contains(key) && !key.startsWith("android.")) return false;
        Object capture = preferences.get("includeScreenshot");
        if (capture != null && !Boolean.FALSE.equals(capture)) return false;
        if (present(preferences.get("home-pending-id")) || present(preferences.get("home-pending-text"))) return false;
        if (!thread.isEmpty() && !thread.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) return false;
        if (thread.isEmpty()) return true;
        String prefix = "composer." + thread + ".";
        if (present(preferences.get("draft-" + thread))) return false;
        for (String field : new String[]{"attachments", "failedDeliveries"})
            if (!emptyContainer(preferences.get(prefix + field), "[]")) return false;
        for (String field : new String[]{"delivery", "steerDelivery"})
            if (!emptyContainer(preferences.get(prefix + field), "{}")) return false;
        return true;
    }

    private static boolean present(Object value) {
        return value != null && (!(value instanceof String) || !((String) value).isBlank());
    }

    private static boolean emptyContainer(Object value, String empty) {
        return value == null || value instanceof String && ((String) value).trim().equals(empty);
    }
}
