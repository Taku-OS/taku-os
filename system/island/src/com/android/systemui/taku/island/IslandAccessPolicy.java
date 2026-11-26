/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

import java.util.Set;

/**
 * Who may call which island provider operation. Pure rules; Android identity lookups live in
 * IslandProvider. Agents and ADB (UID 0/1000/2000), SystemUI itself and platform-signed packages
 * are trusted. Ordinary third-party apps may only learn whether their own package can publish,
 * unless the App Permission Center grants them {@code taku.control}. That grant reaches only the
 * activities the caller's own UID published: other apps' activities are invisible to it and cannot
 * be operated, so it can never fire another app's action or send a {@code RemoteInput} reply on
 * its behalf. Operations that act on whatever the island shows ({@code collapse}, {@code next}),
 * island settings and the publisher allow-list stay with trusted callers.
 */
public final class IslandAccessPolicy {
    public enum Tier { PUBLIC, CONTROL, GLOBAL, ADMIN }

    public static final String CONTROL_SCOPE = "taku.control";
    public static final String DENIED = "permission_denied";
    public static final String NOT_FOUND = "not_found";
    /** Scope of a trusted caller: every activity. Any other scope is the caller's own UID. */
    public static final int ALL_ACTIVITIES = -1;

    private static final Set<String> PUBLIC = Set.of("capabilities", "canShowIsland");
    /** Act on whichever activity the island presents, whoever published it. */
    private static final Set<String> GLOBAL = Set.of("collapse", "next");
    private static final Set<String> ADMIN = Set.of("setEnabled", "setAllowed", "setProtocol");
    /** Name one activity by key; a scoped caller must have published it. */
    private static final Set<String> TARGETED =
            Set.of("get", "select", "expand", "tap", "dismiss", "hide", "open", "action");

    private IslandAccessPolicy() {}

    /** Unknown names fall into CONTROL so an untrusted caller learns nothing about them. */
    public static Tier tier(String method) {
        if (method != null && PUBLIC.contains(method)) return Tier.PUBLIC;
        if (method != null && GLOBAL.contains(method)) return Tier.GLOBAL;
        if (method != null && ADMIN.contains(method)) return Tier.ADMIN;
        return Tier.CONTROL;
    }

    public static boolean targeted(String method) {
        return method != null && TARGETED.contains(method);
    }

    /** The activities a caller may read and operate: all of them, or only its own UID's. */
    public static int scope(boolean trusted, int callerUid) {
        return trusted ? ALL_ACTIVITIES : callerUid;
    }

    /** Whether an activity published by {@code ownerUid} is within {@code scope}. */
    public static boolean owns(int scope, int ownerUid) {
        return scope == ALL_ACTIVITIES || scope == ownerUid;
    }

    /**
     * A targeted operation on one activity. Another app's activity answers exactly like a missing
     * one, so a scoped caller cannot probe which keys exist.
     *
     * @param ownerUid publishing UID of the addressed activity, or null when there is none
     * @return null when allowed, else the error code returned to the caller
     */
    public static String target(int scope, Integer ownerUid) {
        return ownerUid != null && owns(scope, ownerUid) ? null : NOT_FOUND;
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
