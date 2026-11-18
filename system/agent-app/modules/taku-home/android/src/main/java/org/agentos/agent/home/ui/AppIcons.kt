package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.agentos.agent.home.apps.AppCatalog
import org.agentos.agent.home.apps.LaunchableApp
import org.agentos.agent.home.apps.Slot

/** Icon bitmap rendered off the main thread and cached by [AppCatalog]. */
@Composable
fun rememberAppIcon(key: String, size: Dp): ImageBitmap? {
  val context = LocalContext.current
  val px = with(LocalDensity.current) { size.roundToPx() }
  val icon by produceState<ImageBitmap?>(null, key, px) {
    val catalog = AppCatalog.get(context)
    // A saved layout can compose before LauncherApps finishes loading. Observe the
    // app entry so that an initial miss is retried, including folder miniatures.
    catalog.apps.map { apps -> apps.firstOrNull { it.key == key } }
      .distinctUntilChanged()
      .collectLatest { app ->
        value = if (app == null) null else withContext(Dispatchers.IO) { catalog.icon(key, px) }
      }
  }
  return icon
}

/**
 * 83 dp app backplate: Surface/600 over the wallpaper blurred σ16, no stroke or shadow.
 * Core apps show their monochrome glyph (#1B1B1B), others their full icon in a 48 dp circle.
 */
@Composable
fun AppIcon(app: LaunchableApp?, key: String, backdrop: Backdrop?, plate: Dp, modifier: Modifier = Modifier) {
  val iconSize = plate * (48f / 83f)
  val icon = rememberAppIcon(key, iconSize)
  GlassSurface(backdrop, modifier.size(plate), shape = CircleShape, fill = TakuTheme.colors.surfaceGlass) {
    if (icon != null) {
      val clip = if (app?.monochrome == true) Modifier else Modifier.clip(CircleShape)
      Image(icon, null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.Center).size(iconSize).then(clip))
    }
  }
}

/** Folder backplate: up to four 25.5 dp mini icons on a 2×2 grid (gap 4) from (14, 13.85); fewer stay top-left aligned. */
@Composable
fun FolderIcon(folder: Slot.Folder, backdrop: Backdrop?, plate: Dp, modifier: Modifier = Modifier) {
  val k = plate / 83.dp
  val mini = 25.5.dp * k
  GlassSurface(backdrop, modifier.size(plate), shape = CircleShape, fill = TakuTheme.colors.surfaceGlass) {
    folder.apps.take(4).forEachIndexed { i, key ->
      val icon = rememberAppIcon(key, mini)
      val x = if (i % 2 == 0) 14.dp else 43.5.dp
      val y = if (i < 2) 13.85.dp else 43.5.dp
      Box(Modifier.offset(x * k, y * k).size(mini).clip(CircleShape).background(TakuTheme.colors.surfaceIcon)) {
        if (icon != null) Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(mini))
      }
    }
  }
}

/** Grid cell: backplate + 4 dp + label (Body/Regular/sm, Content/800, one line). */
@Composable
fun IconCell(label: String, plate: Dp, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TakuSpacing.xs), modifier = modifier) {
    icon()
    BasicText(
      label,
      style = TakuTheme.type.bodySm.copy(color = TakuTheme.colors.textSecondary, textAlign = TextAlign.Center),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.width(plate + 12.dp).height(16.dp),
    )
  }
}
