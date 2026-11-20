// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.agentos.audio.AudioClient
import org.json.JSONObject

class LiveResultProbe : Instrumentation() {
    private var expectedProfile: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedProfile = arguments?.getString("profileId")
        start()
    }

    override fun onStart() {
        val events = LinkedBlockingQueue<JSONObject>()
        val client = AudioClient(targetContext, object : AudioClient.Listener {
            override fun connected() { events.add(JSONObject().put("connected", true)) }
            override fun event(event: String) { events.add(JSONObject(event)) }
            override fun failed(message: String) { events.add(JSONObject().put("failure", message)) }
        })
        fun take(description: String, matches: (JSONObject) -> Boolean): JSONObject {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                check(remaining > 0) { "Timed out waiting for $description" }
                val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: error("Timed out waiting for $description")
                check(!event.has("failure")) { event.getString("failure") }
                val data = event.optJSONObject("data")
                check(data?.optString("kind") != "Closed") { data?.optString("reason").orEmpty() }
                check(event.optString("type") != "response" || event.optBoolean("ok")) { event.optJSONObject("error").toString() }
                if (matches(event)) return event
            }
        }
        fun live(description: String, matches: (JSONObject) -> Boolean): JSONObject =
            take(description) { it.optJSONObject("data")?.optJSONObject("live")?.let(matches) == true }
                .getJSONObject("data").getJSONObject("live")

        val report = JSONObject().put("passed", false)
        var sessionId: String? = null
        try {
            runOnMainSync { client.connect() }
            take("audio connection") { it.optBoolean("connected") }
            var openId = ""
            runOnMainSync { openId = client.open(JSONObject().put("purpose", "LIVE_TALK")) }
            val lease = take("Live Talk lease") { it.optString("id") == openId }.getJSONObject("result")
            val session = lease.getString("sessionId")
            sessionId = session
            val profile = lease.getString("profileId")
            report.put("profileId", profile)
            expectedProfile?.let { check(profile == it) { "Expected Live Talk with $it, got $profile" } }
            live("microphone ready") { it.optString("kind") == "Mic" && !it.optBoolean("muted") }
            runOnMainSync {
                client.call("audio.live.text", JSONObject().put("sessionId", session).put("text", "Say hello in one word."))
            }
            val input = live("confirmed input") { it.optString("kind") == "Input" && it.optBoolean("final") }
            live("completed initial playback") { it.optString("kind") == "Playback" && it.optString("state") == "stopped" }
            fun offer(id: String, afterInput: String, text: String) {
                runOnMainSync {
                    client.call("audio.live.result", JSONObject().put("sessionId", session).put("offerId", id)
                        .put("afterInput", afterInput).put("text", text))
                }
            }
            offer("old", "previous-input", "The old result is 123.")
            val stale = live("stale result rejected") { it.optString("offerId") == "old" }
            check(!stale.getBoolean("sent")) { "A result for old input reached the provider" }
            offer("current", input.getString("itemId"), "The current result is 37.")
            val current = live("current result sent") { it.optString("offerId") == "current" }
            check(current.getBoolean("sent")) { "Provider did not accept the result for the confirmed input" }
            val answer = live("final result caption") { it.optString("kind") == "Output" && it.optBoolean("final") }
            val caption = answer.getString("text")
            check(Regex("\\b(37|thirty[- ]seven)\\b", RegexOption.IGNORE_CASE).containsMatchIn(caption)) {
                "Provider reply did not contain the result: $caption"
            }
            live("result playback complete") {
                it.optString("kind") == "Playback" && it.optString("state") == "stopped" &&
                    it.optString("responseId") == answer.getString("responseId")
            }
            report.put("passed", true).put("staleSuppressed", true).put("played", caption)
        } catch (failure: Exception) {
            report.put("error", failure.message ?: failure.javaClass.simpleName)
        } finally {
            runOnMainSync {
                sessionId?.let { runCatching { client.cancel(it) } }
                client.close()
            }
        }
        finish(if (report.getBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
    }
}
