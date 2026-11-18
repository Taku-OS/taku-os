// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/** Bounded chunks at natural sentence/word breaks, shared by native reading and tests. */
object SpeechChunks {
    /** Cumulative plain-text snapshots in, newly completed sentences out. */
    class Stream {
        private var emitted=""
        var finished=false;private set
        fun accept(text: String,complete: Boolean): List<String> {
            check(!finished)
            require(text.startsWith(emitted)){"Previously read reply content changed"}
            var end=emitted.length
            if(complete)end=text.length
            else for(i in emitted.length until text.length) {
                val c=text[i]
                if(c in "。！？!?;；\n" || c=='.' && i+1<text.length && text[i+1].isWhitespace())end=i+1
            }
            val added=text.substring(emitted.length,end)
            emitted=text.substring(0,end);finished=complete
            return split(added)
        }
    }
    @JvmStatic fun split(text: String): List<String> {
        val result=mutableListOf<String>()
        var remaining=text.trim()
        while(remaining.isNotEmpty()) {
            if(remaining.length<=240){result.add(remaining);break}
            val boundary=(80 until 240).lastOrNull{remaining[it] in "。！？.!?\n;；"}
                ?: (120 until 240).lastOrNull{remaining[it].isWhitespace()} ?: 239
            var end=boundary+1
            if(end<remaining.length && Character.isHighSurrogate(remaining[end-1]) && Character.isLowSurrogate(remaining[end]))end--
            result.add(remaining.substring(0,end).trim());remaining=remaining.substring(end).trim()
        }
        return result
    }
}
