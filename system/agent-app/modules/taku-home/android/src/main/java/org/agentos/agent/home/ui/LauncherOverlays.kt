package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.agentos.agent.home.apps.AppCatalog
import org.agentos.agent.home.apps.HomeMode
import org.agentos.agent.home.apps.Slot
import kotlin.math.roundToInt

/** The icon lifted under the finger while dragging (scaled 1.1, soft shadow). */
@Composable
fun DragOverlay(controller: HomeController, backdrop: Backdrop?) {
  val drag = controller.drag
  val item = drag.item ?: return
  val density = LocalDensity.current
  val lift = remember(item) { Animatable(1f) }
  LaunchedEffect(item) { lift.animateTo(1.1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
  val plate = with(density) { drag.iconSize.toDp() }
  val catalog = remember { AppCatalog.get(controller.context) }
  DropIndicator(controller)
  Box(
    Modifier
      .offset { (drag.pointer - drag.grab).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
      .graphicsLayer { scaleX = lift.value; scaleY = lift.value; shadowElevation = TakuElevation.lifted.toPx(); shape = if (item.slot is Slot.Widget) CircleShape else iconShape(plate); clip = false },
  ) {
    when (val slot = item.slot) {
      is Slot.Widget -> WidgetDragPreview(controller, slot, plate)
      is Slot.App -> AppIcon(catalog.app(slot.key), slot.key, backdrop, plate)
      is Slot.Folder -> FolderIcon(slot, backdrop, plate)
    }
  }
}

/** The icon the lifted app would join (folder target): a ring around it. Gaps show by the icons sliding aside. */
@Composable
private fun DropIndicator(controller: HomeController) {
  val target = controller.drag.target ?: return
  val intoKey = target.intoKey ?: return
  val index = controller.pageItems(target.page).indexOfFirst { it.itemKey == intoKey }
  val rect = controller.drag.grids[target.page]?.takeIf { index >= 0 }?.plateRect(index) ?: return
  val ring = TakuTheme.colors.strokeDropTargetActive
  Canvas(Modifier.fillMaxSize()) {
    drawCircle(ring, radius = rect.width / 2 + 3.dp.toPx(), center = rect.center, style = Stroke(1.5.dp.toPx()))
  }
}

/** An opened folder: its name (editable) and apps; tap to open, drag one out to move it, long-press for App info. */
@Composable
fun FolderSheet(controller: HomeController, backdrop: Backdrop?) {
  val id = controller.openFolderId ?: return
  val catalog = remember { AppCatalog.get(controller.context) }
  val folder = controller.homeLayout.folder(id)
  if (folder == null) { LaunchedEffect(id) { controller.openFolderId = null }; return }
  val appear = rememberAppear(id)
  var name by remember(id, folder.name) { mutableStateOf(folder.name) }
  val close = {
    if (name.isNotBlank() && name != folder.name) controller.renameFolder(id, name)
    controller.openFolderId = null
  }
  // While an app is dragged out of the folder the sheet steps aside (it stays composed so the gesture lives on).
  var editing by remember(id) { mutableStateOf(false) }
  // Issue #32 H7: a title, not a search-like field; tapping it renames. The card is frosted
  // wallpaper and the plates sit on the card, so nothing under the sheet shows through.
  TakuDialog(onDismiss = close, appear = appear, backdrop = backdrop, modifier = Modifier.graphicsLayer { alpha = if (controller.drag.item != null) 0f else 1f }) {
    if (editing) TakuTextField(name, { name = it }, placeholder = "Folder name", maxLength = 24, onDone = { editing = false; if (name.isNotBlank() && name != folder.name) controller.renameFolder(id, name) },
      modifier = Modifier.fillMaxWidth())
    else BasicText(name.ifBlank { folder.name }, style = TakuTheme.type.headingXl.copy(color = TakuTheme.colors.textPrimary, textAlign = TextAlign.Center), maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth().clickable { editing = true }.semantics { contentDescription = "Folder ${folder.name}, tap to rename"; role = Role.Button })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      // Four cells (plate + 12 dp label room) and three gaps must fit the card's width.
      val gap = TakuSpacing.md
      val plate = minOf(64.dp, (maxWidth - gap * 3) / 4 - 12.dp)
      Column(verticalArrangement = Arrangement.spacedBy(TakuSpacing.lg)) {
        folder.apps.chunked(4).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            row.forEach { key ->
              val app = catalog.app(key)
              DraggableIcon(controller, Slot.App(key), app?.label ?: "", page = null, index = null, plate = plate) {
                AppIcon(app, key, null, plate)
              }
            }
          }
        }
      }
    }
  }
}
