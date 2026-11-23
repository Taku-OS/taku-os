// SPDX-License-Identifier: Apache-2.0
package android.os;

/** Controllable monotonic elapsed time for the production bridge's default constructor. */
public final class SystemClock {
    public static long nowMs;
    public static long elapsedRealtime() { return nowMs; }
}
