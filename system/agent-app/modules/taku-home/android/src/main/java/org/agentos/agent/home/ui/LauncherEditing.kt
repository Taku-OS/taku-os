package org.agentos.agent.home.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.agentos.agent.home.R
import org.agentos.agent.home.apps.AppCatalog
import org.agentos.agent.home.apps.HomeMode
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.design.*
import org.agentos.agent.home.ui.effects.Shaders
import kotlin.math.roundToInt

/** Samples the live home scene, keeping labels sharp above the frosted, refracting surface. */
@Composable
fun LauncherGlass(scene: GraphicsLayer, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  var origin by remember { mutableStateOf(Offset.Zero) }
  val region = rememberGraphicsLayer()
  val density = LocalDensity.current.density
  val effectKey = remember { arrayOfNulls<Any>(1) }
  val colors = TakuTheme.colors
  val shape = RoundedCornerShape(26.dp)
  Box(modifier.shadow(TakuElevation.menu, shape).onGloballyPositioned { origin = it.positionInRoot() }.clip(shape)) {
    Canvas(Modifier.matchParentSize()) {
      val dimensions = IntSize(size.width.roundToInt(), size.height.roundToInt())
      if (Build.VERSION.SDK_INT >= 33 && dimensions.width > 0 && dimensions.height > 0) {
        if (effectKey[0] != (dimensions to density)) {
          region.renderEffect = Shaders.menuGlass(size.width, size.height, density).asComposeRenderEffect()
          effectKey[0] = dimensions to density
        }
        region.clip = true
        region.record(dimensions) { translate(-origin.x, -origin.y) { drawLayer(scene) } }
        drawLayer(region)
      } else drawRect(colors.surfaceCard)
    }
    content()
  }
}

private data class LauncherAction(val title: String, val icon: LauncherMenuIcons, val destructive: Boolean = false, val run: () -> Unit)

@Composable
private fun MenuRows(actions: List<LauncherAction>) {
  Column(Modifier.padding(vertical = 6.dp)) {
    actions.forEach { action ->
      Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = action.run).semantics { role = Role.Button }
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val ink = if (action.destructive) TakuTheme.colors.accentDestructive else TakuTheme.colors.textPrimary
        LauncherMenuIcon(action.icon, tint = ink)
        Spacer(Modifier.width(16.dp))
        BasicText(action.title, Modifier.weight(1f), style = TakuTheme.type.bodyLg.copy(color = ink))
      }
    }
  }
}

@Composable
private fun LauncherDropdown(anchor: Rect, scene: GraphicsLayer, actions: List<LauncherAction>) {
  val density = LocalDensity.current
  val top = with(density) { WindowInsets.statusBars.getTop(this) } + with(density) { 12.dp.roundToPx() }
  val bottom = with(density) { WindowInsets.navigationBars.getBottom(this) } + with(density) { 16.dp.roundToPx() }
  Layout(content = { LauncherGlass(scene) { MenuRows(actions) } }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
    val margin = 16.dp.roundToPx(); val gap = 10.dp.roundToPx()
    val width = minOf(280.dp.roundToPx(), constraints.maxWidth - margin * 2)
    val menu = measurables.single().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = (constraints.maxHeight - top - bottom).coerceAtLeast(0)))
    val x = (anchor.center.x - width / 2).roundToInt().coerceIn(margin, (constraints.maxWidth - width - margin).coerceAtLeast(margin))
    val below = anchor.bottom.roundToInt() + gap
    val y = (if (below + menu.height <= constraints.maxHeight - bottom) below else anchor.top.roundToInt() - gap - menu.height)
      .coerceIn(top, (constraints.maxHeight - menu.height - bottom).coerceAtLeast(top))
    layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x, y) }
  }
}

