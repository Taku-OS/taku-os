package org.agentos.lab

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.HashSet
import java.util.Objects

/** Bounded OpenAI chat SSE decoding, independent of Android and the HTTP caller. */
internal class UiSseStream {
    fun interface Delta {
        @Throws(Exception::class)
        fun accept(delta: String)
    }

    /** A cancellation is permanent. Admitted callbacks run outside the lock and may still be in flight. */
    class Flight {
        @Volatile
        private var cancelled = false
        private var connection: HttpURLConnection? = null

        fun cancel() {
            val active: HttpURLConnection?
            synchronized(this) {
                cancelled = true
                active = connection
                connection = null
            }
            if (active != null) active.disconnect()
        }

        fun cancelled(): Boolean = cancelled

        @Synchronized
        @Throws(InterruptedIOException::class)
        fun check() {
            if (cancelled) throw InterruptedIOException("UI generation was cancelled")
        }

        @Synchronized
        @Throws(IOException::class)
        fun attach(active: HttpURLConnection?) {
            check()
            if (connection != null) throw IOException("A UI generation request is already running")
            connection = Objects.requireNonNull(active)
        }

        @Synchronized
        fun detach(active: HttpURLConnection?) {
            if (connection === active) connection = null
        }

        @Throws(Exception::class)
        fun emit(delta: Delta, value: String) {
            check()
            delta.accept(value)
            check()
        }
    }

    companion object {
        const val MAX_TEXT = 24000
        const val MAX_LINE_BYTES = 196608
        const val MAX_EVENT_CHARS = 196608
        const val MAX_STREAM_BYTES = 2000000
        const val MAX_EVENTS = 16384

        @JvmStatic
        @Throws(Exception::class)
        fun read(input: InputStream?, flight: Flight?, delta: Delta?): JSONObject {
            Objects.requireNonNull(input)
            Objects.requireNonNull(flight)
            Objects.requireNonNull(delta)
            return Parser(flight!!, delta!!).read(input!!)
        }

        private fun invalid(): IOException = IOException("The model did not return a complete streaming text result")
        private fun oversized(): IOException = IOException("The model's streaming reply is too long")
    }

    private class Parser(val flight: Flight, val delta: Delta) {
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val data = StringBuilder()
        val text = StringBuilder()
        var line = ByteArray(4096)
        var lineLength = 0
        var totalBytes = 0
        var events = 0
        var firstLine = true
        var skipLf = false
        var dataSeen = false
        var stopped = false
        var done = false
        var usageSeen = false
        var usage = JSONObject()

        @Throws(Exception::class)
        fun read(input: InputStream): JSONObject {
            val buffer = ByteArray(8192)
            while (true) {
                flight.check()
                val count = input.read(buffer)
                flight.check()
                if (count < 0) break
                if (count == 0) continue
                if (count > MAX_STREAM_BYTES - totalBytes) throw oversized()
                totalBytes += count
                for (i in 0 until count) {
                    val value = buffer[i].toInt() and 255
                    if (skipLf) {
                        skipLf = false
                        if (value == '\n'.code) continue
                    }
                    if (value == '\r'.code || value == '\n'.code) {
                        consumeLine()
                        skipLf = value == '\r'.code
                        continue
                    }
                    if (lineLength == MAX_LINE_BYTES) throw oversized()
                    if (lineLength == line.size) line = Arrays.copyOf(line, Math.min(MAX_LINE_BYTES, line.size * 2))
                    line[lineLength++] = value.toByte()
                }
            }
            flight.check()
            if (lineLength != 0 || dataSeen || !stopped || !done || text.isEmpty()) throw invalid()
            return JSONObject().put("text", text.toString()).put("usage", usage)
        }

        @Throws(Exception::class)
        fun consumeLine() {
            var value = utf8.decode(ByteBuffer.wrap(line, 0, lineLength)).toString()
            lineLength = 0
            if (firstLine) {
                firstLine = false
                if (value.startsWith("\ufeff")) value = value.substring(1)
            }
            if (value.isEmpty()) {
                if (dataSeen) {
                    val event = data.toString()
                    data.setLength(0)
                    dataSeen = false
                    consumeEvent(event)
                }
                return
            }
            if (value[0] == ':') return
            val colon = value.indexOf(':')
            val field = if (colon < 0) value else value.substring(0, colon)
            if (field != "data") return
            var part = if (colon < 0) "" else value.substring(colon + 1)
            if (part.startsWith(" ")) part = part.substring(1)
            val added = part.length + if (dataSeen) 1 else 0
            if (added > MAX_EVENT_CHARS - data.length) throw oversized()
            if (dataSeen) data.append('\n')
            data.append(part)
            dataSeen = true
        }

