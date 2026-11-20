package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
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

/** Corner radius of icon plates as a share of their size: Figma 338:3610's 18 dp on a 60 dp tile. */
private const val ICON_CORNER = 18f / 60f

/** Rounded-rectangle outline of an app, folder or folder-miniature icon of [size]. */
fun iconShape(size: Dp): Shape = RoundedCornerShape(size * ICON_CORNER)

/**
 * App icon in a rounded rectangle (Figma 338:3610). Core apps show their monochrome glyph
 * (#1B1B1B, a 48/60 viewport) on a Surface/600 glass plate with a 1 dp Surface/400 rim. Colorful
 * icons (Taku and third-party apps) fill the whole rounded rectangle instead of a circle inside
 * the plate; this intentionally differs from the frame's 48 dp circles. Legacy, non-adaptive
 * icons keep their transparent corners over the glass.
 */
@Composable
fun AppIcon(app: LaunchableApp?, key: String, backdrop: Backdrop?, plate: Dp, modifier: Modifier = Modifier) {
  val shape = iconShape(plate)
  val glyph = app?.monochrome == true
  val iconSize = if (glyph) plate * (48f / 60f) else plate
  val icon = rememberAppIcon(key, iconSize)
  val rim = if (glyph) Modifier.border(1.dp, TakuTheme.colors.strokeFrosted, shape) else Modifier
  GlassSurface(backdrop, modifier.size(plate).then(rim), shape = shape, fill = TakuTheme.colors.surfaceGlass) {
    if (icon != null) {
      val fit = if (glyph) Modifier.align(Alignment.Center).size(iconSize) else Modifier.matchParentSize()
      Image(icon, null, contentScale = ContentScale.Fit, modifier = fit)
    }
  }
}

/**
 * Folder: the same rounded glass plate and rim as a glyph app, with up to four 25.5 dp rounded
 * miniatures on a 2×2 grid (gap 4) from (14, 13.85); fewer stay top-left aligned.
 */
@Composable
fun FolderIcon(folder: Slot.Folder, backdrop: Backdrop?, plate: Dp, modifier: Modifier = Modifier) {
  val k = plate / 83.dp
  val mini = 25.5.dp * k
  val shape = iconShape(plate)
  GlassSurface(backdrop, modifier.size(plate).border(1.dp, TakuTheme.colors.strokeFrosted, shape), shape = shape, fill = TakuTheme.colors.surfaceGlass) {
    folder.apps.take(4).forEachIndexed { i, key ->
      val icon = rememberAppIcon(key, mini)
      val x = if (i % 2 == 0) 14.dp else 43.5.dp
      val y = if (i < 2) 13.85.dp else 43.5.dp
      Box(Modifier.offset(x * k, y * k).size(mini).clip(iconShape(mini)).background(TakuTheme.colors.surfaceIcon)) {
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