@Composable
fun LauncherIconMenu(controller: HomeController, scene: GraphicsLayer) {
  val target = controller.drag.menu ?: return
  BackHandler { controller.drag.menu = null }
  val actions = buildList {
    add(LauncherAction(stringResource(R.string.home_edit_screen), LauncherMenuIcons.Edit) { controller.setHomeEditing(true) })
    when (val slot = target.slot) {
      is Slot.Widget -> {
        add(LauncherAction("Resize widget", LauncherMenuIcons.Layout) { controller.drag.menu = null; controller.resizingWidget = slot })
        if (controller.widgetHost.info(slot.appWidgetId)?.configure != null) add(LauncherAction("Configure widget", LauncherMenuIcons.Edit) { controller.configureWidget(slot.appWidgetId) })
        add(LauncherAction("Remove widget", LauncherMenuIcons.Remove, true) { controller.removeWidget(slot.appWidgetId) })
      }
      is Slot.App -> {
        add(LauncherAction(stringResource(R.string.home_workspace_launch), LauncherMenuIcons.Layout) { controller.launchWorkspace(slot.key, target.label) })
        add(LauncherAction(stringResource(R.string.home_share_app), LauncherMenuIcons.Share) { controller.shareApp(slot.key) })
        add(LauncherAction(stringResource(R.string.home_app_info), LauncherMenuIcons.Info) { controller.appInfo(slot.key) })
        add(LauncherAction(stringResource(R.string.home_remove_app), LauncherMenuIcons.Remove, true) { controller.requestRemove(slot.key) })
      }
      is Slot.Folder -> add(LauncherAction(stringResource(R.string.home_ungroup), LauncherMenuIcons.Remove, true) { controller.ungroup(slot.id) })
    }
  }
  val appear = rememberAppear(target)
  val tint = TakuTheme.colors.scrimLight
  val clearIcon = remember(target.iconBounds) { Path().apply { addOval(target.iconBounds) } }
  Scrim(appear, { controller.drag.menu = null }, color = Color.Transparent) {
    // Keep the original icon above the dimming effect without drawing a duplicate or changing
    // its gesture/semantics. Bounds come from the icon plate, excluding its label, even in folders.
    Canvas(Modifier.fillMaxSize()) {
      clipPath(clearIcon, clipOp = ClipOp.Difference) { drawRect(tint, alpha = appear) }
    }
    LauncherDropdown(target.anchor, scene, actions)
  }
}

@Composable
fun LauncherHomeMenu(controller: HomeController, scene: GraphicsLayer) {
  val at = controller.homeMenu ?: return
  BackHandler { controller.closeHomeMenu() }
  val actions = buildList {
    if (!controller.editingHome) add(LauncherAction(stringResource(R.string.home_edit_screen), LauncherMenuIcons.Edit) { controller.setHomeEditing(true) })
    add(LauncherAction("Add widget", LauncherMenuIcons.Add) { controller.closeHomeMenu(); controller.showingWidgetPicker = true })
    add(LauncherAction(stringResource(R.string.home_hidden_apps), LauncherMenuIcons.Add) { controller.closeHomeMenu(); controller.showingHiddenApps = true })
    val scrolling = controller.homeLayout.mode == HomeMode.SCROLL
    add(LauncherAction(stringResource(if (scrolling) R.string.home_pages else R.string.home_scroll), LauncherMenuIcons.Layout) { controller.setHomeMode(if (scrolling) HomeMode.PAGES else HomeMode.SCROLL) })
  }
  Scrim(rememberAppear(at), { controller.closeHomeMenu() }, color = TakuTheme.colors.scrimLight) {
    LauncherDropdown(Rect(at, androidx.compose.ui.geometry.Size.Zero), scene, actions)
  }
}

@Composable
fun HomeEditingToolbar(controller: HomeController, scene: GraphicsLayer, top: Dp) {
  BackHandler(enabled = controller.editingHome && controller.openFolderId == null) { controller.setHomeEditing(false) }
  Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = top + 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    var anchor by remember { mutableStateOf(Offset.Zero) }
    LauncherGlass(scene, Modifier.onGloballyPositioned { anchor = it.positionInRoot() + Offset(0f, it.size.height.toFloat()) }) {
      BasicText(stringResource(R.string.home_edit), Modifier.clickable { controller.openHomeMenu(anchor) }.padding(horizontal = 22.dp, vertical = 10.dp), style = TakuTheme.type.bodyLg)
    }
    LauncherGlass(scene) {
      BasicText(stringResource(R.string.home_done), Modifier.clickable { controller.setHomeEditing(false) }.padding(horizontal = 22.dp, vertical = 10.dp), style = TakuTheme.type.bodyLg)
    }
  }
}

