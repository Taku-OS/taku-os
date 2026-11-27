package org.agentos.agent.home.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.agentos.agent.home.assistant.*
import org.agentos.agent.home.design.TakuSpacing
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.design.TakuIcon
import org.agentos.agent.home.design.TakuIcons

/** Original dining Compose bodies, hosted without restoring the retired general island. */
@Composable
internal fun ReplyOptionsOverlay(state: AssistantState, session: AssistantSession) {
  val share = state.share
  TopIslandFrame(share?.id, share?.busy,
    fullHeight = state.island == IslandMode.Full || share != null,
    onOutside = { candidateBack(session) }, onOpenConversation = session::openConversation) {
    ReplyOptionsIslandBody(state, session)
  }
}

/** One shell for results and dining confirmations: the same top position, eyes and design tokens. */
@Composable
internal fun TopIslandFrame(identity: Any?, busy: Boolean?, fullHeight: Boolean = false,
  onOutside: () -> Unit, onOpenConversation: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().pointerInput(identity, busy) { detectTapGestures { onOutside() } })
    val density = LocalDensity.current
    val bottom = with(density) { maxOf(WindowInsets.ime.getBottom(this), WindowInsets.navigationBars.getBottom(this)).toDp() }
    val available = (maxHeight - bottom - 24.dp).coerceAtLeast(96.dp)
    val cap = if (fullHeight) available
      else minOf(available, maxHeight * .72f)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp)
      .fillMaxWidth().heightIn(max = cap).clip(RoundedCornerShape(40.dp))
      .background(TakuTheme.colors.islandSurface.compositeOver(TakuTheme.colors.canvas))
      .padding(start = TakuSpacing.lg, end = TakuSpacing.lg, top = TakuSpacing.lg)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { CandidateEyes() }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = busy != true, role = Role.Button) { onOpenConversation() }
          .semantics { contentDescription = "Open conversation" }, contentAlignment = Alignment.Center) {
          TakuIcon(TakuIcons.Expand, 20.dp, tint = TakuTheme.colors.islandInk)
        }
      }
      Spacer(Modifier.height(TakuSpacing.xs))
      content()
    }
  }
}

/** The original island's fixed-size eyes; blinking changes pixels, never layout. */
@Composable
private fun CandidateEyes() {
  val ink = TakuTheme.colors.islandInk
  val blink = remember { Animatable(1f) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(3800L + (System.nanoTime() % 1800L))
      blink.animateTo(.12f, tween(70)); blink.animateTo(1f, tween(110))
    }
  }
  Canvas(Modifier.size(16.dp, 12.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
    val width = 6.dp.toPx(); val height = size.height * blink.value
    repeat(2) { eye -> drawRoundRect(ink, topLeft = Offset(eye * 10.dp.toPx(), (size.height-height)/2f),
      size = Size(width,height), cornerRadius = CornerRadius(width/2f)) }
  }
}
