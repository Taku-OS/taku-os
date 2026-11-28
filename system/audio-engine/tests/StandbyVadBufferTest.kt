// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

private fun check(condition: Boolean, label: String) { if(!condition) throw AssertionError(label) }

fun main() {
    // Ring buffer keeps exactly the newest 1.5 s, oldest first.
    val ring = PcmRingBuffer(8)
    ring.write(byteArrayOf(1, 2, 3, 4))
    check(ring.snapshot().toList() == listOf<Byte>(1, 2, 3, 4), "partial fill")
    ring.write(byteArrayOf(5, 6, 7, 8, 9, 10))
    check(ring.snapshot().toList() == listOf<Byte>(3, 4, 5, 6, 7, 8, 9, 10), "wraps to the newest bytes")
    ring.write(ByteArray(20) { it.toByte() })
    check(ring.snapshot().toList() == (12..19).map { it.toByte() }, "a write larger than the ring keeps its tail")
    ring.write(byteArrayOf(99), 0)
    check(ring.size == 8, "empty write")
    ring.clear(); check(ring.snapshot().isEmpty(), "clear")
    ring.write(byteArrayOf(1, 2, 3)); check(ring.snapshot().size == 2, "whole samples only")
    check(PcmRingBuffer().capacity == 16000 * 2 * 3 / 2, "1.5 s at 16 kHz PCM16")

    // Standby timing.
    val s = AudioLiveStandby()
    val a = LiveStandbyPolicy.ACTIVE; val h = LiveStandbyPolicy.HOT; val c = LiveStandbyPolicy.COLD
    check(LiveStandbyPolicy.next(a, s, 14_999, false, false, true) == a, "active before hot timeout")
    check(LiveStandbyPolicy.next(a, s, 15_000, false, false, true) == h, "hot after 15 s")
    check(LiveStandbyPolicy.next(h, s, 74_999, false, false, true) == h, "hot until cold timeout")
    check(LiveStandbyPolicy.next(h, s, 75_000, false, false, true) == c, "cold 60 s after hot")
    check(LiveStandbyPolicy.next(h, s, 75_000, false, false, false) == h, "no wake model: never cold")
    check(LiveStandbyPolicy.next(h, s, 90_000, true, false, true) == a, "speech or playback keeps it active")
    check(LiveStandbyPolicy.next(a, s.copy(enabled = false), 999_999, false, false, true) == a, "standby off")
    check(LiveStandbyPolicy.next(a, s, 1, false, true, true) == h, "locked: hot at once")
    check(LiveStandbyPolicy.next(h, s, 10_000, false, true, true) == c, "locked: cold after 10 s")
    check(LiveStandbyPolicy.next(h, s.copy(lockScreenWakeEnabled = false), 99_000, false, true, true) == h, "locked without lock wake: no passive listening")
    check(LiveStandbyPolicy.next(c, s, 20_000, false, false, true) == c, "cold stays cold until woken")

    // Battery and saver guard.
    check(LiveStandbyPolicy.mustEnd(14, false, false), "below 15% unplugged")
    check(!LiveStandbyPolicy.mustEnd(14, true, false), "charging is fine")
    check(!LiveStandbyPolicy.mustEnd(15, false, false), "15% is fine")
    check(LiveStandbyPolicy.mustEnd(80, true, true), "battery saver ends standby")
    check(!LiveStandbyPolicy.mustEnd(-1, false, false), "unknown battery level does not end it")

    // Wake detector: 240 ms of confident, near-field speech.
    val wake = LiveStandbyPolicy.WakeDetector(32)
    repeat(7) { check(!wake.accept(0.9f, 1000.0), "not yet") }
    check(wake.accept(0.9f, 1000.0), "wakes at 256 ms")
    wake.reset()
    repeat(20) { check(!wake.accept(0.9f, 100.0), "quiet far-field speech never wakes") }
    repeat(5) { wake.accept(0.9f, 1000.0) }; wake.accept(0.2f, 1000.0)
    repeat(7) { check(!wake.accept(0.9f, 1000.0), "a gap restarts the count") }

    // Settings parsing.
    check(AudioLiveStandby.from(JSONObject().put("enabled", false).put("hotTimeoutSec", 30).put("coldTimeoutSec", 120).put("lockScreenWakeEnabled", false)) ==
        AudioLiveStandby(false, 30, 120, false), "saved standby")
    check(AudioLiveStandby.from(JSONObject().put("enabled", true).put("hotTimeoutSec", 1).put("coldTimeoutSec", 60).put("lockScreenWakeEnabled", true)) == AudioLiveStandby(), "out of range reads as defaults")
    check(AudioLiveStandby.from(null) == AudioLiveStandby(), "missing reads as defaults")
    println("PASS StandbyVadBufferTest: 1.5 s ring buffer, standby timing (incl. locked), battery guard, wake detector and settings parsing")
}
