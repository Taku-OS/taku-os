package org.agentos.agent.home.design

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.caverock.androidsvg.SVG

/** Original Lucide SVGs, not font glyphs. Source revision and license live beside the assets. */
enum class LauncherMenuIcons(val asset: String) {
  Edit("layout-grid"), Share("share"), Info("info"), Remove("circle-minus"), Add("plus"), Layout("rows-3"),
}

@Composable
fun LauncherMenuIcon(icon: LauncherMenuIcons, tint: Color) {
  val context = LocalContext.current
  val size = TakuSize.iconMd
  val pixels = with(LocalDensity.current) { size.roundToPx() }
  val bitmap = remember(context, icon, pixels) {
    val svg = context.assets.open("launcher/menu/${icon.asset}.svg").use { SVG.getFromInputStream(it) }
    svg.documentWidth = pixels.toFloat()
    svg.documentHeight = pixels.toFloat()
    Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }.asImageBitmap()
  }
  // The row supplies the accessible action label; the leading icon is decorative.
  Image(bitmap, contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
