package org.agentos.agent.home.onboarding

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONObject

/**
 * Device-local Agent access to first-run onboarding:
 * `content call --uri content://org.agentos.agent.home-onboarding --method onboarding/get`.
 * Every method runs the same [OnboardingRepository] operation as the Home card, with the same
 * validation. `ai-setup/…` read, connect and open the Jev or Fal setup page after onboarding
 * ([ServiceSetup]). Open to device-local callers by product decision (see AGENTS.md).
 */
class OnboardingProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val out = Bundle()
    val identity = Binder.clearCallingIdentity()
    try {
      val ctx = context ?: throw OnboardingException("UNAVAILABLE", "provider not attached")
      val p = if (arg.isNullOrBlank()) JSONObject() else JSONObject(arg)
      val repo = OnboardingRepository.get(ctx)
      fun only(vararg keys: String) {
        val names = p.keys()
        while (names.hasNext()) names.next().let { if (it !in keys) throw OnboardingException("UNSUPPORTED_PARAMETER", "unexpected parameter $it") }
      }
      // The Jev or Fal setup page after onboarding; these answer with that service, not the card.
      fun setupService(): OnboardingService = OnboardingService.parse(p.optString("service"))
        ?.takeIf { it != OnboardingService.CHATGPT }
        ?: throw OnboardingException("INVALID_SERVICE", "service must be jev or fal")
      val setup: JSONObject? = when (method) {
        "ai-setup/get" -> { only("service"); val s = setupService(); JSONObject().put("service", s.id).put("connection", repo.serviceStatus(s).id) }
        "ai-setup/connect" -> {
          only("service", "key")
          val s = setupService()
          if (s == OnboardingService.FAL && (!p.has("key") || p.isNull("key"))) throw OnboardingException("INVALID_KEY", "fal needs a key")
          JSONObject().put("service", s.id).put("connection", repo.setUp(s, if (!p.has("key") || p.isNull("key")) null else p.getString("key")).id)
        }
        "ai-setup/open" -> {
          only("service")
          val s = setupService()
          ServiceSetup.open(ctx, s)
          JSONObject().put("service", s.id).put("connection", repo.serviceStatus(s).id).put("opened", true)
        }
        else -> null
      }
      if (setup != null) {
        out.putString("result", JSONObject().put("ok", true).put("result", setup).toString())
        return out
      }
      when (method) {
        "onboarding/get" -> only()
        "onboarding/set-up" -> { only(); repo.perform(OnboardingAction.SET_UP) }
        "onboarding/submit-name" -> {
          only("name")
          if (!p.has("name") || p.isNull("name")) throw OnboardingException("INVALID_NAME", "name is required")
          repo.perform(OnboardingAction.SUBMIT_NAME, p.getString("name"))
        }
        "onboarding/skip-name" -> { only(); repo.perform(OnboardingAction.SKIP_NAME) }
        "onboarding/connect" -> {
          only("service", "key")
          val service = OnboardingService.parse(p.optString("service"))
            ?: throw OnboardingException("INVALID_SERVICE", "service must be chatgpt, jev or fal")
          if (service == OnboardingService.CHATGPT && p.has("key")) throw OnboardingException("UNSUPPORTED_PARAMETER", "chatgpt signs in through the official page; no key")
          repo.connect(service, if (p.isNull("key")) null else p.optString("key"))
        }
        "onboarding/cancel-sign-in" -> { only(); repo.cancelSignIn() }
        "onboarding/finish" -> { only(); repo.perform(OnboardingAction.FINISH) }
        "onboarding/explore" -> { only(); repo.perform(OnboardingAction.EXPLORE) }
        "onboarding/reset" -> { only(); repo.reset() }
        else -> throw OnboardingException("UNKNOWN_METHOD", "unknown method $method")
      }
      out.putString("result", JSONObject().put("ok", true).put("result", repo.describe()).toString())
    } catch (e: OnboardingException) {
      out.putString("result", JSONObject().put("ok", false).put("code", e.code).put("message", e.message).toString())
    } catch (e: Exception) {
      out.putString("result", JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT").put("message", e.message ?: e.javaClass.simpleName).toString())
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
    return out
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
    throw UnsupportedOperationException("Use content call; see onboarding/get")
  override fun getType(uri: Uri) = "application/json"
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use content call")
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Use content call")
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Use onboarding/reset")
}
