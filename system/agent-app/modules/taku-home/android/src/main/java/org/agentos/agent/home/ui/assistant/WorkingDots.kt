package org.agentos.agent.home.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.design.TakuTheme

/** The designer's deterministic 1000 ms thinking loop, shared with SystemUI. */
@Composable
fun WorkingDots(modifier: Modifier = Modifier, ink: androidx.compose.ui.graphics.Color = TakuTheme.colors.islandInk) {
  val pose = remember { org.agentos.island.DotMatrixLoader.Pose() }
  var timeMs by remember { mutableFloatStateOf(0f) }
  var running by remember { mutableStateOf(android.animation.ValueAnimator.areAnimatorsEnabled()) }
  LaunchedEffect(Unit) {
    val start = withFrameNanos { it }
    while (true) {
      running = android.animation.ValueAnimator.areAnimatorsEnabled()
      if (!running) { kotlinx.coroutines.delay(250); continue }
      withFrameNanos { timeMs = ((it - start) / 1_000_000.0 % 1000).toFloat() }
    }
  }
  Canvas(modifier.size(16.dp)) {
    if (running) org.agentos.island.DotMatrixLoader.thinking(timeMs.toDouble(), pose)
    else org.agentos.island.DotMatrixLoader.rest(pose)
    for (i in 0..8) drawCircle(ink, 2.dp.toPx() * pose.scale[i].toFloat(),
      Offset((2 + i % 3 * 6).dp.toPx(), (2 + i / 3 * 6).dp.toPx()), alpha = pose.opacity[i].toFloat())
  }
}
