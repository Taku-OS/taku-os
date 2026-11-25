package org.agentos.agent.home.assistant

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Which Taku Home setup page (the `ai-setup` services) fixes a Live Talk failure. The Audio Engine
 * reports the same `PROVIDER_NOT_CONFIGURED` for a missing fal key (Grok Voice) and a missing Gemini
 * Live key, so the engine code alone cannot say whether the fal connection card helps.
 */
internal object LiveTalkSetup {
  const val FAL = "fal"
  private const val FAL_GROK = "fal-grok"
  private val MISSING_KEY = setOf("PROVIDER_NOT_CONFIGURED", "PROVIDER_REMOVED")
  private const val AUDIO = "content://org.agentos.audio.open-access"

  /** Only a missing key can be fixed by a setup page; other failures never need a settings read. */
  fun missingKey(code: String) = code in MISSING_KEY

  /**
   * [FAL] when the failure is a missing fal key, else null. [provider] is the profile the engine
   * accepted for this connection; when the open request itself was refused it is null, and the
   * answer comes from Taku Audio's [settings] read: the selected Live provider is Grok via fal and
   * Taku Audio reports no fal key.
   */
  fun service(code: String, provider: String?, settings: JSONObject?): String? {
    if (!missingKey(code)) return null
    if (provider != null) return FAL.takeIf { provider == FAL_GROK }
    val selected = settings?.optJSONObject("live")?.optString("profileId")
    val falConfigured = settings?.optJSONObject("fal")?.opt("configured") as? Boolean ?: return null
    return FAL.takeIf { selected == FAL_GROK && !falConfigured }
  }

  /** Taku Audio's settings read (the same one onboarding uses); null when unavailable. Blocking. */
  fun readAudioSettings(context: Context): JSONObject? = try {
    val bundle = context.contentResolver.call(Uri.parse(AUDIO), "settings", JSONObject().put("action", "read").toString(), null)
    val envelope = JSONObject(bundle?.getString("result") ?: "{}")
    if (envelope.optBoolean("ok")) envelope.optJSONObject("result") else null
  } catch (_: Exception) { null }
}
