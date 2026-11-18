package org.agentos.lab;

import java.io.IOException;

/** Exercises only a pure predicate; does not instantiate or call any Android UI API. */
public final class PhoneDraftRootAvailabilityTest {
    private static int checks;
    private static void unavailable(boolean emptyBounds, boolean visible) throws Exception {
        try { PhoneDraftHandoff.requireRootContent(0, emptyBounds, visible); }
        catch (IOException error) {
            if (!"FOREGROUND_CONTENT_UNAVAILABLE".equals(error.getMessage())) throw new AssertionError(error);
            checks++; return;
        }
        throw new AssertionError("Empty unavailable root was treated as a title mismatch");
    }
    public static void main(String[] args) throws Exception {
        // Observed WeChat placeholder: zero children, zero bounds, not visible.
        unavailable(true, false);
        unavailable(true, true);
        unavailable(false, false);
        // A normal visible leaf still proceeds to the exact title/editor checks.
        PhoneDraftHandoff.requireRootContent(0, false, true); checks++;
        // An unimportant/invisible wrapper may still have visible children; preserve traversal.
        PhoneDraftHandoff.requireRootContent(1, true, false); checks++;
        PhoneDraftHandoff.requireRootContent(1, false, false); checks++;
        PhoneDraftHandoff.requireRootContent(1, true, true); checks++;
        PhoneDraftHandoff.requireRootContent(1, false, true); checks++;
        System.out.println("PhoneDraftRootAvailabilityTest: " + checks + " checks passed");
    }
}
