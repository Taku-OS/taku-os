package org.agentos.assistant

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/** Durable receipts outlive bounded SQLite state and command history. */
internal class MatterReceiptHistory(private val root: File) {
    @Throws(Exception::class)
    fun command(id: String): JSONObject? = read(file("matter", id))

    @Throws(Exception::class)
    fun cardView(id: String): JSONObject? = read(file("card-view", id))

    fun hasCardView(id: String): Boolean = file("card-view", id).isFile

    @Throws(Exception::class)
    fun archiveCommand(command: JSONObject) {
        if (!command.optBoolean("terminal")) throw IllegalStateException("Unresolved command cannot become history")
        archive(file("matter", command.getString("id")), command)
    }

    @Throws(Exception::class)
    fun archiveCardView(id: String, receipt: JSONObject) = archive(file("card-view", id), receipt)

    private fun file(kind: String, id: String): File {
        AgentGatewayProtocol.requireId(JSONObject().put("requestId", id), "requestId")
        return File(root, "$kind-$id.json")
    }

    private fun read(file: File): JSONObject? =
        if (file.isFile) JSONObject(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) else null

    private fun archive(file: File, receipt: JSONObject) {
        val created = !root.isDirectory
        if (created && !root.mkdirs() && !root.isDirectory) throw IOException("RECEIPT_STORAGE_UNAVAILABLE")
        TaskRequests.write(file, receipt.toString())
        // The live SQL fence may be removed only after both the file and its directory entry are durable.
        FileChannel.open(root.toPath(), StandardOpenOption.READ).use { directory -> directory.force(true) }
        if (created) FileChannel.open(root.parentFile!!.toPath(), StandardOpenOption.READ).use { parent -> parent.force(true) }
    }
}
