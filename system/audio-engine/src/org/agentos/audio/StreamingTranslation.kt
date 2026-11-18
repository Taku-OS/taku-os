// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/** Model transport varies independently of the selected app's audio route. */
interface StreamingTranslation : AutoCloseable {
    val outputSampleRate: Int
    fun connect(key: String)
    fun send(samples: ShortArray)
    fun finish()
    fun cancel(){close()}
}