        @Throws(Exception::class)
        fun consumeEvent(event: String) {
            flight.check()
            if (++events > MAX_EVENTS) throw oversized()
            if (done) throw invalid()
            if (event == "[DONE]") {
                if (!stopped) throw invalid()
                done = true
                return
            }
            JsonSyntax.require(event)
            val tokener = JSONTokener(event)
            val parsed = tokener.nextValue()
            if (parsed !is JSONObject || tokener.nextClean() != '\u0000') throw invalid()
            if (parsed.has("error") && !parsed.isNull("error")) throw invalid()
            val choices = parsed.opt("choices")
            if (choices !is JSONArray) throw invalid()
            val rawUsage = parsed.opt("usage")
            var nextUsage: JSONObject? = null
            if (rawUsage != null && rawUsage !== JSONObject.NULL) {
                if (rawUsage !is JSONObject || usageSeen) throw invalid()
                nextUsage = rawUsage
            }
            if (choices.length() == 0) {
                if (nextUsage == null || !stopped) throw invalid()
                usage = nextUsage
                usageSeen = true
                return
            }
            if (choices.length() != 1 || stopped) throw invalid()
            val choice = choices.get(0)
            if (choice !is JSONObject) throw invalid()
            val index = choice.opt("index")
            val update = choice.opt("delta")
            if (index !is Number || index.toDouble() != 0.0 || update !is JSONObject || choice.has("message")) throw invalid()
            if (present(update, "tool_calls") || present(update, "function_call") || present(choice, "tool_calls") || present(choice, "function_call")) throw invalid()
            if (present(update, "role") && update.get("role") != "assistant") throw invalid()
            val rawText = update.opt("content")
            if (rawText != null && rawText !== JSONObject.NULL && rawText !is String) throw invalid()
            val finish = choice.opt("finish_reason")
            val stop = finish != null && finish !== JSONObject.NULL
            if (stop && finish != "stop") throw invalid()
            val value = if (rawText is String) rawText else ""
            if (value.length > MAX_TEXT - text.length) throw oversized()
            if (nextUsage != null) {
                usage = nextUsage
                usageSeen = true
            }
            if (stop) stopped = true
            if (value.isNotEmpty()) {
                text.append(value)
                flight.emit(delta, value)
            }
        }

        fun present(value: JSONObject, key: String): Boolean = value.has(key) && !value.isNull(key)
    }

    /** org.json accepts JavaScript extensions. SSE payloads must be strict JSON. */
    private class JsonSyntax(val text: String) {
        var at = 0

        companion object {
            @Throws(IOException::class)
            fun require(text: String) {
                val syntax = JsonSyntax(text)
                syntax.value(0)
                syntax.space()
                if (syntax.at != text.length) throw invalid()
            }
        }

        fun space() {
            while (at < text.length) {
                val c = text[at]
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break
                at++
            }
        }

        fun take(c: Char): Boolean {
            if (at < text.length && text[at] == c) {
                at++
                return true
            }
            return false
        }

        @Throws(IOException::class)
        fun need(c: Char) {
            if (!take(c)) throw invalid()
        }

        @Throws(IOException::class)
        fun value(depth: Int) {
            if (depth > 32) throw invalid()
            space()
            if (at == text.length) throw invalid()
            val c = text[at]
            if (c == '{') {
                at++
                space()
                if (take('}')) return
                val names = HashSet<String>()
                do {
                    space()
                    val start = at
                    string()
                    val encoded = text.substring(start, at)
                    val name: String
                    try {
                        name = JSONTokener(encoded).nextValue() as String
                    } catch (e: Exception) {
                        throw invalid()
                    }
                    if (!names.add(name)) throw invalid()
                    space()
                    need(':')
                    value(depth + 1)
                    space()
                    if (take('}')) return
                    need(',')
                } while (true)
            }
            if (c == '[') {
                at++
                space()
                if (take(']')) return
                do {
                    value(depth + 1)
                    space()
                    if (take(']')) return
                    need(',')
                } while (true)
            }
            if (c == '"') {
                string()
                return
            }
            if (c == 't') {
                literal("true")
                return
            }
            if (c == 'f') {
                literal("false")
                return
            }
            if (c == 'n') {
                literal("null")
                return
            }
            take('-')
            if (take('0')) {
                if (at < text.length && digit(text[at])) throw invalid()
            } else {
                if (at == text.length || text[at] < '1' || text[at] > '9') throw invalid()
                while (at < text.length && digit(text[at])) at++
            }
            if (take('.')) digits()
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                digits()
            }
        }

        @Throws(IOException::class)
        fun literal(value: String) {
            if (!text.startsWith(value, at)) throw invalid()
            at += value.length
        }

        @Throws(IOException::class)
        fun digits() {
            val start = at
            while (at < text.length && digit(text[at])) at++
            if (at == start) throw invalid()
        }

        fun digit(value: Char): Boolean = value >= '0' && value <= '9'

        @Throws(IOException::class)
        fun string() {
            need('"')
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return
                if (c < ' ') throw invalid()
                if (c == '\\') {
                    if (at == text.length) throw invalid()
                    val escape = text[at++]
                    if (escape == 'u') {
                        for (n in 0 until 4) {
                            if (at == text.length) throw invalid()
                            val hex = text[at++]
                            if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') || (hex >= 'A' && hex <= 'F'))) throw invalid()
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw invalid()
                }
            }
            throw invalid()
        }
    }
}
