package org.agentos.agent.home.assistant

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.island.PersistedPanelIsland
import org.agentos.agent.home.onboarding.OnboardingRepository
import org.agentos.agent.home.onboarding.OnboardingRules
import org.agentos.agent.home.ui.assistant.AssistantLayer

/** Translucent cross-app presentation; Quickstep keeps ownership of the original pointer stream. */
class AssistantOverlayActivity : AppCompatActivity() {
  private val session by lazy { AssistantSession.get(this) }
  private var focusedOnce = false
  private var resumed = false
  private var presenting = false
  private var panelRecovery: Job? = null

  override fun onCreate(state: Bundle?) {
    enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
    super.onCreate(state)
    window.isNavigationBarContrastEnforced = false
    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    window.attributes = window.attributes.apply { blurBehindRadius = (24 * resources.displayMetrics.density).toInt() }
    if (!open(intent)) finish()
  }

  private fun present(intent: Intent) {
    setIntent(intent)
    if (presenting) return
    presenting = true
    setContent {
      val ui by session.state.collectAsStateWithLifecycle()
      val scene = rememberGraphicsLayer()
      LaunchedEffect(ui.overlayVisible) {
        // Release closes the recorder, not the result surface. An explicit dismissal or
        // collapse hands focus back to the underlying app while work may continue.
        if (!ui.overlayVisible) finish()
      }
      TakuTheme {
        Box(Modifier.fillMaxSize()) {
          // Keep the underlying app live. Window blur supplies its backdrop, not a screenshot.
          Box(Modifier.fillMaxSize().drawWithContent {
            scene.record { this@drawWithContent.drawContent() }; drawLayer(scene)
          })
          AssistantLayer(scene, session)
        }
      }
    }
  }

  private fun open(intent: Intent): Boolean {
    if (isLocked()) return false
    // Same rule as Home: while onboarding takes over Home there is no usable AI to present.
    if (OnboardingRules.takesOverHome(OnboardingRepository.get(this).state.value.step)) return false
    when (intent.getStringExtra("mode")) {
      "text" -> {
        val panelId = intent.getStringExtra("panelId")
        if (panelId == null) {
          if (!session.canOpenText) return false
          session.open(EntryMode.Text)
        } else {
          val activityId = intent.getStringExtra("activityId") ?: return false
          if (!session.reopenPanelText(panelId, activityId)) return recoverPanelEdit(intent, panelId, activityId)
        }
      }
      "voice" -> {
        val id = intent.getStringExtra("gestureId").orEmpty()
        val phase = session.gesture.phase
        val active = phase == VoiceGesture.Phase.Continuous || phase == VoiceGesture.Phase.Listening
        if (session.gesture.id != id || (!active && !session.gesture.held)) return false
        if (!active) session.activateHold(id)
      }
      else -> return false
    }
    panelRecovery?.cancel()
    present(intent)
    return true
  }

  private fun recoverPanelEdit(intent: Intent, panelId: String, activityId: String): Boolean {
    if (presenting || !session.canOpenText) return false
    val route = PersistedPanelIsland.read(this)?.takeIf { it.panelId == panelId && it.activityId == activityId }
      ?: return false
    if (panelRecovery?.isActive == true) return true
    panelRecovery = lifecycleScope.launch {
      try {
        session.restorePanelIsland(route)
        if (!isLocked() && session.reopenPanelText(panelId, activityId)) {
          present(intent)
          return@launch
        }
      } catch (failure: GatewayException) {
        Log.w("TakuIsland", "Panel Edit recovery failed", failure)
      }
      if (!presenting) finish()
    }
    return true
  }

  private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // An irrelevant/replayed intent must not close an already active overlay.
    open(intent)
  }
  override fun onResume() { super.onResume(); resumed = true }
  override fun onPause() { resumed = false; super.onPause() }
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) focusedOnce = true
    else if (presenting && focusedOnce && resumed && session.gesture.held) session.cancelVoice()
  }
  override fun onStop() {
    if (!presenting && panelRecovery?.isActive == true) {
      panelRecovery?.cancel()
      if (!isChangingConfigurations) finish()
    }
    if (presenting && intent.getStringExtra("mode") == "voice")
      session.onVoiceSurfaceStopped(intent.getStringExtra("gestureId").orEmpty(), isChangingConfigurations)
    super.onStop()
  }
}
