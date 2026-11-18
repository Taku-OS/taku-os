// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/** Keeps fractional sample position across synthesis callbacks. */
class PcmResampler(private val inputRate: Int, private val outputRate: Int = 16000) {
    private var index = 0L
    private var next = 0.0
    private var previous = 0f
    init { require(inputRate > 0 && outputRate > 0) }
    fun accept(samples: FloatArray): ShortArray {
        val capacity = samples.size.toLong() * outputRate / inputRate + 2
        require(capacity <= Int.MAX_VALUE) { "Audio block is too large" }
        val output = ArrayList<Short>(capacity.toInt())
        for (current in samples) {
            while (next <= index) {
                val fraction = if (index == 0L) 1.0 else next - (index - 1)
                val value = previous * (1 - fraction) + current * fraction
                output.add((value.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
                next += inputRate.toDouble() / outputRate
            }
            previous = current; index++
        }
        return output.toShortArray()
    }
}
