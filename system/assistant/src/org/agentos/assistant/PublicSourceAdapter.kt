package org.agentos.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.HashSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Public HTTP observations share the runtime's source owner and timer queue. */
internal class PublicSourceAdapter(private val context: Context, private val kind: String) : SourceAdapter {
    override fun watch(resource: JSONObject, invalidated: Runnable): AutoCloseable {
        val interval = if (kind == "weather") 10 * 60_000L else 15 * 60_000L
        val timer = refreshes.scheduleWithFixedDelay(invalidated, interval, interval, TimeUnit.MILLISECONDS)
        return AutoCloseable { timer.cancel(false) }
    }

    @Throws(Exception::class)
    override fun read(resource: JSONObject): JSONObject = if (kind == "weather") weather(resource) else feed(resource)

    private class Response(val body: String, val modified: Long)

    class Failure(status: Int, retryAfter: String?) : IOException("SOURCE_HTTP_" + status) {
        @JvmField val retryAfterAt: Long

        init {
            var at = 0L
            if (retryAfter != null) {
                try {
                    at = System.currentTimeMillis() + Math.multiplyExact(java.lang.Long.parseLong(retryAfter.trim { it <= ' ' }), 1000L)
                } catch (_: Exception) {
                    try {
                        at = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    } catch (_: Exception) { }
                }
            }
            retryAfterAt = Math.max(0L, at)
        }
    }

    private fun base(target: String, hash: String, validUntil: Long): JSONObject =
        JSONObject().put("state", "available").put("observedAt", System.currentTimeMillis()).put("validUntil", validUntil)
            .put("contentHash", hash).put("provenance", identity(kind)).put("url", target)
            .put("capabilities", JSONObject().put("read", true).put("watch", true).put("command", false).put("typedPrice", false))
            .put("pollIntervalMs", if (kind == "weather") 10 * 60_000L else 15 * 60_000L)

