package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.delay
import org.agentos.agent.home.ui.effects.Shaders
import androidx.compose.foundation.background

/**
 * The bottom edge and assistant entry (0-0 / 0-1): progressive blur + #E3E3E3 fade over
 * the scene, the refractive glass sphere rising 40 dp when touched with the prismatic glow
 * and "Double tap to type or hold to talk", plus the page dots.
 *
 * Geometry (dp, from the 412×917 frames): the 158 dp fade frame starts 118 dp above the
 * bottom (158 when expanded); the sphere ellipse is centred 126.5 dp below the frame top,
 * ry 78.5, rx 240 → 206 (scaled by width / 412).
 */
@Composable
internal fun BoxScope.AssistantEntry(scene: GraphicsLayer, controller: HomeController, pageCount: Int, page: Int, navInset: Dp, gestures: HomeGesturePolicy) {
  val density = LocalDensity.current
  val haptics = LocalHapticFeedback.current
  val type = TakuTheme.type
  val entryState by controller.assistant.state.collectAsStateWithLifecycle()
  val expanded = entryState.hintExpanded
  val e by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "entry")
  // Losing window focus mid-hold (shade, recents, another window) cancels it; it never sends.
  val windowFocused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(windowFocused) { if (!windowFocused) controller.abortVoiceHold() }
  val visible = lerp(118.dp, 158.dp, e)
  val region = rememberGraphicsLayer()
  val fade = TakuTheme.colors.edgeFade
  val effectKey = remember { arrayOfNulls<Any>(1) }

  Canvas(
    Modifier
      .align(Alignment.BottomCenter)
      .fillMaxWidth()
      .height(visible),
  ) {
    val w = size.width
    val h = size.height
    val d = density.density
    val ellipse = floatArrayOf(w / 2f, 126.5f * d, (240f + (206f - 240f) * e) * w / 412f, 78.5f * d)
    val glows = arrayOf(
      // Tuned against the 0-1 frame (README "Design notes"): yellow centre, blue left, red right, green.
      floatArrayOf(205f * w / 412f, 172f * d, 42f * d, 34f * d),
      floatArrayOf(128f * w / 412f, 168f * d, 62f * d, 36f * d),
      floatArrayOf(300f * w / 412f, 178f * d, 62f * d, 34f * d),
      floatArrayOf(244f * w / 412f, 182f * d, 46f * d, 34f * d),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val key = listOf(w, h, e, fade)
      if (effectKey[0] != key) {
        region.renderEffect = Shaders.bottomGlass(w, h, 158f * d, d, ellipse, e, glows, 12f * d, fade).asComposeRenderEffect()
        effectKey[0] = key
      }
    }
    region.clip = true
    val screenTop = scene.size.height - h
    region.record(IntSize(w.toInt(), h.toInt())) { translate(0f, -screenTop) { drawLayer(scene) } }
    drawLayer(region)
  }

  // Touch target: the visible part of the sphere. Tap shows the hint, double tap types (1-2),
  // press and hold talks (1-1); while held, the finger is tracked over the Cancel / Keep talking arcs.
  var origin by remember { mutableStateOf(Offset.Zero) }
  Box(
    Modifier
      .align(Alignment.BottomCenter)
      .fillMaxWidth()
      .height(lerp(70.dp, 110.dp, e))
      .onGloballyPositioned { origin = it.positionInRoot() }
      .pointerInput(gestures) {
        awaitEachGesture {
          val down = awaitFirstDown()
          // This is the desktop entry, not the system nav handle. Keep mandatory/system
          // edge starts available to Android, including a stationary hold on the handle.
          if (gestures.systemStart) return@awaitEachGesture
          // The long-press wait overlaps connecting to the Audio Engine, so "Speak now" follows the press.
          controller.prepareVoiceHold()
          var cancelled = false
          val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result: androidx.compose.ui.input.pointer.PointerInputChange? = null
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id }
              if (event.changes.count { it.pressed } > 1 || change == null || change.isConsumed ||
                  (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                cancelled = true; break
              }
              if (!change.pressed) { result = change; break }
            }
            result
          }
          if (cancelled) return@awaitEachGesture
          if (up == null) {
            // No haptic here: the one vibration comes when the microphone records (VoiceHold).
            controller.startVoiceHold()
            // Only a real lift sends. When the system takes the gesture over (navigation swipe,
            // recents) Compose delivers ACTION_CANCEL as an up that is already consumed; that,
            // a vanished pointer, or this coroutine being cancelled all abort the hold instead.
            var released = false
            try {
              while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                  released = !change.isConsumed
                  change.consume()
                  if (released) controller.moveVoiceHold(origin + change.position)
                  break
                }
                change.consume()
                if (controller.moveVoiceHold(origin + change.position)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              }
            } finally {
              if (released) controller.releaseVoiceHold() else controller.abortVoiceHold()
            }
          } else {
            up.consume()
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
            if (second == null) controller.assistantExpanded = !controller.assistantExpanded
            else {
              if (gestures.systemStart) return@awaitEachGesture
              second.consume()
              val secondUp = waitForUpOrCancellation() ?: return@awaitEachGesture
              secondUp.consume()
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              controller.openTextInput()
            }
          }
        }
      }
      .semantics {
        contentDescription = "Taku. Double tap to type or hold to talk"
        onClick("Type to Taku") { controller.openTextInput(); true }
        // Accessibility: long click starts listening, the next one sends what was heard.
        onLongClick("Talk to Taku") {
          if (controller.assistant.state.value.entry == org.agentos.agent.home.assistant.EntryMode.Voice) controller.releaseVoiceHold() else controller.startVoiceHold()
          true
        }
      },
  )

  BasicText(
    "Double tap to type or hold to talk",
    style = type.bodyMd.copy(color = TakuTheme.colors.textPlaceholder, shadow = Shadow(TakuTheme.colors.textGlow, Offset(0f, with(density) { 2.dp.toPx() }), with(density) { 4.dp.toPx() })),
    modifier = Modifier
      .align(Alignment.BottomCenter)
      .offset(y = -(158.dp - 80.dp - 20.dp))
      .graphicsLayer { alpha = e; translationY = (1f - e) * 12.dp.toPx() },
  )

  PageDots(pageCount, page, Modifier.align(Alignment.BottomCenter).padding(bottom = navInset + TakuSpacing.sm))
}
