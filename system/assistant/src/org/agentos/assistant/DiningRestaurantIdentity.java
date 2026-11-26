package org.agentos.assistant;

import java.text.Normalizer;

/** Presentation differences do not remove any restaurant or branch words. */
final class DiningRestaurantIdentity {
    private DiningRestaurantIdentity() {}

    static boolean sameName(String expected, String observed) {
        if (expected == null || observed == null || expected.isBlank() || observed.isBlank())
            return false;
        return presentation(expected).equalsIgnoreCase(presentation(observed));
    }

    /** Only an explicit, task-bound native confirmation may authorize this extension. */
    static boolean branchExtension(String expected, String observed) {
        if (expected == null || observed == null || expected.isBlank() || observed.isBlank())
            return false;
        String base = presentation(expected), branch = presentation(observed);
        return branch.length() > base.length() + 3
                && branch.regionMatches(true, 0, base + " - ", 0, base.length() + 3);
    }

    private static String presentation(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .strip()
                // Yelp candidate copy and OpenTable may style the same branch separator
                // differently.
                .replace(" \u2013 ", " - ")
                .replace(" \u2014 ", " - ");
    }
}
