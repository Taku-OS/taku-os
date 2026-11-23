/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

import java.util.Set;

/**
 * Who may call which island provider operation. Pure rules; Android identity lookups live in
 * IslandProvider. Agents and ADB (UID 0/1000/2000), SystemUI itself and platform-signed packages
 * are trusted. Ordinary third-party apps may only learn whether their own package can publish,
 * unless the App Permission Center grants them {@code taku.control}; even then they can never
 * change island settings or the publisher allow-list.
 */
public final class IslandAccessPolicy {
    public enum Tier { PUBLIC, CONTROL, ADMIN }

    public static final String CONTROL_SCOPE = "taku.control";
    public static final String DENIED = "permission_denied";

    private static final Set<String> PUBLIC = Set.of("capabilities", "canShowIsland");
    private static final Set<String> ADMIN = Set.of("setEnabled", "setAllowed", "setProtocol");

    private IslandAccessPolicy() {}

    /** Unknown names fall into CONTROL so an untrusted caller learns nothing about them. */
    public static Tier tier(String method) {
        if (method != null && PUBLIC.contains(method)) return Tier.PUBLIC;
        if (method != null && ADMIN.contains(method)) return Tier.ADMIN;
        return Tier.CONTROL;
    }

    public static boolean trustedUid(int uid, int selfUid) {
        return uid == 0 || uid == 1000 || uid == 2000 || uid == selfUid;
    }

    /** Whether the App Permission Center must be consulted for this call. */
    public static boolean needsGrant(String method, boolean trusted) {
        return !trusted && tier(method) == Tier.CONTROL;
    }

    /**
     * @param ownPackage for canShowIsland: the queried package belongs to the caller's UID
     * @return null when allowed, else the error code returned to the caller
     */
    public static String decide(String method, boolean trusted, boolean controlGranted, boolean ownPackage) {
        if (trusted) return null;
        switch (tier(method)) {
            case PUBLIC:
                return !"canShowIsland".equals(method) || ownPackage ? null : DENIED;
            case CONTROL:
                return controlGranted ? null : DENIED;
            default:
                return DENIED;
        }
    }
}
