package org.agentos.agent.home.menuscan

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal data class MenuLine(val title: String, val price: String, val bounds: RectF)

internal data class MenuScan(val rows: List<MenuLine>, val contextText: String) {
  fun toJson(): JSONObject = JSONObject().put("menuText", contextText.take(3_500)).put("dishes", JSONArray().apply {
    rows.take(40).forEach { put(JSONObject().put("name", it.title.take(96)).put("printedPrice", it.price)) }
  })
}

internal object MenuRecognition {
  private val price = Regex(
    """(?i)(?<![\p{L}\d])(?:(?:US\$|CA\$|A\$|NZ\$|HK\$|[$€£¥₹₩₽]|(?:USD|CAD|AUD|EUR|GBP|JPY|CNY|CHF|INR)\s+)\s*\d{1,6}(?:[.,]\d{1,2})?|\d{1,6}(?:[.,]\d{1,2})?\s*(?:USD|CAD|AUD|EUR|GBP|JPY|CNY|CHF|INR|€|£)|\d{1,5}[.,]\d{2})$""",
  )
  private val letters = Regex("""\p{L}""")
  private val receipt = Regex("""(?im)^\s*(?:subtotal|amount\s+due|change\s+due|cash\s+tendered|receipt\s*(?:no|#)|payment\s+method)\b""")

  fun read(bitmap: Bitmap): MenuScan? {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val cells = try {
      Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 12, TimeUnit.SECONDS)
        .textBlocks.flatMap { it.lines }.mapNotNull { line ->
          val bounds = line.boundingBox ?: return@mapNotNull null
          val text = line.text.trim().replace(Regex("""\s+"""), " ").take(180)
          if (text.isEmpty()) null else text to RectF(bounds)
        }.sortedWith(compareBy({ it.second.centerY() }, { it.second.left })).take(180)
    } finally {
      recognizer.close()
    }
    val printedText = cells.joinToString("\n") { it.first }
    if (receipt.containsMatchIn(printedText)) return null
    val rows = cells.mapNotNull { (text, bounds) ->
      val match = price.find(text) ?: return@mapNotNull null
      val inline = text.substring(0, match.range.first).trimEnd(' ', '.', '·', '–', '—')
      val titleCell = if (inline.isNotEmpty()) null else cells.filter { (candidate, box) ->
        box.right <= bounds.left && abs(box.centerY() - bounds.centerY()) < minOf(box.height(), bounds.height()) * .65 &&
          letters.containsMatchIn(candidate) && !price.containsMatchIn(candidate)
      }.maxByOrNull { it.second.right }
      val title = titleCell?.first ?: inline
      if (letters.findAll(title).take(3).count() < 3) return@mapNotNull null
      val full = RectF(bounds).apply { titleCell?.let { union(it.second) } }
      val relative = RectF(full.left / bitmap.width, full.top / bitmap.height, full.right / bitmap.width, full.bottom / bitmap.height)
      MenuLine(title, match.value.trim(), relative)
    }.distinctBy { it.title.lowercase() to it.price }.take(60)
    return rows.takeIf { it.size >= 2 }?.let { MenuScan(it, printedText.take(7_000)) }
  }
}
