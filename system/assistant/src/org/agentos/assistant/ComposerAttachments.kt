package org.agentos.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

internal object ComposerAttachments {
    private const val LIMIT = 20_000_000L

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
