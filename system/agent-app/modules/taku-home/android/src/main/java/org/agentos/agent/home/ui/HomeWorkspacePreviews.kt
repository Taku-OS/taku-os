package org.agentos.agent.home.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.R
import org.agentos.agent.home.design.TakuElevation
import org.agentos.agent.home.design.TakuIcon
import org.agentos.agent.home.design.TakuIcons
import org.agentos.agent.home.design.TakuMotion
import org.agentos.agent.home.design.TakuRadius
import org.agentos.agent.home.design.TakuSize
import org.agentos.agent.home.design.TakuSpacing
import org.agentos.agent.home.design.TakuTheme

private val PreviewWidth = 104.dp
private val PreviewHeight = 176.dp

@Composable
internal fun BoxScope.HomeWorkspacePreviews(controller: HomeController) {
  val screens = controller.workspaceScreens
  if (screens.isEmpty()) return

  val visibility = remember { MutableTransitionState(initialState = false) }
  var opening by remember { mutableIntStateOf(0) }
  val expanded = visibility.targetState
  val showing = expanded || visibility.currentState || !visibility.isIdle
  val close = { visibility.targetState = false }
  BackHandler(enabled = showing, onBack = close)

  if (showing) {
    Box(Modifier.matchParentSize().clickable(
      interactionSource = null,
      indication = null,
      onClickLabel = stringResource(R.string.home_workspace_hide_previews),
      onClick = close,
    ))
  }

  BoxWithConstraints(
    modifier = Modifier.align(BiasAbsoluteAlignment(horizontalBias = 1f, verticalBias = 0.62f))
      .height(PreviewHeight),
    contentAlignment = AbsoluteAlignment.CenterRight,
  ) {
    if (!showing) WorkspaceBookmark(screens.size) {
      opening++
      visibility.targetState = true
    }

    val gap = TakuSpacing.xs
    val margin = TakuSpacing.sm
    val visibleCount = ((maxWidth - margin + gap) / (PreviewWidth + gap)).toInt().coerceAtLeast(1)
    val edge = TransformOrigin(pivotFractionX = 1f, pivotFractionY = 0.5f)
    AnimatedVisibility(
      visibleState = visibility,
      enter = slideInHorizontally(TakuMotion.settle()) { it / 3 } +
        scaleIn(TakuMotion.settle(), initialScale = 0.55f, transformOrigin = edge) + fadeIn(TakuMotion.scrim()),
      exit = slideOutHorizontally(TakuMotion.settle()) { it / 3 } +
        scaleOut(TakuMotion.settle(), targetScale = 0.55f, transformOrigin = edge) + fadeOut(TakuMotion.scrim()),
    ) {
      val frames = remember(opening) { mutableStateMapOf<Pair<String, Int>, Bitmap?>() }
      Row(
        modifier = Modifier.absolutePadding(right = margin).pointerInput(Unit) { detectTapGestures {} },
        horizontalArrangement = Arrangement.spacedBy(gap),
      ) {
        screens.take(visibleCount).forEach { screen ->
          val identity = screen.previewUri to screen.displayId
          key(identity) {
            if (expanded) {
              LaunchedEffect(opening) {
                // Keep the placeholder for this expansion if the frame is unavailable.
                if (identity in frames) return@LaunchedEffect
                val frame = controller.readWorkspacePreview(screen.previewUri)
                val stillListed = controller.workspaceScreens.any {
                  it.previewUri == screen.previewUri && it.displayId == screen.displayId
                }
                if (visibility.targetState && stillListed) frames[identity] = frame
              }
            }
            WorkspacePreviewCard(status = screen.status, frame = frames[identity])
          }
        }
      }
    }
  }
}

@Composable
private fun WorkspaceBookmark(count: Int, onOpen: () -> Unit) {
  val colors = TakuTheme.colors
  Box(
    modifier = Modifier.size(width = 48.dp, height = 88.dp).clickable(
      interactionSource = null,
      indication = null,
      role = Role.Button,
      onClickLabel = stringResource(R.string.home_workspace_show_previews),
      onClick = onOpen,
    ),
    contentAlignment = AbsoluteAlignment.CenterRight,
  ) {
    Column(
      modifier = Modifier.size(width = 32.dp, height = 88.dp)
        .clip(AbsoluteRoundedCornerShape(topLeft = TakuRadius.lg, bottomLeft = TakuRadius.lg))
        .background(colors.surfaceInverse),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      TakuIcon(TakuIcons.WorkspaceScreen, TakuSize.iconMd, tint = colors.iconOnInverse)
      BasicText(count.toString(), style = TakuTheme.type.bodySmMedium.copy(color = colors.textOnInverse))
    }
  }
}

@Composable
private fun WorkspacePreviewCard(status: String, frame: Bitmap?) {
  val colors = TakuTheme.colors
  val shape = RoundedCornerShape(TakuRadius.md)
  val label = when (status) {
    "running" -> R.string.home_workspace_running
    "waiting_for_app", "waiting_for_user" -> R.string.home_workspace_waiting
    "paused" -> R.string.home_workspace_paused
    "user_control" -> R.string.home_workspace_user_control
    "stopping" -> R.string.home_workspace_switching
    else -> R.string.home_workspace_unavailable
  }
  Box(
    Modifier.size(width = PreviewWidth, height = PreviewHeight)
      .shadow(TakuElevation.badge, shape)
      .background(colors.fillPlaceholder)
      .border(1.dp, colors.strokeHairline, shape),
  ) {
    if (frame != null) Image(
      bitmap = remember(frame) { frame.asImageBitmap() },
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Fit,
    )
    BasicText(
      text = stringResource(label),
      modifier = Modifier.align(AbsoluteAlignment.TopLeft).padding(TakuSpacing.xs)
        .clip(CircleShape).background(colors.surfaceInverse)
        .padding(horizontal = TakuSpacing.sm, vertical = TakuSpacing.xxs),
      style = TakuTheme.type.bodySmMedium.copy(color = colors.textOnInverse),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
