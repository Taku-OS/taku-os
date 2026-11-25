package org.agentos.agent.home.menuscan

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.annotation.RequiresApi

@RequiresApi(28)
class MenuReviewActivity : Activity() {
  private var id = ""
  private var bitmap: Bitmap? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    id = intent.data?.lastPathSegment.orEmpty()
    val review = MenuCapture.review(id) ?: return finish()
    val photo = runCatching { decodeMenuPhoto(review.photo, 2_560) }.getOrNull() ?: return finish()
    bitmap = photo
    setContentView(object : View(this) {
      private val frame = RectF()
      private var highlights = emptyList<RectF>()
      private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x34ff9b46 }
      private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffa35d.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
      }
      private val annotations = MenuReviewAnnotations(
        resources.displayMetrics.density,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics),
      )

      init {
        contentDescription = review.scan.rows.joinToString(separator = ". ") { "${it.title}, ${it.price}" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
      }

      override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = minOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
        val imageWidth = photo.width * scale
        val imageHeight = photo.height * scale
        val left = (w - imageWidth) / 2f
        val top = (h - imageHeight) / 2f
        frame.set(left, top, left + imageWidth, top + imageHeight)
        val rows = review.scan.rows.map { frame.mapMenuBounds(it.bounds) }
        highlights = rows.map { RectF(it).apply { inset(-4f, -4f) } }
        val labels = annotations.layout(review.scan, rows, frame)
        contentDescription = review.scan.rows.mapIndexed { index, row ->
          listOfNotNull(row.title, row.price, labels[index]).joinToString(", ")
        }.joinToString(". ")
      }

      override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(photo, null, frame, paint)
        canvas.save()
        canvas.clipRect(frame)
        for (line in highlights) {
          canvas.drawRoundRect(line, 4f, 4f, fill)
          canvas.drawRoundRect(line, 4f, 4f, outline)
        }
        annotations.draw(canvas)
        canvas.restore()
      }
    })
  }

  override fun onResume() {
    super.onResume()
    MenuCapture.show(id)
  }

  override fun onPause() {
    MenuCapture.hide(id)
    super.onPause()
  }

  override fun onDestroy() {
    bitmap?.recycle()
    bitmap = null
    super.onDestroy()
  }
}
