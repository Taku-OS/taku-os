package org.agentos.agent.home.ui.assistant

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.os.Build
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.agentos.agent.home.assistant.EntryMode
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.ui.HomeController
import org.agentos.agent.home.ui.effects.Shaders
import kotlin.math.cos
import kotlin.math.sin
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

  // While the AI responds, and only then: from the send until the turn and its task complete.
  WorkingGlow(active = state.working)

  VoiceHoldOverlay(scene, visible = state.entry == EntryMode.Voice, voice = voice,
    zone = state.holdZone, continuous = state.voiceContinuous, session = session)

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

/**
 * "Taku is working" (1-4, 1-5): the four Figma "working gradient" layers, each a full-frame
 * inset shadow (blur 32, 4 dp offset) in its own colour, drawn in Figma order with Figma's
 * blend modes: green (0, −4) and indigo (0, 4) plus-lighter, sky (4, 0) and orange (−4, 0)
 * normal. Over the light home, plus-lighter washes those two toward white, which gives the
 * frame's soft, narrow pastel edge. While active the offsets turn slowly together and the
 * strength breathes a little; at phase 0 it is the static frame.
 */
@Composable
fun WorkingGlow(active: Boolean) {
  val context = LocalContext.current
  val overlay = remember { WorkingGlowOverlay.get(context.applicationContext) }
  val owner = remember { Any() }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val lifecycleState by lifecycle.currentStateAsState()
  // Only a surface actually on screen asks for the glow; the overlay draws it above the keyboard.
  val onScreen = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
  val colors = TakuTheme.colors.workingGlow
  LaunchedEffect(active, onScreen, colors) { overlay.request(owner, active && onScreen, colors) }
  DisposableEffect(overlay) { onDispose { overlay.release(owner) } }
  if (overlay.available) return
  InWindowGlow(active)
}

/** The same glow drawn in this window, for builds where the overlay window is refused. */
@Composable
private fun InWindowGlow(active: Boolean) {
  val strength by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 700 else 250), label = "glow")
  if (strength <= 0.002f) return
  val colors = TakuTheme.colors.workingGlow // bottom (green), left (sky), right (orange), top (indigo)
  val view = LocalView.current
  val density = LocalDensity.current
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  // One shader per layer: a RuntimeShader's uniforms are read when the frame is drawn.
  val layers = remember { List(4) { Shaders.EdgeGlow() } }
  var time by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(Unit) { val s = withFrameNanos { it }; while (true) withFrameNanos { time = (it - s) / 1e9f } }
  val radius = remember(view.rootWindowInsets) {
    val corner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius else null
    corner?.toFloat() ?: with(density) { 48f * this.density }
  }
  Canvas(Modifier.fillMaxSize()) {
    val d = density.density
    val angle = time * 0.45f
    val turn = cos(angle) to sin(angle)
    val breathing = strength * (0.92f + 0.08f * sin(time * 2.1f))
    WORKING_LAYERS.forEachIndexed { i, layer ->
      // Rotate the Figma offset by the slow phase.
      val ox = (layer.dx * turn.first - layer.dy * turn.second) * d
      val oy = (layer.dx * turn.second + layer.dy * turn.first) * d
      layers[i].update(size.width, size.height, radius, d, ox, oy, breathing, colors[layer.color])
      drawRect(ShaderBrush(layers[i].shader), blendMode = if (layer.plus) BlendMode.Plus else BlendMode.SrcOver)
    }
  }
}

/** Figma 1-4 "Working" layers in drawing order: colour index into workingGlow, offset (dp), plus-lighter. */
private data class GlowLayer(val color: Int, val dx: Float, val dy: Float, val plus: Boolean)
private val WORKING_LAYERS = listOf(
  GlowLayer(0, 0f, -4f, true),  // 1:3983 green, plus-lighter
  GlowLayer(1, 4f, 0f, false),  // 1:3984 sky
  GlowLayer(2, -4f, 0f, false), // 1:3985 orange
  GlowLayer(3, 0f, 4f, true),   // 1:3986 indigo, plus-lighter
)
