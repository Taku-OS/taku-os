package org.agentos.agent.home.ui.assistant

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.os.Build
import androidx.compose.ui.viewinterop.AndroidView
import org.agentos.agent.home.ui.effects.WorkingGlowView
import org.agentos.agent.home.ui.effects.shouldShowEdgeGlow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.agentos.agent.home.assistant.EntryMode
import org.agentos.agent.home.ui.HomeController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Everything the AI entry puts over the home: the working edge glow, voice hold (1-1), the text
 * field (1-2…1-5) and the cancel dissolve. SystemUI owns the island. State comes from
 * [org.agentos.agent.home.assistant.AssistantSession], shared with Agents.
 */
@Composable
fun BoxScope.AssistantLayer(scene: GraphicsLayer, controller: HomeController) {
  AssistantLayer(scene, controller.assistant)
}

@Composable
fun BoxScope.AssistantLayer(
  scene: GraphicsLayer,
  session: org.agentos.agent.home.assistant.AssistantSession,
) {
  val state by session.state.collectAsStateWithLifecycle()
  val voice by session.voice.state.collectAsStateWithLifecycle()
  var width by remember { mutableFloatStateOf(0f) }
  var height by remember { mutableFloatStateOf(0f) }
  Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() })
  if (width <= 0f) return

  // Shown only while Live mode is active (e.g. side-key long-press invocation), never during
  // ordinary Agent Chat or AI output.
  WorkingGlow(active = shouldShowEdgeGlow(working = state.working, voiceContinuous = state.voiceContinuous))

  VoiceHoldOverlay(scene, visible = state.entry == EntryMode.Voice, voice = voice,
    zone = state.holdZone, continuous = state.voiceContinuous, keepTalking = state.panelId == null, session = session)

  if (state.entry == EntryMode.Text) Box(Modifier.fillMaxSize().pointerInput(session) {
    detectTapGestures { session.closeEntry() }
  })

  TextComposer(scene, open = state.entry == EntryMode.Text, state = state, voice = voice, session = session, screenWidth = width, screenHeight = height)

  // The actual status bar and every island surface now belong to SystemUI.
  BackHandler(enabled = state.entry != EntryMode.None) {
    when (state.entry) {
      EntryMode.Voice -> session.cancelVoice()
      EntryMode.Text -> session.closeEntry()
      EntryMode.None -> Unit
    }
  }
}

/** Designer screen-edge-glow: travelling packets, shared native renderer above the keyboard. */
@Composable
fun WorkingGlow(active: Boolean) {
  val context = LocalContext.current
  val overlay = remember { WorkingGlowOverlay.get(context.applicationContext) }
  val owner = remember { Any() }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val lifecycleState by lifecycle.currentStateAsState()
  // Only a surface actually on screen asks for the glow; the overlay draws it above the keyboard.
  val onScreen = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
  LaunchedEffect(active, onScreen) { overlay.request(owner, active && onScreen) }
  DisposableEffect(overlay) { onDispose { overlay.release(owner) } }
  if (overlay.available) return
  InWindowGlow(active && onScreen)
}

/** The same glow drawn in this window, for builds where the overlay window is refused. */
@Composable
private fun InWindowGlow(active: Boolean) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { WorkingGlowView(it) },
    update = { it.setWorking(active) },
  )
}