@Composable
fun LauncherRemoval(controller: HomeController, backdrop: Backdrop?) {
  val key = controller.removingApp ?: return
  val catalog = remember { AppCatalog.get(controller.context) }
  val app = catalog.app(key)
  if (app == null) { LaunchedEffect(key) { controller.removingApp = null }; return }
  val close = { controller.removingApp = null }
  TakuDialog(close, rememberAppear(key), backdrop = backdrop) {
    BasicText(stringResource(R.string.home_remove_title, app.label), style = TakuTheme.type.headingXl)
    BasicText(stringResource(R.string.home_remove_message), style = TakuTheme.type.bodyMd)
    TakuButton(stringResource(R.string.home_remove_from_home), { controller.hideApp(key) }, Modifier.fillMaxWidth())
    if (!app.system) TakuButton(stringResource(R.string.home_uninstall), { close(); controller.uninstall(key) }, Modifier.fillMaxWidth(), style = ButtonStyle.Frosted)
    TakuButton(stringResource(R.string.home_cancel), close, Modifier.fillMaxWidth(), style = ButtonStyle.Glass)
  }
}

@Composable
fun LauncherHiddenApps(controller: HomeController, backdrop: Backdrop?) {
  if (!controller.showingHiddenApps) return
  val catalog = remember { AppCatalog.get(controller.context) }
  val close = { controller.showingHiddenApps = false }
  TakuDialog(close, rememberAppear(true), backdrop = backdrop) {
    BasicText(stringResource(R.string.home_hidden_apps), style = TakuTheme.type.headingXl)
    val apps = catalog.apps.value.filter { it.key in controller.homeLayout.hidden }
    if (apps.isEmpty()) BasicText(stringResource(R.string.home_no_hidden_apps), style = TakuTheme.type.bodyLg)
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
      apps.forEach { app ->
        Row(Modifier.fillMaxWidth().clickable { controller.restoreApp(app.key) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          AppIcon(app, app.key, null, 40.dp)
          BasicText(app.label, Modifier.weight(1f).padding(horizontal = 12.dp), style = TakuTheme.type.bodyLg, maxLines = 1, overflow = TextOverflow.Ellipsis)
          BasicText("+", style = TakuTheme.type.headingXl)
        }
      }
    }
    TakuButton(stringResource(R.string.home_done), close, Modifier.fillMaxWidth())
  }
}

@Composable
internal fun LauncherWorkspaceFailure(controller: HomeController, backdrop: Backdrop?) {
  val message = controller.workspaceLaunchFailure ?: return
  val close = { controller.workspaceLaunchFailure = null }
  TakuDialog(close, rememberAppear(message), backdrop = backdrop) {
    BasicText(stringResource(R.string.home_workspace_launch_failure_title), style = TakuTheme.type.headingXl)
    BasicText(message, style = TakuTheme.type.bodyMd)
    TakuButton(stringResource(R.string.home_done), close, Modifier.fillMaxWidth())
  }
}

@Composable
internal fun LauncherWorkspaceTakeover(controller: HomeController, backdrop: Backdrop?) {
  val target = controller.workspaceTakeover ?: return
  val dismiss = controller::dismissWorkspaceTakeover
  val title = if (target.screen.controlOwner == "user") R.string.home_workspace_takeover_owned_title
    else R.string.home_workspace_takeover_title
  TakuDialog(dismiss, rememberAppear(target), backdrop = backdrop) {
    BasicText(stringResource(title, target.appLabel), style = TakuTheme.type.headingXl)
    TakuButton(
      stringResource(R.string.home_workspace_takeover),
      { controller.takeOverWorkspace(target) },
      Modifier.fillMaxWidth(),
      style = ButtonStyle.Primary,
      enabled = !controller.workspaceControlsUnavailable(target.screen),
    )
    TakuButton(stringResource(R.string.home_cancel), dismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Glass)
  }
}
