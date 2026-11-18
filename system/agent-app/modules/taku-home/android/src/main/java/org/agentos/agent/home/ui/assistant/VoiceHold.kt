package org.agentos.agent.home.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.CubicBezierEasing
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.design.TakuSpacing
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.design.voiceTranscriptStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where a held finger is: over "Cancel" (1-1-2), over "Keep talking", or neither (release arms automatic utterance submission). */
typealias HoldZone = org.agentos.agent.home.assistant.VoiceGesture.Zone

/**
 * Geometry of the 1-1 bottom, in px of a [width]×[height] screen. The two "64px / gap 16px"
 * arcs are round-capped 64 dp strokes on one circle (from the Figma paths: r 483.7 dp,
 * centre 274.35 dp below the 917 dp frame); the glass ellipse is 538 × 259 centred
 * 31.5 dp above the bottom. Scaled by width / 412.
 */
class VoiceArcs(val width: Float, val height: Float, density: Float) {
  private val s = width / (412f * density)
  private val d = density * s
  val center = Offset(width / 2f, height + 274.35f * d)
  val radius = 483.7f * d
  val stroke = 64f * d
  val ellipse = floatArrayOf(width / 2f, height - 31.5f * d, 269f * d, 129.5f * d)
  val auroraTop = height - 212f * d

  /** Arc spans in degrees (screen y down): left = Cancel, right = Keep talking. */
  val left = -124.32f to -94.74f
  val right = -85.26f to -55.68f
  /** Label anchors: the middle of each arc's on-screen part. */
  val leftLabel = -104.73f
  val rightLabel = -75.27f

  fun point(deg: Float, r: Float = radius) = Offset(center.x + r * cos(deg * PI.toFloat() / 180f), center.y + r * sin(deg * PI.toFloat() / 180f))

  fun zone(p: Offset, slop: Float): HoldZone {
    val dist = hypot(p.x - center.x, p.y - center.y)
    if (abs(dist - radius) > stroke / 2f + slop) return HoldZone.None
    val a = (atan2(p.y - center.y, p.x - center.x) * 180f / PI.toFloat())
    val margin = (stroke / 2f) / radius * 180f / PI.toFloat()
    return when {
      a >= left.first - margin * 3 && a <= left.second + margin -> HoldZone.Cancel
      a >= right.first - margin && a <= right.second + margin * 3 -> HoldZone.KeepTalking
      else -> HoldZone.None
    }
  }


}

/**
 * 1-1 / 1-1-2: the home blurred under the bottom veil, the colour-shifting aurora inside the
 * glass, the Cancel / Keep talking arcs, and the waveform with the live transcript in the middle.
 * The gesture itself is tracked by the assistant entry; this only draws [zone].
 */
