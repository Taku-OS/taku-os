package org.agentos.agent.home

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * The one rule for "the user can see and use the screen now": it is on and not locked. Voice input
 * and the island reveal of a handed-off reply both depend on it and must agree.
 */
object ScreenState {
  fun usable(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isInteractive != false &&
      context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
}
