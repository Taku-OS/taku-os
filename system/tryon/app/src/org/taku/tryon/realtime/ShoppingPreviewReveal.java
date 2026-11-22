// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** One real-result reveal per window, fenced permanently by any user presentation choice. */
final class ShoppingPreviewReveal {
    private boolean claimed, interrupted;
    private long epoch = 1;
    long claim(boolean realResultReady) {
        if (!realResultReady || claimed || interrupted) return 0;
        claimed = true; return epoch;
    }
    long claim(boolean realResultReady, boolean characterPreview) {
        return characterPreview ? 0 : claim(realResultReady);
    }
    static boolean showCameraImmediately(boolean directFrames, boolean characterPreview) {
        return !directFrames || characterPreview;
    }
    void interrupt() { interrupted = true; epoch++; }
    boolean current(long ticket) { return ticket != 0 && ticket == epoch && claimed && !interrupted; }
}
