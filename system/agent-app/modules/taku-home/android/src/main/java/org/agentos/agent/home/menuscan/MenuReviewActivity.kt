package org.agentos.agent.home.menuscan

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
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
      private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x34ff9b46 }
      private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffa35d.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
      }

      init {
        contentDescription = review.scan.rows.joinToString(separator = ". ") { "${it.title}, ${it.price}" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
      }

      override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val scale = minOf(width.toFloat() / photo.width, height.toFloat() / photo.height)
        val imageWidth = photo.width * scale
        val imageHeight = photo.height * scale
        val left = (width - imageWidth) / 2f
        val top = (height - imageHeight) / 2f
        val frame = RectF(left, top, left + imageWidth, top + imageHeight)
        canvas.drawBitmap(photo, null, frame, paint)
        canvas.save()
        canvas.clipRect(frame)
        for ((_, _, box) in review.scan.rows) {
          val line = RectF(left + box.left * imageWidth, top + box.top * imageHeight,
            left + box.right * imageWidth, top + box.bottom * imageHeight)
          line.inset(-4f, -4f)
          canvas.drawRoundRect(line, 4f, 4f, fill)
          canvas.drawRoundRect(line, 4f, 4f, outline)
        }
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
