package com.android.server.audio;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class PolicyTest {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        require(DedicatedDisplayAudioPolicy.isDedicatedHost(0, "Taku-Independent-Screen"),
                "Recognize the legacy screen");
        require(DedicatedDisplayAudioPolicy.isDedicatedHost(1000, "Taku-Independent-Screen-video"),
                "Recognize named system screens");
        require(!DedicatedDisplayAudioPolicy.isDedicatedHost(10001, "Taku-Independent-Screen"),
                "Ignore a third-party display with the same name");
        require(!DedicatedDisplayAudioPolicy.isDedicatedHost(2000, "Taku-Independent-ScreenMirror"),
                "Ignore unrelated names");

        AtomicInteger notifications = new AtomicInteger();
        DedicatedDisplayAudioPolicy.setOnChange(notifications::incrementAndGet);
        Set<Integer> muted = new HashSet<>(Set.of(10001, 10002));
        DedicatedDisplayAudioPolicy.publish(muted);
        muted.clear();
        require(DedicatedDisplayAudioPolicy.shouldMute(10001), "Copy the ownership snapshot");
        require(!DedicatedDisplayAudioPolicy.shouldMute(10003), "Leave other UIDs audible");
        DedicatedDisplayAudioPolicy.publish(Set.of(10001, 10002));
        require(notifications.get() == 1, "Ignore unchanged window layouts");
        DedicatedDisplayAudioPolicy.publish(Set.of(10002));
        require(!DedicatedDisplayAudioPolicy.shouldMute(10001), "Restore a migrated UID");
        require(DedicatedDisplayAudioPolicy.shouldMute(10002), "Keep the other screen silent");
        DedicatedDisplayAudioPolicy.publish(Set.of());
        require(!DedicatedDisplayAudioPolicy.shouldMute(10002), "Restore after display removal");
        require(notifications.get() == 3, "Notify on every ownership change");
    }
}
