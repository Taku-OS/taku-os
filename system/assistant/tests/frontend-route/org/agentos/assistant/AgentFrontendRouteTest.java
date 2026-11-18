package org.agentos.assistant;

import java.util.Map;
import java.util.Set;

public final class AgentFrontendRouteTest {
    private static final String THREAD = "f891f77d-019c-48e1-8935-75ee445126c1";
    private static int checks;

    public static void main(String[] arguments) {
        check(true, Set.of(), Map.of(), "", "ordinary initial entry");
        check(true, Set.of("conversationId"), Map.of(), THREAD, "exact existing conversation");
        check(false, Set.of("conversationId"), Map.of(), "not-a-thread", "invalid conversation retains native fallback");
        for (String key : new String[]{"page", "jevTestToken", "manualScreen", "nativeControlsOnly", "task_id", "futureContext"})
            check(false, Set.of("conversationId", key), Map.of(), THREAD, key + " keeps native semantics");
        // System assist calls: AssistUtils extras, framework extras and the gesture handle's trigger.
        check(true, Set.of("invocation_type", "invocation_time_ms"), Map.of(), "", "assist key or corner gesture");
        Set<String> powerButtonArgs = Set.of("invocation_type", "invocation_phone_state", "invocation_time_ms",
                "android.intent.extra.ASSIST_INPUT_DEVICE_ID", "android.service.voice.SHOW_SESSION_ID");
        check(true, powerButtonArgs, Map.of(), THREAD, "power button opens the selected conversation");
        check(true, Set.of("invocation_type", "org.agentos.assistant.extra.TRIGGER"), Map.of(), THREAD, "gesture handle double tap");
        check(true, Set.of("invocation_type", "android.intent.extra.ASSIST_INPUT_DEVICE_ID",
                "android.service.voice.SHOW_SESSION_ID"), Map.of(), THREAD, "framework extras");
        check(false, Set.of("invocation_type", "org.agentos.assistant.extra.TRIGGER", "page"), Map.of(), THREAD,
                "a settings page on a system call stays native");
        check(false, Set.of("org.agentos.assistant.extra.TRIGGER"), Map.of("includeScreenshot", true), THREAD,
                "double tap keeps opted-in screen capture");
        check(false, powerButtonArgs, Map.of("draft-" + THREAD, "retained user input"), THREAD,
                "power button does not strand a native draft");
        check(false, Set.of(), Map.of("includeScreenshot", true), THREAD, "capture is not dropped");
        check(false, Set.of(), Map.of("includeScreenshot", "true"), THREAD, "malformed capture preference fails closed");
        check(true, Set.of(), Map.of("includeScreenshot", false), THREAD, "capture disabled");
        for (String key : new String[]{"home-pending-id", "home-pending-text", "draft-" + THREAD})
            check(false, Set.of(), Map.of(key, "retained user input"), THREAD, key + " is not stranded");
        for (String field : new String[]{"attachments", "failedDeliveries", "delivery", "steerDelivery"}) {
            String key = "composer." + THREAD + "." + field;
            check(false, Set.of(), Map.of(key, "retained or malformed state"), THREAD, field + " is not stranded");
            check(true, Set.of(), Map.of(key, field.equals("attachments") || field.equals("failedDeliveries") ? "[]" : "{}"), THREAD, field + " explicitly empty");
        }
        check(true, Set.of(), Map.of("draft-other-thread", "unrelated"), THREAD, "another conversation does not block current navigation");
        System.out.println("AgentFrontendRouteTest: " + checks + " checks passed");
    }

    private static void check(boolean expected, Set<String> keys, Map<String, ?> preferences, String thread, String reason) {
        checks++;
        if (AgentFrontendRoute.ordinary(keys, preferences, thread) != expected) throw new AssertionError(reason);
    }
}
