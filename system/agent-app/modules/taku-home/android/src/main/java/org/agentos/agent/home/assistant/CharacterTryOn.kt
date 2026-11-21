package org.agentos.agent.home.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taku.cosplay.api.CosplayContract

/** Main-thread owner of one gesture's reference. No independent microphone or language setting. */
internal class CharacterTryOn(
  private val context: Context,
  private val scope: CoroutineScope,
  private val eligible: (String) -> Boolean,
  private val unlocked: () -> Boolean,
  private val publish: (CharacterTryOnState?) -> Unit,
  private val fallback: (String) -> Unit,
  private val handedOff: () -> Unit,
) {
  private val client = CharacterTryOnClient(context)
  private var frame: Frame? = null
  private var attempt: Frame? = null
  private var state: CharacterTryOnState? = null
  private var ticket: String? = null
  private var expiry: Job? = null

  private class Frame(val gesture: String, val source: String, val invoked: Long, val captured: Long, val jpeg: ByteArray) {
    val requestId: String = UUID.randomUUID().toString().replace("-", "")
    fun erase() = jpeg.fill(0)
  }

  fun offer(gesture: String, source: String, invoked: Long, captured: Long, jpeg: ByteArray) {
    if (!eligible(gesture) || !unlocked() || CharacterTryOnPolicy.source(source) == null ||
        !CharacterTryOnPolicy.fresh(invoked, captured, SystemClock.elapsedRealtime()) || !CharacterTryOnPolicy.boundedJpeg(jpeg)) {
      jpeg.fill(0)
      return
    }
    frame?.erase()
    expiry?.cancel()
    val reference = Frame(gesture, source, invoked, captured, jpeg)
    frame = reference
    expiry = scope.launch {
      delay(CosplayContract.MAX_INVOCATION_AGE_MS - (SystemClock.elapsedRealtime() - invoked) + 1)
      if (frame === reference) { frame = null; reference.erase() }
      if (attempt === reference) fail("The reference expired. Invoke Taku again from the video.")
    }
  }

  /** Only final, once-delivered hold dictation reaches this method. Ordinary requests return false. */
  fun intercept(text: String): Boolean {
    val reference = frame ?: return false
    frame = null
    if (!CharacterTryOnPolicy.command(text) || !current(reference)) {
      reference.erase(); expiry?.cancel(); expiry = null
      return false
    }
    attempt = reference
    update(CharacterTryOnState(reference.requestId, CharacterTryOnPhase.Checking))
    scope.launch {
      val status = withContext(Dispatchers.IO) { client.status() }
      if (attempt !== reference) return@launch
      if (!current(reference)) { clear(); return@launch }
      if (!status.available || !status.enabled) {
        clear()
        fallback(text)
      } else update(CharacterTryOnState(reference.requestId, CharacterTryOnPhase.Confirming))
    }
    return true
  }

  fun confirm(requestId: String) {
    if (state?.requestId == requestId && state?.phase == CharacterTryOnPhase.Starting) return
    val reference = attempt
    if (reference == null || state?.requestId != requestId || state?.phase != CharacterTryOnPhase.Confirming)
      throw AssistantException("STALE_REQUEST", "There is no current character confirmation")
    if (!current(reference)) { fail("The reference expired. Invoke Taku again from the video."); return }
    update(CharacterTryOnState(reference.requestId, CharacterTryOnPhase.Starting))
    scope.launch {
      val prepared = try { withContext(Dispatchers.IO) { client.prepare(reference) } }
      catch (failure: Exception) {
        if (attempt === reference) fail((failure as? CharacterTryOnFailure)?.message ?: "Character preview could not open. Try again from the video.")
        return@launch
      }
      if (attempt !== reference || !current(reference)) {
        withContext(Dispatchers.IO) { client.cancel(prepared.ticket) }
        if (attempt === reference) fail("The reference expired. Invoke Taku again from the video.")
        return@launch
      }
      ticket = prepared.ticket
      try {
        if (!CharacterTryOnPolicy.ticketValid(prepared.ticket, prepared.expires, SystemClock.elapsedRealtime()))
          throw CharacterTryOnFailure("The preview request expired. Invoke Taku again from the video.")
        context.startActivity(Intent().setComponent(ComponentName(CosplayContract.TRYON_PACKAGE, CosplayContract.ACTIVITY))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(CosplayContract.EXTRA_TICKET, prepared.ticket))
        // The transparent Activity now owns the capability; closing the input must not revoke it.
        ticket = null
        clear()
        handedOff()
      } catch (_: Exception) { fail("Character preview could not open. Try again from the video.") }
    }
  }

  fun cancel(requestId: String) {
    if (state?.requestId != requestId) throw AssistantException("STALE_REQUEST", "There is no current character confirmation")
    clear()
  }

  fun clear() {
    expiry?.cancel(); expiry = null
    val staged = ticket
    ticket = null
    if (staged != null) scope.launch(Dispatchers.IO) { client.cancel(staged) }
    frame?.erase(); frame = null
    attempt?.erase(); attempt = null
    update(null)
  }

  private fun current(reference: Frame): Boolean = eligible(reference.gesture) && unlocked() &&
    CharacterTryOnPolicy.fresh(reference.invoked, reference.captured, SystemClock.elapsedRealtime())

  private fun fail(message: String) {
    val id = state?.requestId ?: return
    clear()
    update(CharacterTryOnState(id, CharacterTryOnPhase.Failed, message.take(256)))
  }

  private fun update(value: CharacterTryOnState?) { state = value; publish(value) }

  /** Fixed capability protocol. No free-form speech or credentials cross this boundary. */
  @Suppress("DEPRECATION") // Bundle.get is intentional: the wire contract requires exact field types.
  private class CharacterTryOnClient(private val context: Context) {
    data class Status(val available: Boolean = false, val enabled: Boolean = false)
    data class Prepared(val ticket: String, val expires: Long)
    private val uri = Uri.parse("content://${CosplayContract.AUTHORITY}")

    private fun requireIdentity() {
      val pm = context.packageManager
      val info = pm.getPackageInfo(CosplayContract.TRYON_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
      val signers = info.signingInfo?.apkContentsSigners
      if (signers == null || signers.size != 1) throw SecurityException("Unexpected TryOn signer")
      val hash = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
      if (pm.checkSignatures("android", CosplayContract.TRYON_PACKAGE) != PackageManager.SIGNATURE_MATCH && hash != CosplayContract.TRYON_CERT_SHA256)
        throw SecurityException("Unexpected TryOn signer")
      val provider = pm.resolveContentProvider(CosplayContract.AUTHORITY, 0)
      if (provider?.packageName != CosplayContract.TRYON_PACKAGE || provider.applicationInfo.packageName != CosplayContract.TRYON_PACKAGE ||
          provider.applicationInfo.uid != info.applicationInfo?.uid) throw SecurityException("Unexpected TryOn provider")
    }

    fun status(): Status = try {
      requireIdentity()
      val reply = context.contentResolver.call(uri, CosplayContract.STATUS, null, null)
      val fields = setOf("schema", "ok", "enabled", "ready", "keyConfigured", "permissionsReady", "userMessage", "voiceOnly")
      if (reply?.keySet() != fields || reply.get("schema") != CosplayContract.SCHEMA || reply.get("ok") != true ||
          listOf("enabled", "ready", "keyConfigured", "permissionsReady", "voiceOnly").any { reply.get(it) !is Boolean } ||
          reply.get("userMessage") !is String) Status()
      else Status(true, reply.getBoolean("enabled"))
    } catch (_: Exception) { Status() }

    fun prepare(reference: Frame): Prepared {
      requireIdentity()
      // Binder may still be copying when the user cancels; give it its own short-lived byte array.
      val bytes = reference.jpeg.copyOf()
      val payload = Bundle().apply {
        putString("schema", CosplayContract.SCHEMA); putByteArray("jpeg", bytes)
        putLong("capturedAtElapsedMs", reference.captured); putLong("invokedAtElapsedMs", reference.invoked)
        putString("command", CosplayContract.COMMAND); putString("sourcePackage", reference.source); putString("requestId", reference.requestId)
      }
      val reply = try { context.contentResolver.call(uri, CosplayContract.PREPARE, null, payload) }
      finally { bytes.fill(0); payload.clear() }
      if (reply?.get("schema") != CosplayContract.SCHEMA || reply.get("ok") != true) {
        val message = (reply?.get("userMessage") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
        throw CharacterTryOnFailure(message ?: "Character preview is unavailable. Check TryOn settings.")
      }
      if (reply.keySet() != setOf("schema", "ok", "ticket", "expiresAtElapsedMs", "userMessage") ||
          reply.get("ticket") !is String || reply.get("expiresAtElapsedMs") !is Long || reply.get("userMessage") !is String) {
        (reply.get("ticket") as? String)?.let(::cancel)
        throw CharacterTryOnFailure("Character preview returned an invalid request.")
      }
      val prepared = Prepared(reply.getString("ticket")!!, reply.getLong("expiresAtElapsedMs"))
      if (!CharacterTryOnPolicy.ticketValid(prepared.ticket, prepared.expires, SystemClock.elapsedRealtime())) {
        cancel(prepared.ticket)
        throw CharacterTryOnFailure("The preview request expired. Invoke Taku again from the video.")
      }
      return prepared
    }

    fun cancel(value: String) {
      if (!value.matches(Regex("[a-f0-9]{32}"))) return
      runCatching {
        requireIdentity()
        context.contentResolver.call(uri, CosplayContract.CANCEL, null, Bundle().apply {
          putString("schema", CosplayContract.SCHEMA); putString("ticket", value)
        })
      }
    }
  }
}

private class CharacterTryOnFailure(message: String) : Exception(message)

/** Worker-only bounded encoding; the system's borrowed screenshot is never recycled. */
internal object CharacterReferenceEncoder {
  fun encode(source: Bitmap): ByteArray {
    require(!source.isRecycled && source.width > 0 && source.height > 0 && source.width.toLong() * source.height <= 64_000_000)
    val scale = minOf(1.0, CosplayContract.MAX_IMAGE_EDGE.toDouble() / maxOf(source.width, source.height))
    var owned = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), true)
      .let { scaled -> try { requireNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false)) } finally { if (scaled !== source) scaled.recycle() } }
    try {
      repeat(8) {
        for (quality in intArrayOf(90, 80, 70, 60, 50)) {
          val output = WipingBytes()
          try {
            check(owned.compress(Bitmap.CompressFormat.JPEG, quality, output))
            val jpeg = output.toByteArray()
            if (CharacterTryOnPolicy.boundedJpeg(jpeg)) return jpeg
            jpeg.fill(0)
          } finally { output.close() }
        }
        val smaller = Bitmap.createScaledBitmap(owned, maxOf(1, (owned.width * .8).toInt()), maxOf(1, (owned.height * .8).toInt()), true)
        if (smaller === owned) error("Reference is too large")
        owned.recycle(); owned = smaller
      }
      error("Reference is too large")
    } finally { owned.recycle() }
  }

  private class WipingBytes : ByteArrayOutputStream() {
    override fun close() { buf.fill(0); reset() }
  }
}
