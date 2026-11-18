// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/** Short file-ASR phrases at 16 kHz. Four-second ceiling, one-second overlap and explicit tail flush. */
class FileSpeechSegments(private val output: (Segment)->Unit) {
    data class Segment(val pcm: ShortArray,val overlapping: Boolean)
    private val buffer=ShortArray(64000)
    private var size=0;private var voiced=0;private var silence=0;private var overlapping=false
    private var carry=ShortArray(0)
    private val before=java.util.ArrayDeque<ShortArray>();private var beforeSize=0
    fun accept(samples: ShortArray) {
        require(samples.size<=3200)
        if(samples.isEmpty())return
        val audible=samples.sumOf{it.toDouble()*it}/samples.size >= 180.0*180
        if(size==0 && !audible) {
            before.add(samples.copyOf());beforeSize+=samples.size
            while(beforeSize>2560 && before.isNotEmpty())beforeSize-=before.removeFirst().size
            return
        }
        if(size==0) {
            overlapping=carry.isNotEmpty();carry.copyInto(buffer);size=carry.size;carry=ShortArray(0)
            for(frame in before){frame.copyInto(buffer,size);size+=frame.size};before.clear();beforeSize=0
        }
        samples.copyInto(buffer,size);size+=samples.size
        if(audible){voiced+=samples.size;silence=0}else silence+=samples.size
        if(silence>=6400 || size>=60800)emit(silence<6400)
    }
    private fun emit(continuePhrase: Boolean) {
        if(voiced>=3200 || overlapping && voiced>0) {
            val pcm=buffer.copyOf(size)
            output(Segment(pcm,overlapping))
            carry=if(continuePhrase)pcm.takeLast(16000).toShortArray()else ShortArray(0)
        }
        size=0;voiced=0;silence=0;overlapping=false
    }
    fun finish(){if(size>0)emit(false);reset()}
    fun reset(){size=0;voiced=0;silence=0;overlapping=false;carry=ShortArray(0);before.clear();beforeSize=0}
}

/** Strip a repeated prefix only when the PCM segments explicitly overlap. Repeated speech stays valid. */
class CaptionOverlap {
    private var previous=""
    fun fresh(segment: String,overlapping: Boolean): String {
        var fresh=segment.trim();var removed=false
        if(overlapping && previous.isNotEmpty()) {
            val words=Regex("[A-Za-z0-9']+")
            val before=words.findAll(previous).map{it.value.lowercase()}.toList()
            val after=words.findAll(fresh).toList()
            for(count in minOf(16,before.size,after.size) downTo 1)if(before.takeLast(count)==after.take(count).map{it.value.lowercase()}) {
                fresh=fresh.substring(after[count-1].range.last+1);removed=true;break
            }
            if(!removed && fresh.any{it.code>255})for(count in minOf(80,previous.length,fresh.length) downTo 2) {
                if(previous.takeLast(count)==fresh.take(count)){fresh=fresh.drop(count);break}
            }
            fresh=fresh.trimStart{it.isWhitespace() || it in ",.!?;:，。！？；："}
        }
        previous=segment.trim()
        return fresh
    }
}
