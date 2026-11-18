/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

/** Pure classification rules; inputs come from Android notification fields/roles, never Taku JSON. */
public final class IslandSourcePolicy {
    private IslandSourcePolicy() {}
    public static String category(String category, boolean callStyle, boolean system, boolean dialer,
            boolean ownCalls, boolean ongoing, boolean fullScreen, boolean countdown) {
        if (callStyle && (dialer || ownCalls || system)) return "call";
        if ("call".equals(category) && dialer && (ongoing || fullScreen)) return "call";
        if ("alarm".equals(category) && (fullScreen || ongoing)) {
            return !fullScreen && countdown ? "timer" : "alarm";
        }
        if ("stopwatch".equals(category) && ongoing) return "timer";
        return null;
    }
}
