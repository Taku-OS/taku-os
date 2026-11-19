package org.agentos.lab

import java.io.IOException
import java.nio.channels.Channels
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object WorkspaceTextFiles {
    private val root = Paths.get("/data/local")
    private val workspace = listOf("agentos", "work")
    private const val maxBytes = 128 * 1024
    private const val maxEntries = 500
    private val extensions = setOf("txt", "md", "csv", "tsv", "log")

    @JvmStatic
    fun control(input: JSONObject): JSONObject {
        val action = input.getString("action")
        val relative = input.optString("path")
        if (relative.length > 512) throw IOException("INVALID_FILE_PATH")
        val parts = if (relative.isEmpty() && action == "list") emptyList() else relative.split('/')
        if (parts.any { !visibleName(it) }) throw IOException("INVALID_FILE_PATH")
        return try {
            Files.newDirectoryStream(root).use { stream ->
                val directory = stream as? SecureDirectoryStream<Path> ?: throw IOException("FILES_UNAVAILABLE")
                val folders = if (action == "list") parts else parts.dropLast(1)
                inside(directory, workspace + folders, 0) { parent ->
                    when (action) {
                        "list" -> listing(parent)
                        "read" -> JSONObject().put("path", relative).put("text", read(parent, fileName(parts.last())))
                        "write" -> {
                            val value = input.getString("text")
                            if (value.length > 32_000 || '\u0000' in value) throw IOException("INVALID_FILE_TEXT")
                            write(parent, fileName(parts.last()), value.toByteArray(Charsets.UTF_8))
                            JSONObject().put("path", relative).put("saved", true)
                        }
                        else -> throw IOException("UNSUPPORTED_FILE_OPERATION")
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            throw IOException("FILE_NOT_FOUND")
        } catch (failure: IOException) {
            if (failure.message?.matches(Regex("[A-Z_]+")) == true) throw failure
            throw IOException("FILES_UNAVAILABLE")
        }
    }

    private fun <T> inside(
        directory: SecureDirectoryStream<Path>, parts: List<String>, index: Int,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T {
        if (index == parts.size) return action(directory)
        return directory.newDirectoryStream(Paths.get(parts[index]), NOFOLLOW_LINKS).use { next ->
            inside(next, parts, index + 1, action)
        }
    }

    private fun listing(directory: SecureDirectoryStream<Path>): JSONObject {
        val entries = ArrayList<Pair<String, Boolean>>()
        var truncated = false
        for (candidate in directory) {
            val name = candidate.fileName.toString()
            if (!visibleName(name)) continue
            val metadata = try { attributes(directory, candidate.fileName) } catch (_: NoSuchFileException) { continue }
            if (metadata.isDirectory || (metadata.isRegularFile && textFile(name))) {
                if (entries.size == maxEntries) { truncated = true; break }
                entries.add(name to metadata.isDirectory)
            }
        }
        entries.sortWith(compareBy<Pair<String, Boolean>> { !it.second }.thenBy { it.first })
        val rows = JSONArray()
        for ((name, folder) in entries) rows.put(JSONObject().put("name", name).put("directory", folder))
        return JSONObject().put("entries", rows).put("truncated", truncated)
    }

    private fun visibleName(name: String): Boolean = name.isNotEmpty() && name.length <= 120 && !name.startsWith('.') &&
        name.none { it.isISOControl() || it == '\\' }

    private fun textFile(name: String) = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in extensions

    private fun fileName(name: String): Path {
        if (!textFile(name)) throw IOException("UNSUPPORTED_FILE_TYPE")
        return Paths.get(name)
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path) =
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()

    private fun read(directory: SecureDirectoryStream<Path>, name: Path): String {
        if (!attributes(directory, name).isRegularFile) throw IOException("FILE_NOT_FOUND")
        val bytes = directory.newByteChannel(name, setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newInputStream(channel).readNBytes(maxBytes + 1)
        }
        if (bytes.size > maxBytes) throw IOException("FILE_TOO_LARGE")
        if (0.toByte() in bytes) throw IOException("UNSUPPORTED_FILE_TYPE")
        return try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { throw IOException("UNSUPPORTED_FILE_TYPE") }
    }

    private fun write(directory: SecureDirectoryStream<Path>, name: Path, bytes: ByteArray) {
        try {
            val existing = attributes(directory, name)
            if (!existing.isRegularFile) throw IOException("FILE_NOT_FOUND")
            if (existing.size() > maxBytes) throw IOException("FILE_TOO_LARGE")
        } catch (_: NoSuchFileException) { }
        val temp = Paths.get(".agent-management-${UUID.randomUUID()}.tmp")
        val access = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        try {
            directory.newByteChannel(temp, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS), access).use { channel ->
                Channels.newOutputStream(channel).write(bytes)
            }
            directory.move(temp, directory, name)
        } finally {
            try { directory.deleteFile(temp) } catch (_: NoSuchFileException) { }
        }
    }
}
