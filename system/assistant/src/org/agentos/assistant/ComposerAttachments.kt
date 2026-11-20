package org.agentos.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

internal object ComposerAttachments {
    private const val LIMIT = 20_000_000L
    private const val THUMBNAIL_PX = 256
    private const val UNREFERENCED_MS = 7L * 24 * 60 * 60 * 1000
    /** An attachment path as stored in preferences; JSON text may escape the slash. */
    private val STORED_NAME = Regex("""composer-attachments\\?/([0-9a-fA-F-]{36})""")

    @JvmStatic
    fun attach(context: Context, input: JSONObject, extras: Bundle?, uid: Int, pid: Int): JSONObject {
        val supplied = extras?.getParcelable("attachment", ParcelFileDescriptor::class.java)
        val uri = input.optString("uri")
        val descriptor = if (supplied == null) {
            val source = Uri.parse(uri)
            if (source.scheme != "content" || source.authority.isNullOrEmpty() ||
                context.checkUriPermission(source, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw IOException("ATTACHMENT_ACCESS_DENIED")
            try { context.contentResolver.openFileDescriptor(source, "r") }
            catch (_: Exception) { throw IOException("ATTACHMENT_UNAVAILABLE") }
        } else if (uri.isNotEmpty()) {
            supplied.close()
            throw IOException("INVALID_ARGUMENT")
        } else supplied
        return attachFd(context, input, descriptor ?: throw IOException("ATTACHMENT_UNAVAILABLE"))
    }

    /**
     * Deletes copied attachment files that no draft, saved draft or kept request refers to any
     * more. Removing an attachment or finishing a send only drops the reference, so without this
     * the files (up to 20 MB each) would stay for good. Files stay a week after their last
     * reference so a queued or failed task that still names one can use or restore it.
     */
    @JvmStatic
    fun sweep(context: Context) {
        val files = File(context.filesDir, "composer-attachments").listFiles() ?: return
        val cutoff = System.currentTimeMillis() - UNREFERENCED_MS
        val stale = files.filter { it.isFile && !it.name.startsWith("screen-") && it.lastModified() < cutoff }
        if (stale.isEmpty()) return
        val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
        // The lock composer state changes under, so a file is never judged while it is being referenced.
        synchronized(ComposerPreferences::class.java) {
            val referenced = HashSet<String>()
            for (value in prefs.all.values)
                if (value is String && value.contains("composer-attachments"))
                    STORED_NAME.findAll(value).forEach { referenced.add(it.groupValues[1]) }
            for (file in stale) if (file.name !in referenced) file.delete()
        }
    }

    /** A small JPEG of an image attached to [thread]'s draft, for the composer's attachment strip. */
    @JvmStatic
    fun thumbnail(context: Context, thread: String, id: String): ByteArray {
        if (thread.isNotEmpty() && !AgentGatewayProtocol.isUuid(thread)) throw IOException("INVALID_ARGUMENT")
        val items = ComposerPreferences.attachments(context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE), thread)
        val item = (0 until items.length()).map(items::getJSONObject).firstOrNull { ComposerState.attachmentId(it) == id }
            ?: throw IOException("ATTACHMENT_UNAVAILABLE")
        if (!item.optString("mime").startsWith("image/")) throw IOException("ATTACHMENT_UNAVAILABLE")
        val path = item.getString("path")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0 || longest > 32_768) throw IOException("ATTACHMENT_UNAVAILABLE")
        var sample = 1
        while (longest / sample > THUMBNAIL_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("ATTACHMENT_UNAVAILABLE")
        try {
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    @JvmStatic
    fun attachFd(context: Context, input: JSONObject, descriptor: ParcelFileDescriptor): JSONObject {
        val file = File(File(context.filesDir, "composer-attachments"), UUID.randomUUID().toString())
        var saved = false
        try {
            var size = 0L
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                val access = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
                if (access != OsConstants.O_RDONLY) throw IOException("ATTACHMENT_ACCESS_DENIED")
                if (descriptor.statSize > LIMIT) throw IOException("ATTACHMENT_TOO_LARGE")
                val directory = file.parentFile!!
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("DRAFT_SAVE_FAILED")
                FileOutputStream(file).use { output ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        val count = stream.read(bytes, 0, minOf(bytes.size.toLong(), LIMIT - size + 1).toInt())
                        if (count < 0) break
                        size += count
                        if (size > LIMIT) throw IOException("ATTACHMENT_TOO_LARGE")
                        output.write(bytes, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (size == 0L) throw IOException("ATTACHMENT_UNAVAILABLE")
            val item = JSONObject().put("path", file.path).put("name", input.getString("name"))
                .put("mime", input.getString("mime")).put("size", size)
            val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
            val updated = ComposerState.addAttachment(prefs, input.getString("threadId"), input.getString("expectedRevision"), item)
            saved = true
            return updated
        } finally {
            if (!saved) file.delete()
        }
    }
}
