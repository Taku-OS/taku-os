package org.agentos.agent.home

import android.app.Activity
import com.facebook.react.ReactHost
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import java.util.IdentityHashMap

/** One application host for chat and feed surfaces, with one coordinated lifecycle owner. */
class TakuReactHost(private val host: ReactHost) : ReactHost by host {
  private val backHandlers = IdentityHashMap<Activity, DefaultHardwareBackBtnHandler?>()
  private val lifecycle = ActivityHostLifecycle<Activity>(
    resumeHost = { host.onHostResume(it, backHandlers[it]) },
    pauseHost = { host.onHostPause(it) },
    destroyHost = { host.onHostDestroy(it) },
  )

  override fun onHostResume(activity: Activity?, defaultBackButtonImpl: DefaultHardwareBackBtnHandler?) {
    UiThreadUtil.assertOnUiThread()
    if (activity == null) {
      clearOwners()
      host.onHostResume(null, defaultBackButtonImpl)
      return
    }
    backHandlers[activity] = defaultBackButtonImpl
    lifecycle.resume(activity)
  }

  override fun onHostResume(activity: Activity?) = onHostResume(activity, backHandlers[activity])

  override fun onHostPause(activity: Activity?) {
    UiThreadUtil.assertOnUiThread()
    if (activity == null) return host.onHostPause(null)
    lifecycle.pause(activity)
    backHandlers.remove(activity)
  }

  override fun onHostDestroy(activity: Activity?) {
    UiThreadUtil.assertOnUiThread()
    if (activity == null) return host.onHostDestroy(null)
    lifecycle.destroy(activity)
    backHandlers.remove(activity)
  }

  override fun onHostPause() {
    UiThreadUtil.assertOnUiThread()
    clearOwners()
    host.onHostPause()
  }

  override fun onHostDestroy() {
    UiThreadUtil.assertOnUiThread()
    clearOwners()
    host.onHostDestroy()
  }

  private fun clearOwners() {
    lifecycle.clear()
    backHandlers.clear()
  }
}
