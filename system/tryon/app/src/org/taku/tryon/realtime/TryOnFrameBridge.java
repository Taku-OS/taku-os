// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** One bounded source/result lease. Shopping owns its camera; calls use the system frame bridge. */
interface TryOnFrameBridge {
    String start(long durationMs) throws CallTryOnBridge.Failure;
    CallTryOnBridge.Frame source(long afterSequence) throws CallTryOnBridge.Failure;
    boolean submit(byte[] jpeg, int width, int height, long sequence, CallTryOnBridge.Frame source)
            throws CallTryOnBridge.Failure;
    boolean stop();
}
