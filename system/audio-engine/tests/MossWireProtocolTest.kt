// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

private fun check(condition: Boolean, label: String) { if(!condition) throw AssertionError(label) }
private fun rejects(label: String, block: () -> Unit) {
    try { block() } catch(error: AudioFailure) { check(error.code == "PROVIDER_PROTOCOL_ERROR", "$label code"); return }
    throw AssertionError("$label accepted")
}
private fun json(text: String) = JSONObject(text)

fun main() {
    // Handshake, in the documented order: attach first, then tagged-v1, then media, then the session.
    val attach = json(MossWireProtocol.attachSession(null))
    check(attach.getString("action") == "attach_session" && attach.isNull("session_id") && attach.getString("memory_scope") == "session", "fresh attach")
    check(json(MossWireProtocol.attachSession("s-1")).getString("session_id") == "s-1", "resume attach")
    check(json(MossWireProtocol.mediaMode()).getString("binary_protocol") == "tagged-v1", "tagged-v1")
    val media = json(MossWireProtocol.mediaConfigure(48000))
    check(media.getJSONObject("output_audio").getInt("sample_rate") == 48000 && media.getJSONObject("output_audio").getString("format") == "pcm16", "48 kHz pcm16 output")
    check(media.getJSONObject("input_audio").getInt("sample_rate") == 16000 && media.getString("playback") == "client", "16 kHz input, client playback")
    val stream = media.getJSONObject("video").getJSONObject("stream")
    check(stream.getJSONArray("codecs").getString(0) == "h264" && stream.getJSONArray("codecs").getString(1) == "vp8", "video codec preference")
    check(stream.getInt("fps") in 1..30 && stream.getInt("max_long_side") == 720 && stream.getInt("keyframe_interval_ms") in 1000..60000, "video stream within provider bounds")

    val update = json(MossWireProtocol.sessionUpdate(listOf(LiveHistoryTurn("user", "hello"), LiveHistoryTurn("model", "hi there"))))
    val session = update.getJSONObject("session")
    val tool = session.getJSONArray("tools").getJSONObject(0)
    check(update.getString("action") == "session.update" && tool.getString("type") == "function" && tool.getString("name") == "taku_agent", "client tool registered")
    val actions = tool.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
    check((0 until actions.length()).map(actions::getString) == listOf("submit", "correct", "cancel"), "only Live Talk tool actions")
    val instructions = session.getString("instructions")
    check(instructions.contains("User: hello") && instructions.contains("Assistant: hi there"), "fresh session carries confirmed history")
    check(instructions.length <= MossWireProtocol.INSTRUCTIONS_CHARS, "instructions within provider bound")
    val long = List(24) { LiveHistoryTurn("user", "x".repeat(500)) }
    check(MossWireProtocol.instructions(long).length <= MossWireProtocol.INSTRUCTIONS_CHARS, "bounded history replay")
    check(!MossWireProtocol.instructions(emptyList()).contains("Earlier conversation"), "no history section when empty")

    // Playback receipts.
    val ack = PlaybackChunkAck("r1", "t1", "c1")
    val chunk = json(MossWireProtocol.playbackChunkCompleted(ack))
    check(chunk.getString("action") == "playback.chunk.completed" && chunk.getString("run_id") == "r1" && chunk.getString("turn_id") == "t1" && chunk.getString("chunk_id") == "c1", "chunk receipt")
    check(json(MossWireProtocol.playbackCompleted("r1", "t1")).getString("action") == "playback.completed", "turn receipt")
    val stopped = json(MossWireProtocol.playbackStopped("r1", "t1", 340))
    check(stopped.getString("action") == "playback.stopped" && stopped.getLong("played_audio_ms") == 340L, "stop receipt with played ms")
    check(json(MossWireProtocol.playbackStopped("r1", "t1", -5)).getLong("played_audio_ms") == 0L, "played ms never negative")

    // Tool output is a function_call_output item whose output is a JSON string, then response.create.
    val output = json(MossWireProtocol.toolOutput("call-1", JSONObject().put("state", "accepted")))
    check(output.getString("action") == "conversation.item.create", "tool output action")
    val item = output.getJSONObject("item")
    check(item.getString("type") == "function_call_output" && item.getString("call_id") == "call-1" && json(item.getString("output")).getString("state") == "accepted", "tool output item")
    check(json(MossWireProtocol.responseCreate("call-1")).getJSONObject("response").getJSONArray("call_ids").getString(0) == "call-1", "response.create continues the call")
    check(json(MossWireProtocol.commentary("y".repeat(5000))).getString("content").length == MossWireProtocol.APPEND_CHARS, "commentary bounded")
    check(json(MossWireProtocol.thinking("progress")).getString("action") == "session.thinking.append", "silent context")

    // tagged-v1 binary frames.
    val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
    val audio = MossWireProtocol.encodeAudio(pcm, 4)
    check(audio.size == 5 && audio[0] == 0.toByte() && audio[1] == 1.toByte() && audio[4] == 4.toByte(), "audio tag 0x00 + PCM")
    val video = MossWireProtocol.encodeVideo("screen", 16000L * 3 + 7, true, byteArrayOf(9, 8))
    check(video.size == 12 && video[0] == 0x06.toByte() && video[9] == 1.toByte() && video[10] == 9.toByte(), "screen video header")
    var samples = 0L
    for(i in 0 until 8) samples = samples or ((video[1 + i].toLong() and 0xff) shl (8 * i))
    check(samples == 48007L, "capture time in 16 kHz samples, little endian")
    check(MossWireProtocol.encodeVideo("camera", 0, false, ByteArray(0))[0] == 0x05.toByte(), "camera tag")
    check(MossWireProtocol.encodeVideo("camera", 0, false, ByteArray(0))[9] == 0.toByte(), "non-key flags")
    check(MossWireProtocol.decodeAudio(byteArrayOf(1, 2)).size == 2, "downstream audio has no prefix")
    rejects("odd PCM") { MossWireProtocol.decodeAudio(byteArrayOf(1, 2, 3)) }

    // Server events.
    fun event(action: String, data: JSONObject = JSONObject()) = MossWireProtocol.parse(JSONObject().put("action", action).put("data", data).toString())
    check(event("session_attached", JSONObject().put("session_id", "s-9")) == MossWireProtocol.Event.SessionAttached("s-9"), "attached")
    rejects("blank session") { event("session_attached", JSONObject().put("session_id", "")) }
    check(event("media.configured", JSONObject().put("output_audio", JSONObject().put("format", "pcm16").put("sample_rate", 24000))) ==
        MossWireProtocol.Event.MediaConfigured(24000, null, null), "24 kHz fallback")
    val withVideo = event("media.configured", JSONObject().put("output_audio", JSONObject().put("format", "pcm16").put("sample_rate", 48000))
        .put("video", JSONObject().put("stream", JSONObject().put("codec", "vp8")))) as MossWireProtocol.Event.MediaConfigured
    check(withVideo.videoCodec == "vp8", "negotiated video codec")
    val vp9 = event("media.configured", JSONObject().put("output_audio", JSONObject().put("format", "pcm16").put("sample_rate", 48000))
        .put("video", JSONObject().put("stream", JSONObject().put("codec", "vp9")))) as MossWireProtocol.Event.MediaConfigured
    check(vp9.videoCodec == null, "a codec the encoder cannot produce means audio only")
    check((event("media.configured", JSONObject().put("output_audio", JSONObject().put("format", "pcm16").put("sample_rate", 48000)))
        as MossWireProtocol.Event.MediaConfigured).videoCodec == null, "no stream means no video")
    check(json(MossWireProtocol.videoState("screen", false)).let { it.getString("action") == "video.state" && !it.getBoolean("enabled") && it.getString("source") == "screen" }, "video.state")
    rejects("opus output") { event("media.configured", JSONObject().put("output_audio", JSONObject().put("format", "opus").put("sample_rate", 48000))) }
    rejects("16 kHz output") { event("media.configured", JSONObject().put("output_audio", JSONObject().put("format", "pcm16").put("sample_rate", 16000))) }
    check(event("session.updated", JSONObject().put("error", "")) == MossWireProtocol.Event.SessionUpdated(null), "empty error is success")
    check(event("session.updated", JSONObject().put("error", "bad tool")) == MossWireProtocol.Event.SessionUpdated("bad tool"), "update error surfaced")
    check(event("response.audio.context", JSONObject().put("run_id", "r").put("turn_id", "t").put("chunk_id", "c")) ==
        MossWireProtocol.Event.AudioContext("r", "t", "c"), "audio context")
    check(event("response.audio.stop", JSONObject().put("run_id", "r").put("turn_id", "t").put("reason", "barge_in")) ==
        MossWireProtocol.Event.AudioStop("r", "t"), "server barge-in")
    check(event("response.interrupted") == MossWireProtocol.Event.Interrupted("", ""), "interrupted without ids")
    check(event("input.transcript.done", JSONObject().put("text", "hi").put("turn", 3)) == MossWireProtocol.Event.InputTranscript("3", "hi", true), "final input snapshot")
    check(event("output.transcript.updated", JSONObject().put("run_id", "r").put("turn_id", "t").put("text", "He")) ==
        MossWireProtocol.Event.OutputTranscript("r", "t", "He", false), "output snapshot")
    check(event("response.function_call_arguments.done", JSONObject().put("call_id", "k").put("name", "taku_agent").put("arguments", "{\"action\":\"submit\",\"text\":\"t\"}")) ==
        MossWireProtocol.Event.ToolCall("k", "taku_agent", "{\"action\":\"submit\",\"text\":\"t\"}"), "tool call")
    val summary = event("response.done", JSONObject().put("response", JSONObject().put("output", org.json.JSONArray()
        .put(JSONObject().put("type", "message"))
        .put(JSONObject().put("type", "function_call").put("call_id", "k").put("name", "taku_agent").put("arguments", "{}"))))) as MossWireProtocol.Event.ToolCalls
    check(summary.calls.map { it.callId } == listOf("k"), "response.done repeats function calls for dedupe")
    check(event("conversation.item.created", JSONObject().put("item", JSONObject().put("call_id", "k"))) == MossWireProtocol.Event.ItemCreated("k"), "item created")
    check(event("error", JSONObject().put("error", JSONObject().put("code", "unknown_call_id"))) == MossWireProtocol.Event.Failure("unknown_call_id"), "error code")
    check(event("session.closing", JSONObject().put("code", "draining").put("reconnect", true)) == MossWireProtocol.Event.Closing("draining", true), "closing")
    check(event("pong") is MossWireProtocol.Event.Ignored, "unknown events ignored")
    rejects("invalid JSON") { MossWireProtocol.parse("{") }

    // Layered backpressure: video yields long before audio is at risk.
    val policy = MossBackpressure()
    check(policy.video(10_000, false, 0) == MossBackpressure.Video.SEND, "idle queue sends")
    check(policy.video(100 * 1024L, false, 10) == MossBackpressure.Video.SKIP, "skip non-key frames above 96 KB")
    check(policy.video(100 * 1024L, true, 20) == MossBackpressure.Video.SEND, "key frames still sent above 96 KB")
    check(policy.video(130 * 1024L, false, 100) == MossBackpressure.Video.SKIP, "high queue starts pause timer")
    check(policy.video(130 * 1024L, true, 1700) == MossBackpressure.Video.PAUSE && policy.videoPaused, "pause after 1.5 s above 128 KB")
    check(policy.video(60 * 1024L, true, 1800) == MossBackpressure.Video.SKIP, "stay paused until drained")
    check(policy.video(40 * 1024L, true, 2000) == MossBackpressure.Video.SKIP, "drain timer starts below 48 KB")
    check(policy.video(40 * 1024L, true, 4100) == MossBackpressure.Video.RESUME && !policy.videoPaused, "resume after 2 s below 48 KB")
    check(policy.audioAllowed(500 * 1024L) && !policy.audioAllowed(513 * 1024L), "audio fails only above 512 KB")
    check(policy.audioAllowed(128 * 1024L), "a video key frame burst never ends audio")

    println("PASS MossWireProtocolTest: documented handshake, tool output/response.create, playback receipts, tagged-v1 audio/video frames, event parsing and layered backpressure")
}
