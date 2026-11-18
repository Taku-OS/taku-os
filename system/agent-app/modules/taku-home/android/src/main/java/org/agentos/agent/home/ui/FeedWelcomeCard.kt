package org.agentos.agent.home.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import com.caverock.androidsvg.SVG
import org.agentos.agent.home.design.*
import org.agentos.agent.home.feed.FeedWelcome

/** Empty Feed invitation using the card, inset and primary action from Figma 1:1901 / 1:2001. */
@Composable
fun FeedWelcomeCard(backdrop: Backdrop?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
  val c = TakuTheme.colors
  val type = TakuTheme.type
  GlassSurface(backdrop, modifier.fillMaxWidth().clip(RoundedCornerShape(TakuRadius.card))
    .clickable(role = Role.Button, onClickLabel = FeedWelcome.actionLabel, onClick = onOpen)) {
    Column(Modifier.padding(TakuSpacing.md), verticalArrangement = Arrangement.spacedBy(TakuSpacing.md)) {
      BasicText(FeedWelcome.app, style = type.bodyMd.copy(color = c.textSecondary))
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(TakuRadius.lg)).background(c.fillPlaceholder)
        .padding(TakuSpacing.md), verticalArrangement = Arrangement.spacedBy(TakuSpacing.sm)) {
        BasicText(FeedWelcome.title, style = type.bodyLgMedium)
        BasicText(FeedWelcome.message, style = type.bodyMd.copy(color = c.textSecondary))
      }
      TakuButton(FeedWelcome.actionLabel, onOpen, Modifier.fillMaxWidth(), style = ButtonStyle.Primary,
        leading = { AskTakuIcon() })
    }
  }
}

/** Original 16×16 Figma AI asset; rasterize at display density without changing the SVG geometry. */
@Composable
private fun AskTakuIcon() {
  val context = LocalContext.current
  val pixels = with(LocalDensity.current) { TakuSize.iconSm.roundToPx() }
  val bitmap = remember(context, pixels) {
    val svg = context.assets.open("feed/icons/ask-taku.svg").use { SVG.getFromInputStream(it) }
    Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also {
      val canvas = Canvas(it)
      canvas.scale(pixels / svg.documentWidth, pixels / svg.documentHeight)
      svg.renderToCanvas(canvas)
    }.asImageBitmap()
  }
  Image(bitmap, contentDescription = null, modifier = Modifier.size(TakuSize.iconSm))
}
