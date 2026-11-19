package org.agentos.assistant

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import org.json.JSONObject

internal object AssistantAccounts {
    private const val MAX_KEY_BYTES = 16_384

    @JvmStatic
    fun open(context: Context, page: String) {
        if (page !in setOf("account", "byok", "magpie")) throw IOException("INVALID_SETTINGS_PAGE")
        context.startActivity(Intent(context, AssistantAuthorizationActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JvmStatic
    fun accountView(): JSONObject = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress("taku-agent-account"))
        if (socket.peerCredentials.uid != 0) throw IOException("ACCOUNT_UNAVAILABLE")
        socket.soTimeout = 3000
        val bytes = socket.inputStream.readNBytes(32_769)
        if (bytes.size > 32_768) throw IOException("ACCOUNT_UNAVAILABLE")
        JSONObject(bytes.toString(Charsets.UTF_8))
    }

    @JvmStatic
    fun command(context: Context, method: String, input: JSONObject, extras: Bundle?): JSONObject = when (method) {
        "accounts/read" -> { PanelProtocol.exact(input, emptySet()); status() }
        "accounts/refresh" -> { PanelProtocol.exact(input, emptySet()); refresh(); status() }
        "accounts/login" -> { PanelProtocol.exact(input, setOf("type")); login(input.getString("type")); status() }
        "accounts/cancel" -> { PanelProtocol.exact(input, emptySet()); cancel(); status() }
        "accounts/open" -> { PanelProtocol.exact(input, setOf("page")); open(context, input.getString("page")); JSONObject().put("opened", true) }
        "accounts/keys" -> { PanelProtocol.exact(input, emptySet()); keys() }
        "accounts/removeKey" -> { PanelProtocol.exact(input, setOf("id", "ref")); removeKey(input.getString("id"), input.getString("ref")) }
        "accounts/saveKey" -> {
            PanelProtocol.exact(input, setOf("id", "ref"))
            val id = if (input.has("id")) input.getString("id") else ""
            val ref = if (input.has("ref")) input.getString("ref") else ""
            val descriptor = extras?.getParcelable("credential", ParcelFileDescriptor::class.java)
                ?: throw IOException("KEY_SOURCE_REQUIRED")
            val key = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                val mode = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
                if (mode != OsConstants.O_RDONLY) throw IOException("KEY_SOURCE_NOT_READ_ONLY")
                val stat = Os.fstat(descriptor.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException("KEY_SOURCE_NOT_REGULAR")
                if (stat.st_size > MAX_KEY_BYTES) throw IOException("KEY_TOO_LARGE")
                val bytes = stream.readNBytes(MAX_KEY_BYTES + 1)
                try {
                    if (bytes.size > MAX_KEY_BYTES) throw IOException("KEY_TOO_LARGE")
                    bytes.toString(Charsets.UTF_8)
                } finally { bytes.fill(0) }
            }
            saveKey(key, id, ref)
        }
        else -> throw IOException("UNKNOWN_METHOD")
    }

    fun refresh() { TaskRequests.control("account/read", JSONObject()) }

    fun login(type: String) {
        if (type != "chatgpt" && type != "chatgptDeviceCode") throw IOException("INVALID_ACCOUNT_TYPE")
        try { TaskRequests.control("account/login/start", JSONObject().put("type", type)) }
        catch (_: Exception) { throw IOException("ACCOUNT_LOGIN_FAILED") }
    }

    fun cancel() {
        try { TaskRequests.control("account/login/cancel", JSONObject()) }
        catch (_: Exception) { throw IOException("ACCOUNT_CANCEL_FAILED") }
    }

    private fun status(): JSONObject {
        val view = accountView()
        return JSONObject().put("connected", view.getBoolean("connected")).put("phase", view.optString("phase"))
            .put("kind", view.optString("kind")).put("plan", view.optString("plan"))
    }

    fun keys(): JSONObject = byok(JSONObject().put("action", "read"))

    fun saveKey(value: String, id: String = "", ref: String = ""): JSONObject {
        validateKeyRef(id, ref, required = false)
        val key = value.trim()
        if (key.length !in 1..4096 || key.any { it.isWhitespace() || it.isISOControl() || it == ',' })
            throw IOException("INVALID_CREDENTIAL")
        val input = JSONObject().put("action", "save").put("id", id).put("ref", ref).put("key", key)
        try { return byok(input) } finally { input.remove("key") }
    }

    fun removeKey(id: String, ref: String): JSONObject {
        validateKeyRef(id, ref, required = true)
        return byok(JSONObject().put("action", "remove").put("id", id).put("ref", ref))
    }

    private fun validateKeyRef(id: String, ref: String, required: Boolean) {
        if ((!required && id.isEmpty() && ref.isEmpty())) return
        if (id.isBlank() || id.length > 256 || ref.isBlank() || ref.length > 256 ||
            id.any(Char::isISOControl) || ref.any(Char::isISOControl)) throw IOException("INVALID_KEY_REFERENCE")
    }

    private fun byok(input: JSONObject): JSONObject {
        val result = try { NativeClient.exchange(input.put("op", "byok").put("provider", "openrouter")) }
        catch (_: Exception) { throw IOException("KEY_OPERATION_FAILED") }
        return result
    }

    fun magpieManagement(): Uri {
        val result = NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "manage"))
        val uri = Uri.parse(result.getString("url"))
        if (uri.scheme != "http" || uri.encodedAuthority != "127.0.0.1:3430" || uri.path != "/" ||
            uri.fragment != null || uri.encodedQuery?.matches(Regex("k=[a-f0-9]{64}")) != true)
            throw IOException("MAGPIE_MANAGEMENT_UNAVAILABLE")
        return uri
    }

    fun officialPage(state: JSONObject): Uri {
        if (state.optString("phase") != "pending") throw IOException("ACCOUNT_LOGIN_UNAVAILABLE")
        val uri = Uri.parse(state.optString("url"))
        if (uri.scheme != "https" || uri.encodedAuthority !in setOf("auth.openai.com", "chatgpt.com"))
            throw IOException("INVALID_AUTHORIZATION_URL")
        return uri
    }
}
