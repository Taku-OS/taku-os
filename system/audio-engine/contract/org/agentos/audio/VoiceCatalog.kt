// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

data class LocalVoice(val id: String,val language: String,val speaker: Int,val label: String)
object VoiceCatalog {
    val voices=listOf(
        LocalVoice("zh-CN-local-3","zh-CN",3,"Chinese female voice 1"),LocalVoice("zh-CN-local-20","zh-CN",20,"Chinese female voice 2"),
        LocalVoice("zh-CN-local-58","zh-CN",58,"Chinese male voice 1"),LocalVoice("zh-CN-local-80","zh-CN",80,"Chinese male voice 2"),
        LocalVoice("en-US-local-0","en-US",0,"American English female voice 1"),LocalVoice("en-US-local-1","en-US",1,"American English female voice 2"))
    fun select(language: String,id: String): LocalVoice = voices.firstOrNull{it.language==language && (id.isEmpty() || it.id==id)}
        ?: throw AudioFailure("UNSUPPORTED_VOICE","Select a voice for the current language")
}