@Composable
fun BoxScope.VoiceHoldOverlay(
  scene: GraphicsLayer,
  visible: Boolean,
  voice: VoiceState,
  zone: HoldZone,
  continuous: Boolean,
  session: org.agentos.agent.home.assistant.AssistantSession,
) {
  val appear by animateFloatAsState(if (visible) 1f else 0f, if (visible) tween(320) else tween(260), label = "voiceAppear")
  if (appear <= 0.001f && !visible) return
  val colors = TakuTheme.colors
  val type = TakuTheme.type
  var pressedZone by remember { mutableStateOf(HoldZone.None) }
  var cancelledVoice by remember { mutableStateOf<VoiceState?>(null) }
  val shownVoice = cancelledVoice ?: voice
  val shownZone = if (pressedZone != HoldZone.None) pressedZone else zone
  val scope = rememberCoroutineScope()
  LaunchedEffect(visible) { if (!visible) { cancelledVoice = null; pressedZone = HoldZone.None } }
  val cancel: () -> Unit = {
    if (cancelledVoice == null) {
      cancelledVoice = voice; pressedZone = HoldZone.Cancel
      val operation = session.beginCancelledVoice()
      scope.launch { delay(if (voice.transcript.isBlank()) 180 else 900); session.endCancelledVoice(operation) }
    }
  }
  val region = rememberGraphicsLayer()
  val context = LocalContext.current
  val artwork = remember(context) {
    listOf("cancel", "cancel-active", "keep-talking", "keep-talking-active", "voice-glass").associateWith { name ->
      context.assets.open("assistant/$name.png").use { android.graphics.BitmapFactory.decodeStream(it).asImageBitmap() }
    }
  }
  Canvas(Modifier.fillMaxSize()) {
    val d = size.width / 412f
    // Native blur over the current content; the exported art supplies the exact Figma shapes.
    region.renderEffect = android.graphics.RenderEffect.createBlurEffect(24f * d, 24f * d,
      android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
    region.record(IntSize(size.width.toInt(), size.height.toInt())) { drawLayer(scene) }
    drawLayer(region)
    drawRect(Brush.verticalGradient(listOf(colors.edgeFade.copy(alpha = 0.32f * appear), colors.edgeFade.copy(alpha = appear))))
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    AndroidView(factory = { org.agentos.agent.home.VoiceEffectView(it) },
      modifier = Modifier.align(Alignment.BottomCenter).width(maxWidth).height(maxWidth * (357f / 412f)).graphicsLayer { alpha = appear },
      update = { it.level = if (shownVoice.ready) shownVoice.levels.lastOrNull() ?: 0f else 0f; it.update() })
  }
  Canvas(Modifier.fillMaxSize()) {
    val d = size.width / 412f
    fun art(name: String, x: Float, fromBottom: Float, w: Float, h: Float, plus: Boolean = false, mirror: Boolean = false) {
      val left = x * d
      val top = size.height - fromBottom * d + (1f - appear) * 48f * d
      withTransform({
        translate(if (mirror) left + w * d else left, top)
        if (mirror) scale(-1f, 1f, pivot = Offset.Zero)
      }) {
        drawImage(artwork.getValue(name), dstOffset = IntOffset.Zero,
          dstSize = IntSize((w * d).roundToInt(), (h * d).roundToInt()), alpha = appear,
          blendMode = if (plus) BlendMode.Plus else BlendMode.SrcOver)
      }
    }
    art("voice-glass", -95f, 193f, 602f, 323f)
    run {
      art(if (shownZone == HoldZone.Cancel) "cancel-active" else "cancel", -98.72f, 239.68f, 296.72f, 146.564f)
      art(if (shownZone == HoldZone.KeepTalking) "keep-talking-active" else "keep-talking", 214f, 239.68f, 296.72f, 146.564f)
    }
  }

  // Arc labels, rotated along the circle.
  val labelStyle = type.bodyXl
  ArcLabel("Cancel", true, shownZone == HoldZone.Cancel, appear, labelStyle)
  ArcLabel("Keep talking", false, shownZone == HoldZone.KeepTalking || continuous, appear, labelStyle)
  if (visible) Box(Modifier.fillMaxSize().semantics {
    customActions = listOf(CustomAccessibilityAction("Cancel") { cancel(); true },
      CustomAccessibilityAction("Keep talking") { session.keepTalking(); true })
  }.pointerInput(visible, continuous) {
    detectTapGestures(onPress = { position ->
      val hit = VoiceArcs(size.width.toFloat(), size.height.toFloat(), density).zone(position, 16f * density)
      pressedZone = hit
      val released = tryAwaitRelease()
      if (released) when (hit) {
        HoldZone.Cancel -> cancel()
        HoldZone.KeepTalking -> session.keepTalking()
        else -> Unit
      }
      if (cancelledVoice == null) pressedZone = HoldZone.None
    })
  })

  // The cancel preview is shared by desktop and system-held streams (Figma 297:505).
  val transcriptLayer = rememberGraphicsLayer()
  var transcriptBounds by remember { mutableStateOf<Rect?>(null) }
  var cancelDust by remember { mutableStateOf<org.agentos.agent.home.ui.DustDissolve?>(null) }
  val dustProgress = remember { Animatable(0f) }
  val pixelDensity = LocalDensity.current.density
  LaunchedEffect(shownZone) {
    cancelDust = null
    if (shownZone == HoldZone.Cancel && shownVoice.transcript.isNotBlank() && transcriptBounds != null) {
      runCatching {
        cancelDust = org.agentos.agent.home.ui.DustDissolve(
          transcriptLayer.toImageBitmap().asAndroidBitmap(), transcriptBounds!!, pixelDensity)
      }
      if (cancelDust != null) { dustProgress.snapTo(0f); dustProgress.animateTo(1f, tween(900)) }
    }
  }
  cancelDust?.let { dust ->
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = appear }) {
      drawIntoCanvas { dust.draw(it.nativeCanvas, dustProgress.value) }
    }
  }

  // Waveform + transcript, centred (1-1: gap 24).
  Column(
    Modifier.align(Alignment.Center).padding(horizontal = TakuSpacing.xl).graphicsLayer { alpha = appear },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(TakuSpacing.xl),
  ) {
    VoiceBars(if (shownVoice.ready) shownVoice.levels else emptyList(), flat = shownZone == HoldZone.Cancel)
    if (shownVoice.transcript.isNotBlank()) {
      BasicText(
        "“${shownVoice.transcript.trim()}”",
        style = voiceTranscriptStyle(shownVoice.transcript).copy(color = colors.textSecondary, textAlign = TextAlign.Center),
        maxLines = 5, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .onGloballyPositioned { transcriptBounds = it.boundsInRoot() }
          .drawWithContent {
            transcriptLayer.record { this@drawWithContent.drawContent() }
            if (cancelDust == null) drawLayer(transcriptLayer)
          },
      )
    }
  }
  val cover by animateFloatAsState(if (continuous) 1f else 0f,
    tween(820, easing = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)), label = "keepTalkingSurface")
  if (cover > 0f) Canvas(Modifier.fillMaxSize()) {
    val d = size.width / 412f
    val c = cover * cover * (3f - 2f * cover)
    val x = (352f + (206f - 352f) * c) * d
    val y = size.height - (165f + 294f * c) * d
    val r = (2f + 700f * cover) * d
    withTransform({ scale(1.08f, 1f, Offset(x,y)) }) {
      drawCircle(Brush.radialGradient(0f to colors.islandSurface, 0.88f to colors.islandSurface, 1f to Color.Transparent,
        center = Offset(x,y), radius = r), r, Offset(x,y))
    }
  }

}

