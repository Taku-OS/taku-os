// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/** Suppress empty/near-silent file-ASR requests; this is an energy floor, not a speech classifier.
 * 16 kHz PCM, 20 ms windows, DC removed. Require 100 ms of signal including a 60 ms run,
 * so a startup click cannot turn a quiet recording into a transcription request.
 * The 180 RMS floor matches FileSpeechSegments. No words are blacklisted.
 */
internal object WhisperSignalGate {
    fun hasSignal(pcm: ShortArray, count: Int = pcm.size, offset: Int = 0): Boolean {
        require(offset in 0..pcm.size && count in 0..pcm.size - offset)
        var signal = 0
        var run = 0
        var longest = 0
        var start = offset
        val end = offset + count
        while (start < end) {
            val size = minOf(320, end - start)
            var sum = 0L
            var squares = 0L
            for (index in start until start + size) {
                val value = pcm[index].toLong()
                sum += value
                squares += value * value
            }
            val mean = sum.toDouble() / size
            val energy = squares.toDouble() / size - mean * mean
            if (energy >= 180.0 * 180) {
                signal += size
                run += size
                longest = maxOf(longest, run)
            } else run = 0
            if (signal >= 1600 && longest >= 960) return true
            start += size
        }
        return false
    }
}
