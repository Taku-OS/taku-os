// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

import kotlin.math.log10
import kotlin.math.sqrt

internal class PcmLevelMeter(private val emit: (Float,Int)->Unit) {
    private var squares=0L
    private var frames=0

    fun accept(pcm: ShortArray,count: Int,offset: Int=0) {
        for(index in offset until offset+count) {
            val sample=pcm[index].toLong()
            squares+=sample*sample
            if(++frames==1600)flush()
        }
    }

    fun flush() {
        if(frames==0)return
        val rms=sqrt(squares.toDouble()/frames)
        val level=if(squares==0L)0f else ((20*log10(maxOf(rms,1.0)/32768)+60)/60).toFloat().coerceIn(0f,1f)
        val measured=frames
        squares=0;frames=0
        emit(level,measured)
    }
}
