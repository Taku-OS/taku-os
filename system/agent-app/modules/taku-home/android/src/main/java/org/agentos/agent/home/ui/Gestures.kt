package org.agentos.agent.home.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long press that also works over React Native content. It observes the Initial pass
 * without consuming, so taps and scrolls still reach the card and the list; once the
 * timeout elapses without movement it consumes the rest of the gesture, which cancels
 * the touch inside the React surface.
 */
fun Modifier.longPressOver(enabled: Boolean = true, onTap: (() -> Unit)? = null, onLongPress: () -> Unit): Modifier =
  if (!enabled) this else pointerInput(onLongPress, onTap) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      var released = false
      val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
          if (!change.pressed) { released = true; return@withTimeoutOrNull true }
          if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
        }
        @Suppress("UNREACHABLE_CODE") true
      }
      if (ended == null) {
        onLongPress()
        do {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
      } else if (released) {
        onTap?.invoke()
      }
    }
  }

/** Lets content draw [amount] above and below its slot without changing layout. */
fun Modifier.overflowVertical(amount: Dp): Modifier = layout { measurable, constraints ->
  val extra = amount.roundToPx()
  val placeable = measurable.measure(constraints.copy(maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * extra else constraints.maxHeight,
    minHeight = constraints.minHeight + 2 * extra))
  layout(placeable.width, placeable.height - 2 * extra) { placeable.place(0, -extra) }
}
