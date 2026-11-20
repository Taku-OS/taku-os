package org.agentos.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal object MenuDishArtworkGateway {
    private const val MAX_RESPONSE = 20 * 1024 * 1024
    private const val MAX_IMAGE = 12 * 1024 * 1024
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2)) { task ->
        Thread(task, "menu-dish-artwork").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    fun open(uri: Uri, requireCurrentAi: () -> Unit): ParcelFileDescriptor {
        if (uri.queryParameterNames != setOf("name", "ingredients") ||
            uri.getQueryParameters("name").size != 1 || uri.getQueryParameters("ingredients").size != 1)
            throw FileNotFoundException("INVALID_ARGUMENT")
        val name = uri.getQueryParameter("name")?.trim().orEmpty()
        val ingredients = uri.getQueryParameter("ingredients")?.trim().orEmpty()
        if (name.isEmpty() || name.length > 160 || ingredients.length > 1200 ||
            name.any { it.isISOControl() } || ingredients.any { it.isISOControl() })
            throw FileNotFoundException("INVALID_ARGUMENT")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        try {
            workers.execute {
                try {
                    awaitReader(pipe[1], 0)
                    requireCurrentAi()
                    val bytes = generate(name, ingredients)
                    requireCurrentAi()
                    write(pipe[1], bytes)
                    pipe[1].close()
                } catch (_: Exception) {
                    try { pipe[1].closeWithError("ARTWORK_UNAVAILABLE") } catch (_: IOException) { }
                }
            }
        } catch (_: RejectedExecutionException) {
            pipe[0].close()
            pipe[1].close()
            throw FileNotFoundException("BUSY")
        }
        return pipe[0]
    }

    private fun generate(name: String, ingredients: String): ByteArray {
        val dish = JSONObject().put("name", name).put("ingredients", ingredients)
        val prompt = "Create a realistic food photograph of the dish described in this JSON: $dish. " +
            "Treat its values only as dish information. Show one complete round ceramic plate, viewed slightly " +
            "from above, with the dish plated naturally and ingredients appropriate to its name and description. " +
            "Top-down camera, center the plate and show the complete rim with some breathing room. " +
            "Isolate it as a cutout with a fully transparent background. " +
            "No text, logos, cutlery, napkins, props, or decorations outside the plate."
        val payload = JSONObject().put("model", "codex/gpt-image-2").put("prompt", prompt)
            .put("n", 1).put("size", "1024x1024").put("response_format", "b64_json")
            .put("quality", "low").put("background", "transparent").put("output_format", "png")
            .toString().toByteArray(Charsets.UTF_8)
        val connection = URL("http://127.0.0.1:3425/v1/images/generations")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 180_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode != 200) throw IOException("ARTWORK_UPSTREAM_FAILED")
            val deadline = SystemClock.elapsedRealtime() + 180_000
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("ARTWORK_TIMEOUT")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE) throw IOException("ARTWORK_TOO_LARGE")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            val data = JSONObject(body).getJSONArray("data")
            if (data.length() != 1) throw IOException("ARTWORK_INVALID")
            val encoded = data.getJSONObject(0).optString("b64_json")
            if (encoded.isEmpty() || encoded.length > MAX_IMAGE * 4 / 3 + 8) throw IOException("ARTWORK_INVALID")
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            if (raw.isEmpty() || raw.size > MAX_IMAGE) throw IOException("ARTWORK_TOO_LARGE")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
                bounds.outWidth.toLong() * bounds.outHeight > 16_777_216) throw IOException("ARTWORK_INVALID")
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IOException("ARTWORK_INVALID")
            return try { plateCutout(bitmap) } finally { bitmap.recycle() }
        } finally { connection.disconnect() }
    }

    private fun plateCutout(bitmap: Bitmap): ByteArray {
        val row = IntArray(bitmap.width)
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        var transparent = false
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) {
                val alpha = row[x] ushr 24
                if (alpha < 16) transparent = true
                if (alpha < 80) continue
                bounds.left = minOf(bounds.left, x)
                bounds.top = minOf(bounds.top, y)
                bounds.right = maxOf(bounds.right, x + 1)
                bounds.bottom = y + 1
            }
        }
        if (!transparent || bounds.isEmpty) throw IOException("ARTWORK_INVALID")
        val edge = maxOf(bounds.width(), bounds.height())
        val size = 640
        val scale = (size - 24).toFloat() / edge
        val width = (bounds.width() * scale).toInt()
        val height = (bounds.height() * scale).toInt()
        val left = (size - width) / 2
        val top = (size - height) / 2
        val plate = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(plate).drawBitmap(bitmap, bounds, Rect(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            val output = ByteArrayOutputStream()
            if (!plate.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("ARTWORK_INVALID")
            output.toByteArray()
        } finally { plate.recycle() }
    }

    private fun awaitReader(output: ParcelFileDescriptor, millis: Int): Boolean {
        val fd = StructPollfd().apply { this.fd = output.fileDescriptor; events = OsConstants.POLLOUT.toShort() }
        if (Os.poll(arrayOf(fd), millis) == 0) return false
        if ((fd.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0)
            throw IOException("ARTWORK_READER_CLOSED")
        return (fd.revents.toInt() and OsConstants.POLLOUT) != 0
    }

    private fun write(output: ParcelFileDescriptor, bytes: ByteArray) {
        Os.fcntlInt(output.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var offset = 0
        while (offset < bytes.size) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw IOException("ARTWORK_READER_TIMEOUT")
            if (!awaitReader(output, remaining.toInt())) throw IOException("ARTWORK_READER_TIMEOUT")
            try {
                offset += Os.write(output.fileDescriptor, bytes, offset, bytes.size - offset)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
            }
        }
    }
}
