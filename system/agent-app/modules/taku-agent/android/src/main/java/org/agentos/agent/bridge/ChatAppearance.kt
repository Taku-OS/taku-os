package org.agentos.agent.bridge

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * The chat assistant's background (Taku AI settings → Appearance): `dark` (Figma 300:661, the
 * default) or `light`. Stored per Android user in `Settings.Secure` [KEY], the one source of truth
 * for the GUI and Agents (`settings get|put secure taku_chat_appearance`); a missing or unknown
 * value reads as `dark`.
 */
internal object ChatAppearance {
  const val KEY = "taku_chat_appearance"
  const val DARK = "dark"
  const val LIGHT = "light"

  fun read(context: Context): String =
    if (Settings.Secure.getString(context.contentResolver, KEY) == LIGHT) LIGHT else DARK

  /** Saves [value] (`dark` or `light`); `INVALID_APPEARANCE` or `SETTINGS_WRITE_FAILED` otherwise. */
  fun write(context: Context, value: String): String {
    if (value != DARK && value != LIGHT) throw IllegalArgumentException("INVALID_APPEARANCE")
    val saved = try {
      Settings.Secure.putString(context.contentResolver, KEY, value)
    } catch (e: SecurityException) {
      false
    }
    if (!saved) throw IllegalStateException("SETTINGS_WRITE_FAILED")
    return read(context)
  }

  /** Follows [KEY], including changes an Agent makes with `settings put`; returns the observer to unregister. */
  fun observe(context: Context, onChange: (String) -> Unit): ContentObserver {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) = onChange(read(context))
    }
    context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY), false, observer)
    return observer
  }
}
