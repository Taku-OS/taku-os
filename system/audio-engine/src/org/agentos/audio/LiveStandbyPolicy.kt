// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

/** Two-level Live Talk standby: hot keeps the provider connection, cold closes it and listens locally. */
internal data class AudioLiveStandby(val enabled: Boolean = true, val hotTimeoutSec: Int = 15, val coldTimeoutSec: Int = 60,
    val lockScreenWakeEnabled: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("hotTimeoutSec", hotTimeoutSec)
        .put("coldTimeoutSec", coldTimeoutSec).put("lockScreenWakeEnabled", lockScreenWakeEnabled)
    companion object {
        val HOT_RANGE = 5..120
        val COLD_RANGE = 15..600
        fun from(value: JSONObject?): AudioLiveStandby {
            value ?: return AudioLiveStandby()
            val hot = value.opt("hotTimeoutSec") as? Int
            val cold = value.opt("coldTimeoutSec") as? Int
            val enabled = value.opt("enabled") as? Boolean
            val lock = value.opt("lockScreenWakeEnabled") as? Boolean
            if (hot == null || cold == null || enabled == null || lock == null || hot !in HOT_RANGE || cold !in COLD_RANGE) return AudioLiveStandby()
            return AudioLiveStandby(enabled, hot, cold, lock)
        }
    }
}

/**
 * Fixed-size ring of the most recent PCM16 bytes, written without allocating. In cold standby it
 * holds the last [capacity] bytes (1.5 s at 16 kHz mono) so the words that woke Live Talk are
 * replayed to the provider once it reconnects.
 */
internal class PcmRingBuffer(val capacity: Int = 48_000) {
    private val data = ByteArray(capacity)
    private var start = 0
    var size = 0
        private set

    fun write(bytes: ByteArray, count: Int = bytes.size) {
        require(count in 0..bytes.size)
        var offset = maxOf(0, count - capacity)
        while(offset < count) {
            val end = (start + size) % capacity
            val chunk = minOf(count - offset, capacity - end)
            System.arraycopy(bytes, offset, data, end, chunk)
            offset += chunk
            val overflow = size + chunk - capacity
            if(overflow > 0) { start = (start + overflow) % capacity; size = capacity } else size += chunk
        }
    }

    /** The buffered bytes, oldest first, trimmed to whole 16-bit samples. */
    fun snapshot(): ByteArray {
        val count = size - size % 2
        val out = ByteArray(count)
        val first = minOf(count, capacity - start)
        System.arraycopy(data, start, out, 0, first)
        if(first < count) System.arraycopy(data, 0, out, first, count - first)
        return out
    }

    fun clear() { start = 0; size = 0 }
}

/**
 * When Live Talk moves between active, hot standby (provider connected, video paused) and cold
 * standby (provider disconnected, the phone listens locally). Pure so host tests cover it.
 */
internal object LiveStandbyPolicy {
    @Volatile var current = AudioLiveStandby()

    const val ACTIVE = "active"
    const val HOT = "hot_standby"
    const val COLD = "cold_standby"
    /** With the screen off or locked, Live Talk goes cold this long after the last activity. */
    const val LOCKED_COLD_MS = 10_000L
    const val LOW_BATTERY_PERCENT = 15

    /**
     * The state Live Talk should be in [quietMs] after its last activity. [busy] (speaking, a reply
     * playing, a tool call awaiting its result) keeps it active; cold standby also needs the wake
     * model ([coldAvailable]) and an allowed lock-screen wake when [locked].
     */
    fun next(state: String, settings: AudioLiveStandby, quietMs: Long, busy: Boolean, locked: Boolean, coldAvailable: Boolean): String {
        if(!settings.enabled || busy) return if(busy) ACTIVE else state
        val hotMs = if(locked) 0L else settings.hotTimeoutSec * 1000L
        val coldMs = if(locked) LOCKED_COLD_MS else hotMs + settings.coldTimeoutSec * 1000L
        val canCold = coldAvailable && (!locked || settings.lockScreenWakeEnabled)
        return when {
            canCold && quietMs >= coldMs -> COLD
            quietMs >= hotMs -> if(state == COLD) COLD else HOT
            else -> state
        }
    }

    /** Standby must end the session: battery under [LOW_BATTERY_PERCENT] while not charging, or battery saver. */
    fun mustEnd(batteryPercent: Int, charging: Boolean, powerSave: Boolean): Boolean =
        powerSave || !charging && batteryPercent in 0 until LOW_BATTERY_PERCENT

    /**
     * Counts consecutive speech windows from Silero VAD probabilities and a near-field energy gate;
     * wakes after [WAKE_MS] of confident speech.
     */
    class WakeDetector(private val windowMs: Int = 32) {
        private var speechMs = 0
        fun accept(probability: Float, rms: Double): Boolean {
            speechMs = if(probability >= SPEECH_PROBABILITY && rms >= NEAR_FIELD_RMS) speechMs + windowMs else 0
            return speechMs >= WAKE_MS
        }
        fun reset() { speechMs = 0 }
        companion object {
            const val WAKE_MS = 240
            const val SPEECH_PROBABILITY = 0.6f
            const val NEAR_FIELD_RMS = 300.0
        }
    }
}
