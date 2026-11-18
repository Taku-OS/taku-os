package org.agentos.lab;

/** The host gateway fixture uses a temporary root and never touches Android Settings. */
final class MagpieSettings {
 static String importLegacy(String key) { throw new AssertionError("Android Settings path used by host fixture"); }
}
