/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

/**
 * What the lock screen may show and run for one island activity. Pure rules; the inputs come from
 * Android (notification visibility, the user's lock-screen notification settings, the keyguard),
 * never from the publisher's JSON.
 *
 * Activities stay on the lock screen in full while the phone is locked, like Live Activities on
 * iOS; ordinary notifications keep the lock screen's own hide-until-authenticated rule.
 */
public final class IslandLockScreenPolicy {
    /** Not on the lock screen. */
    public static final int HIDDEN = 0;
    /** Present, but only as its glyph: no text, timer, number or card. */
    public static final int GLYPH = 1;
    /** Capsule and card with their content. */
    public static final int FULL = 2;

    /** Field [IslandActivity#category] of a Now Playing activity. */
    public static final String MEDIA = "media";

    private IslandLockScreenPolicy() {}

    /**
     * @param enabled the "show activities on the lock screen" setting
     * @param tier {@link IslandStore} tier; calls, alarms, recording and casting are below
     *     {@link IslandStore#TIER_ACTIVITY}
     * @param assistant Taku Agent's assistant activity, available only after authentication
     * @param authenticated the keyguard can be dismissed without a credential
     * @param secret the notification is {@code VISIBILITY_SECRET}
     * @param allowedOnLockScreen the user shows this user's notifications on the lock screen
     * @param needsRedaction Android would show this notification's public version on a locked
     *     screen (sensitive content hidden by the user's setting, a one-time code). Android keeps
     *     reporting it while the keyguard shows, also after the user has authenticated.
     * @param profileLocked the notification's profile has its own challenge and is still locked
     */
    public static int exposure(boolean enabled, int tier, boolean assistant, boolean authenticated,
            boolean secret, boolean allowedOnLockScreen, boolean needsRedaction, boolean profileLocked) {
        if (!enabled) return HIDDEN;
        // Stock Android already shows and operates these over the lock screen.
        if (tier < IslandStore.TIER_ACTIVITY) return FULL;
        if (secret || !allowedOnLockScreen) return HIDDEN;
        if (assistant && !authenticated) return HIDDEN;
        // Authenticating reveals the content, except for a profile that is still locked.
        return needsRedaction && (!authenticated || profileLocked) ? GLYPH : FULL;
    }

    /**
     * Whether a tap may open the activity's screen while the phone is locked: calls and alarms,
     * whose screens show over the keyguard as in stock Android. Anything else needs the user first.
     */
    public static boolean openWhileLocked(int tier) {
        return tier <= IslandStore.TIER_ALARM;
    }

    /**
     * Whether an action button runs while the phone is locked: SystemUI's own controller actions
     * (stop recording, stop casting), call and alarm actions, and Now Playing transport controls.
     * Everything else, and opening the app, needs the user to authenticate first.
     */
    public static boolean actionWhileLocked(int tier, boolean nativeAction, String category) {
        return nativeAction || tier < IslandStore.TIER_ACTIVITY || MEDIA.equals(category);
    }
}