    private fun weather(resource: JSONObject): JSONObject {
        val coordinate = resource.getString("resourceId")
        val latitude: Double
        val longitude: Double
        if (coordinate == "device") {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("LOCATION_PERMISSION_REQUIRED")
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var newest: Location? = null
            for (provider in manager.getProviders(true)) {
                val candidate = manager.getLastKnownLocation(provider)
                if (candidate != null && (newest == null || candidate.elapsedRealtimeNanos > newest.elapsedRealtimeNanos)) newest = candidate
            }
            if (newest == null || android.os.SystemClock.elapsedRealtimeNanos() - newest.elapsedRealtimeNanos > 30L * 60 * 1_000_000_000L)
                throw IOException("LOCATION_OR_CITY_REQUIRED")
            latitude = newest.latitude
            longitude = newest.longitude
        } else {
            val values = (coordinate as java.lang.String).split(",", -1)
            if (values.size != 2) throw IOException("COORDINATES_REQUIRED")
            latitude = java.lang.Double.parseDouble(values[0])
            longitude = java.lang.Double.parseDouble(values[1])
            if (!java.lang.Double.isFinite(latitude) || !java.lang.Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
                throw IOException("INVALID_COORDINATES")
        }
        val target = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=5&timezone=auto&timeformat=unixtime"
        val response = fetch(target)
        val data = JSONObject(response.body)
        val current = data.getJSONObject("current")
        val daily = data.getJSONObject("daily")
        val zone = data.getString("timezone")
        val unit = data.getJSONObject("current_units").getString("temperature_2m")
        val observed = current.getLong("time") * 1000L
        val now = System.currentTimeMillis()
        val description = condition(current.getInt("weather_code"))
        val metric = block("temperature", "metric", "", description, JSONObject().put("type", "number")
            .put("value", current.getDouble("temperature_2m")).put("unit", unit))
        metric.put("config", JSONObject().put("emphasis", "hero").put("size", "full_size")
            .put("icon", conditionIcon(current.getInt("weather_code"))))
        metric.getJSONArray("items").put(JSONObject().put("id", "high").put("label", "High")
            .put("value", JSONObject().put("type", "number").put("value", daily.getJSONArray("temperature_2m_max").getDouble(0)).put("unit", unit))
            .put("detail", "").put("url", "").put("image", "").put("icon", ""))
            .put(JSONObject().put("id", "low").put("label", "Low")
                .put("value", JSONObject().put("type", "number").put("value", daily.getJSONArray("temperature_2m_min").getDouble(0)).put("unit", unit))
                .put("detail", "").put("url", "").put("image", "").put("icon", ""))
        val items = JSONArray()
        val times = daily.getJSONArray("time")
        val high = daily.getJSONArray("temperature_2m_max")
        val low = daily.getJSONArray("temperature_2m_min")
        val codes = daily.getJSONArray("weather_code")
        val dayLabel = DateTimeFormatter.ofPattern("EEE", context.resources.configuration.locales[0])
        for (i in 0 until times.length()) {
            val day = Instant.ofEpochSecond(times.getLong(i)).atZone(ZoneId.of(zone)).toLocalDate()
            items.put(JSONObject().put("id", "day-" + day).put("label", day.format(dayLabel))
                .put("value", JSONObject().put("type", "number").put("value", high.getDouble(i)).put("unit", unit))
                .put("detail", "↓ " + low.getDouble(i) + unit).put("url", target).put("image", "").put("icon", conditionIcon(codes.getInt(i))))
        }
        val forecast = block("forecast", "collection", "", "", JSONObject().put("type", "text").put("value", JSONObject.NULL))
            .put("items", items).put("config", JSONObject().put("layout", "horizontal").put("size", "full_size"))
        val updated = block("observed", "metric", "As of", "Open-Meteo · " + zone,
            JSONObject().put("type", "instant").put("value", Instant.ofEpochMilli(observed).toString()).put("timeZone", zone))
            .put("config", JSONObject().put("emphasis", "inline").put("size", "full_size"))
        return base(target, digest(response.body), now + 10 * 60_000L).put("sourceUpdatedAt", observed).put("quality", "weather-model")
            .put("title", "Weather").put("text", description).put("blocks", JSONArray().put(metric).put(forecast).put(updated))
            .put("coordinateSource", if (coordinate == "device") "authorized-device-location" else "explicit-coordinate")
    }

    private fun feed(resource: JSONObject): JSONObject {
        val target = resource.getString("resourceId")
        val response = fetch(target)
        val xml = Xml.newPullParser()
        xml.setInput(StringReader(response.body))
        val items = JSONArray()
        val seen = HashSet<String>()
        var title = ""
        var link = ""
        var guid = ""
        var published = ""
        var description = ""
        var field = ""
        var feedTitle = ""
        var entry = false
        val content = StringBuilder()
        var event = xml.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = xml.name
                if (xml.depth == 1 && !java.util.Set.of("rss", "feed", "RDF").contains(name)) throw IOException("SOURCE_NOT_A_FEED")
                if (name == "item" || name == "entry") {
                    entry = true
                    title = ""
                    link = ""
                    guid = ""
                    published = ""
                    description = ""
                }
                if (entry && FEED_FIELDS.contains(name)) {
                    field = name
                    content.setLength(0)
                    if (name == "link") {
                        val href = xml.getAttributeValue(null, "href")
                        val rel = xml.getAttributeValue(null, "rel")
                        if (href != null && (rel == null || rel == "alternate")) link = href
                    }
                } else if (!entry && name == "title") {
                    field = "feedTitle"
                    content.setLength(0)
                }
            } else if (event == XmlPullParser.TEXT) {
                if (field.isNotEmpty()) content.append(xml.text)
            } else if (event == XmlPullParser.END_TAG) {
                val name = xml.name
                val text = content.toString().trim { it <= ' ' }
                if (name == field || field == "feedTitle" && name == "title") {
                    when (field) {
                        "title" -> title = text
                        "link" -> if (text.isNotEmpty()) link = text
                        "guid", "id" -> guid = text
                        "pubDate", "published" -> published = text
                        "updated" -> if (published.isEmpty()) published = text
                        "description", "summary", "content" -> description = text
                        "feedTitle" -> feedTitle = text
                    }
                    field = ""
                }
                if (name == "item" || name == "entry") {
                    entry = false
                    if (title.isEmpty() || link.isEmpty()) {
                        event = xml.next()
                        continue
                    }
                    val resolved = URL(URL(target), link)
                    link = resolved.toString()
                    if (resolved.protocol != "https" && resolved.protocol != "http") {
                        event = xml.next()
                        continue
                    }
                    val stable = if (guid.isEmpty()) link else guid
                    if (!seen.add(stable) || items.length() >= 20) {
                        event = xml.next()
                        continue
                    }
                    val id = "entry-" + digest(stable)
                    var value = JSONObject().put("type", "text").put("value", if (published.isEmpty()) JSONObject.NULL else clip(published, 200))
                    try {
                        val time = try { Instant.parse(published) } catch (_: Exception) {
                            ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                        }
                        value = JSONObject().put("type", "instant").put("value", time.toString()).put("timeZone", "UTC")
                    } catch (_: Exception) { }
                    items.put(JSONObject().put("id", id).put("label", clip(title, 80)).put("value", value)
                        .put("detail", clip(WHITESPACE.matcher(android.text.Html.fromHtml(description,
                            android.text.Html.FROM_HTML_MODE_LEGACY).toString()).replaceAll(" "), 200))
                        .put("url", link).put("image", "").put("icon", ""))
                }
            }
            event = xml.next()
        }
        val collection = block("entries", "collection", clip(feedTitle, 80), if (items.length() == 0) "No updates" else "",
            JSONObject().put("type", "text").put("value", JSONObject.NULL)).put("items", items)
        return base(target, digest(response.body), System.currentTimeMillis() + 15 * 60_000L).put("sourceUpdatedAt", response.modified)
            .put("quality", "published-feed").put("title", clip(feedTitle, 80)).put("text", items.length().toString() + " published entries")
            .put("blocks", JSONArray().put(collection)).put("coverageComplete", false).put("coverage", "published-feed-window").put("entryCount", seen.size)
    }

    companion object {
        private val FEED_FIELDS = java.util.Set.of("title", "link", "guid", "id", "pubDate", "published", "updated", "description", "summary", "content")
        private val WHITESPACE = Pattern.compile("\\s+")
        private val refreshes = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "matter-public-sources").apply { isDaemon = true }
        }

        @JvmStatic
        fun supports(kind: String): Boolean = kind == "weather" || kind == "feed"

        @JvmStatic
        @Throws(Exception::class)
        fun normalize(kind: String, resource: String): String {
            if (kind == "feed") {
                if (resource == "figma" || resource == "https://www.figma.com/blog/feed/atom.xml" || resource == "https://figma.com/blog/feed/atom.xml")
                    return "https://www.figma.com/blog/feed/atom.xml"
                throw IOException("ONLY_FIGMA_FEED_SUPPORTED")
            }
            if (resource == "device") return resource
            val values = (resource as java.lang.String).split(",", -1)
            if (values.size != 2) throw IOException("COORDINATES_REQUIRED")
            val latitude = java.lang.Double.parseDouble(values[0].trim { it <= ' ' })
            val longitude = java.lang.Double.parseDouble(values[1].trim { it <= ' ' })
            if (!java.lang.Double.isFinite(latitude) || !java.lang.Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
                throw IOException("INVALID_COORDINATES")
            return java.lang.Double.toString(if (latitude == 0.0) 0.0 else latitude) + "," +
                java.lang.Double.toString(if (longitude == 0.0) 0.0 else longitude)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun identity(kind: String): JSONObject = JSONObject().put("kind", if (kind == "weather") "weather-model" else "public-feed")
            .put("authority", if (kind == "weather") "api.open-meteo.com" else "www.figma.com").put("packageName", "org.agentos.assistant")

        private fun fetch(target: String): Response {
            val url = URL(target)
            if (url.protocol != "https" || url.userInfo != null) throw IOException("HTTPS_SOURCE_REQUIRED")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "TakuOS-Matter/1")
            connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/atom+xml, application/xml, text/xml")
            try {
                val status = connection.responseCode
                if (status != 200) throw Failure(status, connection.getHeaderField("Retry-After"))
                connection.inputStream.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > 1_048_576) throw IOException("SOURCE_TOO_LARGE")
                            output.write(buffer, 0, count)
                        }
                        return Response(output.toString(StandardCharsets.UTF_8), connection.lastModified)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun conditionIcon(code: Int): String = when (code) {
            0 -> "sun"
            1, 2 -> "partly-cloudy"
            3 -> "cloud"
            45, 48 -> "fog"
            71, 73, 75, 77, 85, 86 -> "snow"
            95, 96, 99 -> "storm"
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
            else -> ""
        }

        @JvmStatic
        @Throws(Exception::class)
        fun block(id: String, kind: String, title: String, text: String, value: JSONObject): JSONObject =
            JSONObject().put("id", id).put("kind", kind).put("title", title).put("text", text).put("value", value)
                .put("image", "").put("url", "").put("items", JSONArray()).put("config", JSONObject())

        private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max - 1) + "…"

        private fun digest(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
            val output = CharArray(24)
            val hex = "0123456789abcdef"
            for (i in 0 until 12) {
                val value = bytes[i].toInt() and 255
                output[2 * i] = hex[value ushr 4]
                output[2 * i + 1] = hex[value and 15]
            }
            return String(output)
        }

        private fun condition(code: Int): String = when (code) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 65, 66, 67 -> "Rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Weather code " + code
        }
    }
}
