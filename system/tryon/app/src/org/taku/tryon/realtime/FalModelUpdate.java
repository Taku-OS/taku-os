// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/**
 * ACK accounting only; carries no prompt, image, token or Android dependency.
 * fal's ACKs have no request ID. Exactly one update may be in flight, and an
 * uncertain/rejected update permanently closes this segment's update lane.
 * Successful sequential requests assume one ordered ACK per changed field;
 * a server duplicating an old ACK after a new send cannot be disambiguated.
 * An optional late initial image ACK also has no identifier; if it arrives
 * during a later image update it is indistinguishable from that update's ACK.
 */
final class FalModelUpdate {
    static final long TIMEOUT_MS = 10_000;
    enum Ack { IGNORED, INITIAL_READY, INITIAL_REJECTED, WAITING, APPLIED, REJECTED, UNCONFIRMED }
    private boolean initialPrompt = true, locked, closed;
    private boolean promptAck, imageAck;
    private long nextId, pendingId, deadline;

    synchronized String availability() {
        if (closed) return "closed";
        if (locked) return "unconfirmed";
        if (pendingId != 0) return "pending";
        if (initialPrompt) return "initial_unconfirmed";
        return "ready";
    }
    synchronized long begin(long now, boolean imageChanged) {
        if (!"ready".equals(availability())) return 0;
        pendingId = ++nextId; deadline = now + TIMEOUT_MS;
        promptAck = true; imageAck = imageChanged;
        return pendingId;
    }
    synchronized Ack acknowledge(String type, Object success) {
        boolean prompt = "prompt_ack".equals(type), image = "set_image_ack".equals(type);
        if ((!prompt && !image) || closed || locked) return Ack.IGNORED;
        if (initialPrompt) {
            // Existing endpoint traces emit prompt_ack but do not promise an
            // initial image ACK, even when the initial payload supplies a URL.
            if (!Boolean.TRUE.equals(success)) { locked = true; return Ack.INITIAL_REJECTED; }
            if (image) return Ack.IGNORED;
            initialPrompt = false; return Ack.INITIAL_READY;
        }
        if (pendingId == 0) {
            // An optional initial image ACK can arrive after generation starts.
            if (image) return Ack.IGNORED;
            // An unsolicited/duplicate ACK makes subsequent attribution unsafe.
            locked = true; return Ack.IGNORED;
        }
        if (image && !imageAck) return Ack.IGNORED;
        if (prompt && !promptAck) {
            pendingId = 0; locked = true; return Ack.UNCONFIRMED;
        }
        if (!Boolean.TRUE.equals(success)) {
            pendingId = 0; locked = true; return Ack.REJECTED;
        }
        if (prompt) promptAck = false; else imageAck = false;
        if (promptAck || imageAck) return Ack.WAITING;
        pendingId = 0; return Ack.APPLIED;
    }
    synchronized boolean expires(long id, long now) {
        if (id == 0 || id != pendingId || now < deadline || closed) return false;
        pendingId = 0; locked = true; return true;
    }
    synchronized boolean fail(long id) {
        if (id == 0 || id != pendingId || closed) return false;
        pendingId = 0; locked = true; return true;
    }
    synchronized boolean owns(long id) { return !closed && id != 0 && id == pendingId; }
    synchronized void close() { closed = true; pendingId = 0; }
}
