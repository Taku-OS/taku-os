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

    /** Opens the upstream Magpie console; its one-time access link never leaves this process. */
    @JvmStatic
    fun openMagpie(context: Context) {
        val uri = try { magpieManagement() } catch (_: Exception) { throw IOException("MAGPIE_MANAGEMENT_UNAVAILABLE") }
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { throw IOException("BROWSER_UNAVAILABLE") }
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

    /**
     * [challenge] says whether this caller may read a pending sign-in's one-time code and
     * authorization page: the settings screen and the on-device Agent, never a third-party app.
     */
    @JvmStatic
    fun command(context: Context, method: String, input: JSONObject, extras: Bundle?, challenge: Boolean): JSONObject = when (method) {
        "accounts/read" -> { PanelProtocol.exact(input, emptySet()); status(challenge) }
        "accounts/refresh" -> { PanelProtocol.exact(input, emptySet()); refresh(); status(challenge) }
        "accounts/login" -> { PanelProtocol.exact(input, setOf("type")); login(input.getString("type")); status(challenge) }
        "accounts/cancel" -> { PanelProtocol.exact(input, emptySet()); cancel(); status(challenge) }
        "accounts/openMagpie" -> { PanelProtocol.exact(input, emptySet()); openMagpie(context); JSONObject().put("opened", true) }
        "accounts/keys" -> { PanelProtocol.exact(input, emptySet()); keys() }
        "accounts/removeKey" -> { PanelProtocol.exact(input, setOf("id", "ref")); removeKey(input.getString("id"), input.getString("ref")) }
        "accounts/saveKey" -> {
            PanelProtocol.exact(input, setOf("id", "ref", "key"))
            val id = if (input.has("id")) input.getString("id") else ""
            val ref = if (input.has("ref")) input.getString("ref") else ""
            saveKey(secret(input, "key", extras), id, ref)
        }
        // Gemini through Magpie's agy provider; see AgyGemini in the broker.
        "accounts/agyRead" -> { PanelProtocol.exact(input, emptySet()); agy(JSONObject().put("action", "read"), challenge) }
        "accounts/agyRefresh" -> { PanelProtocol.exact(input, emptySet()); agy(JSONObject().put("action", "refresh"), challenge) }
        "accounts/agySignIn" -> { PanelProtocol.exact(input, emptySet()); agy(JSONObject().put("action", "signIn"), challenge) }
        "accounts/agyCancel" -> { PanelProtocol.exact(input, emptySet()); agy(JSONObject().put("action", "signInCancel"), challenge) }
        "accounts/agyCode" -> {
            PanelProtocol.exact(input, setOf("code"))
            val request = JSONObject().put("action", "signInCode").put("code", secret(input, "code", extras))
            try { agy(request, challenge) } finally { request.remove("code") }
        }
        "accounts/agyUse" -> {
            PanelProtocol.exact(input, setOf("model"))
            val model = if (input.has("model")) input.getString("model") else ""
            if (model.isNotEmpty() && !model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) throw IOException("INVALID_ARGUMENT")
            agy(JSONObject().put("action", "use").put("model", model), challenge)
        }
        else -> throw IOException("UNKNOWN_METHOD")
    }

    /** Known failure codes of the broker's agyGemini op; anything else is reported generically. */
    private val agyErrors = setOf("AGY_NOT_INSTALLED", "AGY_SIGN_IN_REQUIRED", "AGY_STATUS_CHECKING", "AGY_UNAVAILABLE",
        "AGY_MODEL_UNAVAILABLE", "MAGPIE_AGY_UNSUPPORTED", "SIGN_IN_NOT_PENDING", "SECRET_EMPTY", "INVALID_CREDENTIAL",
        "INVALID_ARGUMENT", "SETTINGS_CHANGED")

    /**
     * The Gemini (agy) view. While a sign-in waits for its code, a caller allowed the [challenge]
     * also gets Google's sign-in page (accounts.google.com only); others see the phase alone.
     */
    private fun agy(request: JSONObject, challenge: Boolean): JSONObject {
        val view = try { NativeClient.exchange(request.put("op", "agyGemini")) }
        catch (problem: Exception) {
            val code = problem.message.orEmpty()
            throw IOException(agyErrors.firstOrNull { code.contains(it) } ?: when {
                code.contains("Tasks are still running") -> "TASKS_RUNNING"
                else -> "AGY_OPERATION_FAILED"
            })
        }
        val signIn = view.optJSONObject("signIn") ?: JSONObject()
        val url = signIn.optString("url")
        signIn.put("url", if (challenge && signIn.optString("phase") == "pending" && googleSignIn(url)) url else "")
        return view.put("signIn", signIn)
    }

    private fun googleSignIn(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.encodedAuthority == "accounts.google.com" && uri.encodedUserInfo == null
    }

    /**
     * A secret supplied either as a regular read-only UTF-8 file descriptor in
     * `extras["credential"]`, which keeps it out of the caller's argument string, or in [field].
     */
    @JvmStatic
    fun secret(input: JSONObject, field: String, extras: Bundle?): String {
        val supplied = credentialText(extras)
        return when {
            input.has(field) -> {
                if (supplied != null) throw IOException("KEY_SOURCE_AMBIGUOUS")
                input.remove(field) as? String ?: throw IOException("INVALID_CREDENTIAL")
            }
            else -> supplied ?: throw IOException("KEY_SOURCE_REQUIRED")
        }
    }

    /** The UTF-8 text of the descriptor in `extras["credential"]`, or null when there is none. */
    fun credentialText(extras: Bundle?): String? =
        extras?.getParcelable("credential", ParcelFileDescriptor::class.java)?.let(::readKey)

    private fun readKey(descriptor: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
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

    /**
     * The sign-in state the settings screen shows. While a sign-in is pending, a caller allowed
     * the [challenge] also gets the one-time device code and the official authorization page;
     * both are empty otherwise.
     */
    private fun status(challenge: Boolean): JSONObject {
        val view = accountView()
        val pending = challenge && view.optString("phase") == "pending"
        val page = if (pending) runCatching { officialPage(view).toString() }.getOrDefault("") else ""
        return JSONObject().put("connected", view.getBoolean("connected")).put("phase", view.optString("phase"))
            .put("kind", view.optString("kind")).put("plan", view.optString("plan"))
            .put("code", if (pending) view.optString("code") else "").put("url", page)
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

    private fun magpieManagement(): Uri {
        val result = NativeClient.exchange(JSONObject().put("op", "modelConnection").put("action", "manage"))
        val uri = Uri.parse(result.getString("url"))
        if (uri.scheme != "http" || uri.encodedAuthority != "127.0.0.1:3430" || uri.path != "/" ||
            uri.fragment != null || uri.encodedQuery?.matches(Regex("k=[a-f0-9]{64}")) != true)
            throw IOException("MAGPIE_MANAGEMENT_UNAVAILABLE")
        return uri
    }

    private fun officialPage(state: JSONObject): Uri {
        if (state.optString("phase") != "pending") throw IOException("ACCOUNT_LOGIN_UNAVAILABLE")
        val uri = Uri.parse(state.optString("url"))
        if (uri.scheme != "https" || uri.encodedAuthority !in setOf("auth.openai.com", "chatgpt.com"))
            throw IOException("INVALID_AUTHORIZATION_URL")
        return uri
    }
}
