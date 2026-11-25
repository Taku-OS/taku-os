package org.agentos.agent.home.menuscan

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs

internal class MenuReviewAnnotations(density: Float, labelTextSize: Float) {
  private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffc14317.toInt() }
  private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfffff1df.toInt() }
  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffe75d26.toInt() }
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xffffffff.toInt()
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    textSize = labelTextSize
  }
  private val padding = 6 * density
  private val starRadius = 4 * density
  private val starGap = 4 * density
  private val labelHeight = maxOf(22 * density, textPaint.fontSpacing + 6 * density)
  private val dotRadius = 2 * density
  private val gap = 3 * density
  private val clearance = 2 * density
  private val star = Path().apply {
    val inner = starRadius * .27f
    moveTo(0f, -starRadius)
    lineTo(inner, -inner)
    lineTo(starRadius, 0f)
    lineTo(inner, inner)
    lineTo(0f, starRadius)
    lineTo(-inner, inner)
    lineTo(-starRadius, 0f)
    lineTo(-inner, -inner)
    close()
  }
  private var placements = emptyList<Placement>()

  fun layout(scan: MenuScan, rows: List<RectF>, frame: RectF): Map<Int, String> {
    val textLines = scan.textLines.map { it.text to frame.mapMenuBounds(it.bounds) }
    val details = textLines.groupBy { (_, bounds) -> descriptionOwner(bounds, rows) }
    val occupied = textLines.mapTo(mutableListOf()) { (_, bounds) ->
      RectF(bounds).apply { inset(-clearance, -clearance) }
    }
    placements = buildList {
      for ((index, dish) in scan.rows.withIndex()) {
        val label = printedCue(dish.title)
          ?: details[index].orEmpty().firstNotNullOfOrNull { printedCue(it.first) }
          ?: "Soup".takeIf { soup.containsMatchIn(dish.title) }
          ?: continue
        val placement = place(index, label, rows[index], frame, occupied) ?: continue
        add(placement)
        occupied.add(placement.footprint)
        if (size == 3) break
      }
    }
    return placements.associate { it.index to it.label }
  }

  fun draw(canvas: Canvas) {
    for (placement in placements) {
      val pill = placement.pill
      val center = pill.centerY()
      val radius = pill.height() / 2
      canvas.drawRoundRect(pill, radius, radius, pillPaint)
      canvas.drawCircle(placement.dotX, placement.dotY, dotRadius, dotPaint)
      val starX = pill.left + padding + starRadius
      canvas.save()
      canvas.translate(starX, center)
      canvas.drawPath(star, accentPaint)
      canvas.restore()
      val baseline = center - (textPaint.ascent() + textPaint.descent()) / 2
      canvas.drawText(placement.label, starX + starRadius + starGap, baseline, textPaint)
    }
  }

  private fun descriptionOwner(bounds: RectF, rows: List<RectF>): Int? =
    rows.indices.filter { index ->
      val row = rows[index]
      val height = maxOf(row.height(), bounds.height())
      val distance = bounds.top - row.bottom
      distance >= 0 && distance <= height * 1.5f && abs(bounds.left - row.left) <= height * 2
    }.maxByOrNull { rows[it].bottom }

  private fun place(index: Int, label: String, row: RectF, frame: RectF, occupied: List<RectF>): Placement? {
    val width = 2 * padding + 2 * starRadius + starGap + textPaint.measureText(label)
    val center = row.centerY()
    val offset = 2 * gap + 2 * dotRadius
    val beside = sequenceOf(
      Placement(index, label, RectF(row.right + offset, center - labelHeight / 2,
        row.right + offset + width, center + labelHeight / 2), row.right + gap + dotRadius, center),
      Placement(index, label, RectF(row.left - offset - width, center - labelHeight / 2,
        row.left - offset, center + labelHeight / 2), row.left - gap - dotRadius, center),
    )
    val stacked = sequence {
      for (top in listOf(row.bottom + offset, row.top - offset - labelHeight)) {
        val below = top > row.bottom
        val dotY = if (below) row.bottom + gap + dotRadius else row.top - gap - dotRadius
        val bottom = top + labelHeight
        val footprintTop = minOf(top, dotY - dotRadius)
        val footprintBottom = maxOf(bottom, dotY + dotRadius)
        val preferredLeft = row.right - width
        val textGapEdges = occupied.asSequence()
          .filter { it.top < footprintBottom && it.bottom > footprintTop }
          .flatMap { sequenceOf(it.left - width, it.right) }
          .sortedBy { abs(it - preferredLeft) }
        val alignments = sequenceOf(preferredLeft, row.left, row.centerX() - width / 2) + textGapEdges
        for (left in alignments) {
          val dotX = (left + width / 2).coerceIn(row.left, row.right)
          if (dotX in left..(left + width)) {
            yield(Placement(index, label, RectF(left, top, left + width, bottom), dotX, dotY))
          }
        }
      }
    }
    return (beside + stacked).firstOrNull { candidate ->
      frame.contains(candidate.footprint) && occupied.none { RectF.intersects(it, candidate.footprint) }
    }
  }

  private inner class Placement(
    val index: Int,
    val label: String,
    val pill: RectF,
    val dotX: Float,
    val dotY: Float,
  ) {
    val footprint = RectF(pill).apply {
      union(dotX - dotRadius, dotY - dotRadius, dotX + dotRadius, dotY + dotRadius)
    }
  }

  private fun printedCue(text: String): String? {
    if (negation.containsMatchIn(text)) return null
    return cues.firstOrNull { (cue, _) -> cue.containsMatchIn(text) }?.second
  }

  private companion object {
    val negation = Regex("""\b(?:no|not|never|without|non-)\b""", RegexOption.IGNORE_CASE)
    val soup = Regex("""\bsoup\b""", RegexOption.IGNORE_CASE)
    val cues = listOf(
      """\b(?:to share|for sharing)\b""" to "Good for sharing",
      """\b(?:warm|hot)\s*(?:&|and)\s*savou?ry\b""" to "Warm & savoury",
      """\bfresh\s*(?:&|and)\s*tangy\b""" to "Fresh & tangy",
      """^house[-‐‑ ]smoked\b""" to "House-smoked",
      """^smoked\b""" to "Smoked",
      """^dry[-‐‑ ]cured\b""" to "Dry-cured",
      """^slow[-‐‑ ]roasted\b""" to "Slow-roasted",
    ).map { (pattern, label) -> Regex(pattern, RegexOption.IGNORE_CASE) to label }
  }
}

internal fun RectF.mapMenuBounds(box: RectF) = RectF(
  left + box.left * width(),
  top + box.top * height(),
  left + box.right * width(),
  top + box.bottom * height(),
)