@Composable
private fun ArcLabel(text: String, left: Boolean, armed: Boolean, appear: Float, style: androidx.compose.ui.text.TextStyle) {
  val density = LocalDensity.current.density
  val colors = TakuTheme.colors
  BasicText(
    text,
    style = style.copy(color = if (armed) colors.textOnInverse else colors.textSecondary),
    modifier = Modifier.layout { measurable, constraints ->
      val p = measurable.measure(Constraints())
      layout(constraints.maxWidth, constraints.maxHeight) {
        val arcs = VoiceArcs(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density)
        val deg = if (left) arcs.leftLabel else arcs.rightLabel
        val at = arcs.point(deg)
        val lift = (1f - appear) * 72f * density
        p.placeWithLayer((at.x - p.width / 2f).roundToInt(), (at.y - p.height / 2f + lift).roundToInt()) {
          rotationZ = deg + 90f
          alpha = appear
        }
      }
    },
  )
}

/**
 * 1-1 "row": ten 3 dp bars (gap 4, 28 dp tall) driven by the latest measured input level; flat
 * 6 dp dots on cancel. Without readings the bars hold their resting shape.
 */
@Composable
private fun VoiceBars(levels: List<Float>, flat: Boolean) {
  AndroidView(factory = { org.agentos.agent.home.VoiceEffectView(it).apply { kind = "wave" } },
    modifier = Modifier.width(66.dp).height(28.dp),
    update = { it.level = levels.lastOrNull() ?: 0f; it.flat = flat; it.update() })
}
