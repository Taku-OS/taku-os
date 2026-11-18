// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

internal class WhisperPcmRanges {
    data class Range(val offset: Int,val count: Int,val final: Boolean)
    private val monitor=Object()
    private var published=0
    private var sealed=false
    private var canceled=false

    fun publish(count: Int) = synchronized(monitor) {
        published=count
        monitor.notifyAll()
    }

    fun seal() = synchronized(monitor) {
        sealed=true
        monitor.notifyAll()
    }

    fun cancel() = synchronized(monitor) {
        canceled=true
        monitor.notifyAll()
    }

    fun next(processed: Int,attempted: Int=processed): Range? = synchronized(monitor) {
        // A failed preview retains its PCM, but must wait for more input rather than spin
        // on the same failed range. Sealing always retries the complete recording.
        while(!canceled && !sealed && published-maxOf(processed,attempted)<32000)monitor.wait()
        when {
            canceled -> null
            sealed -> Range(0,published,true)
            else -> Range(processed,published-processed,false)
        }
    }
}
