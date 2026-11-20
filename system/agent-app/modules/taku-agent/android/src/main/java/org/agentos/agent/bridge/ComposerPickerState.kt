package org.agentos.agent.bridge

import android.os.Bundle
import androidx.activity.result.ActivityResultRegistryOwner

/** Restore external activity results without restoring obsolete React navigation fragments. */
object ComposerPickerState {
  private const val KEY = "taku.activityResults"

  @JvmStatic fun save(activity: ActivityResultRegistryOwner, state: Bundle) {
    val results = Bundle()
    activity.activityResultRegistry.onSaveInstanceState(results)
    state.putBundle(KEY, results)
  }

  @JvmStatic fun restore(activity: ActivityResultRegistryOwner, state: Bundle?) {
    state?.getBundle(KEY)?.let(activity.activityResultRegistry::onRestoreInstanceState)
  }
}
