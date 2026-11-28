package org.agentos.agent.home.assistant

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.onboarding.OnboardingRepository
import org.agentos.agent.home.onboarding.OnboardingRules
import org.agentos.agent.home.ui.assistant.AssistantLayer

/** Translucent cross-app presentation; Quickstep keeps ownership of the original pointer stream. */
class AssistantOverlayActivity : AppCompatActivity() {
  private val session by lazy { AssistantSession.get(this) }
  private var focusedOnce = false
  private var resumed = false
  private var presenting = false
  /**
   * Counted in [AssistantSession.overlayHosts] while started. Home or a task switch may only stop
   * this window (it is destroyed later, or never while its task is kept), and Home must take its
   * composer back the moment it is visible again.
   */
  private var hosting = false

  override fun onCreate(state: Bundle?) {
    enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
    super.onCreate(state)
    window.isNavigationBarContrastEnforced = false
    // No blur behind (BUG-0051): Android turns cross-window blur off and on by itself (tunnelled
    // video, GPU load), so over video feeds and games the app underneath flickered between blurred
    // and sharp while this window showed. The overlay draws its own surfaces; the app stays as is.
    presenting = open(intent)
    if (!presenting) { finish(); return }
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
          // Keep the underlying app live and unblurred; nothing here is a screenshot of it.
          Box(Modifier.fillMaxSize().drawWithContent {
            scene.record { this@drawWithContent.drawContent() }; drawLayer(scene)
          })
          AssistantLayer(scene, session)
        }
      }
    }
  }

  private fun open(intent: Intent): Boolean {
    if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
    // Same rule as Home: while onboarding takes over Home there is no usable AI to present.
    if (OnboardingRules.takesOverHome(OnboardingRepository.get(this).state.value.step)) return false
    when (intent.getStringExtra("mode")) {
      "text" -> {
        if (!session.canOpenText) return false
        session.open(EntryMode.Text)
      }
      "voice" -> {
        val id = intent.getStringExtra("gestureId").orEmpty()
        if (session.gesture.id == id && session.gesture.phase in setOf(VoiceGesture.Phase.Continuous, VoiceGesture.Phase.Listening)) return true
        if (session.gesture.id != id || !session.gesture.held) return false
        session.activateHold(id)
      }
      else -> return false
    }
    return true
  }
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // An irrelevant/replayed intent must not close an already active overlay.
    if (open(intent)) { setIntent(intent); presenting = true }
  }
  override fun onStart() {
    super.onStart()
    if (presenting && !hosting) { hosting = true; session.overlayHostStarted() }
  }
  override fun onResume() { super.onResume(); resumed = true }
  override fun onPause() { resumed = false; super.onPause() }
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) focusedOnce = true
    else if (presenting && focusedOnce && resumed && session.gesture.held) session.cancelVoice()
  }
  override fun onDestroy() {
    if (hosting) { hosting = false; session.overlayHostEnded() }
    super.onDestroy()
  }
  override fun onStop() {
    if (presenting && intent.getStringExtra("mode") == "voice")
      session.onVoiceSurfaceStopped(intent.getStringExtra("gestureId").orEmpty(), isChangingConfigurations)
    if (hosting) { hosting = false; session.overlayHostEnded() }
    super.onStop()
  }
}
