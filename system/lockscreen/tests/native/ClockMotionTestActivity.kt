package org.taku.test.clockmotion

import android.app.Activity
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.taku.lockscreen.*
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import java.util.Date

class ClockMotionTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(0xff53647d.toInt()); clipChildren = false }
        setContentView(root)
        val report = checkGeometry()
        java.io.File(filesDir, "result.txt").writeText(report)
        val label = TextView(this).apply {
            text = report; setTextColor(-1); textSize = 14f
            if (intent.getBooleanExtra("clean", false)) visibility = View.GONE
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -2).apply { topMargin = (36*density).toInt() })
        if (intent.getBooleanExtra("digits", false)) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (line in listOf("01234", "56789")) {
                column.addView(TakuTallClockView(this).apply {
                    time = line; progress = intent.getFloatExtra("progress", 0f)
                }, LinearLayout.LayoutParams(-1, (330*density).toInt()))
            }
            root.addView(column, FrameLayout.LayoutParams(-1,-2).apply { topMargin = (110*density).toInt() })
            return
        }
        val tall = TakuClockFaceView(this, true).apply { id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE }
        val small = TakuClockFaceView(this, false).apply { id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL; visibility = View.INVISIBLE }
        for (face in listOf(tall, small)) {
            face.setIs24Hour(true)
            // A fixed test instant, only in this harness. Production always uses the real clock.
            face.setTimeZone(android.icu.util.TimeZone.getTimeZone("UTC"))
            face.refresh(Date(intent.getIntExtra("minuteOfDay", 570) * 60000L))
            root.addView(face, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (76*density).toInt()
            })
        }
        val button = TextView(this).apply { text = "Tap to morph"; textSize = 24f; setTextColor(-1); gravity = Gravity.CENTER }
        root.addView(button, FrameLayout.LayoutParams(-1,(80*density).toInt(),Gravity.BOTTOM))
        fun change() {
            TransitionManager.beginDelayedTransition(root, TakuClockSizeTransition())
            val showTall = tall.visibility != View.VISIBLE
            tall.visibility = if (showTall) View.VISIBLE else View.INVISIBLE
            small.visibility = if (showTall) View.INVISIBLE else View.VISIBLE
        }
        button.setOnClickListener { change() }
        val mode = intent.getStringExtra("test")
        if (mode != null) root.postDelayed({
            change()
            if (mode == "interrupt") root.postDelayed({ change() }, 180)
            if (mode == "reverse") root.postDelayed({ change() }, 1100)
            root.postDelayed({
                val tallExpected = mode == "interrupt" || mode == "reverse"
                check((tall.visibility == View.VISIBLE) == tallExpected)
                check((small.visibility == View.VISIBLE) != tallExpected)
                check(tall.morphOverlay == null)
                check(tall.alpha == 1f && small.alpha == 1f)
                java.io.File(filesDir,"transition.txt").writeText("PASS $mode: endpoint, overlay cleanup, both face alphas restored")
                label.text = "$report\nPASS $mode"
            }, 2600)
        }, 600)
    }

    private fun checkGeometry(): String {
        val path = Path()
        val bounds = RectF()
        var checks = 0
        for (h in 0..23) for (m in 0..59) {
            val time = "%02d:%02d".format(java.util.Locale.ROOT,h,m)
            for (p in listOf(-.02f,0f,.5f,1f,1.02f)) {
                check(TakuClockGlyphs.width(time,p) in 150f..340f)
                for (c in time) {
                    TakuClockGlyphs.path(c,p,path)
                    check(!path.isEmpty)
                    path.computeBounds(bounds,true)
                    check(bounds.left.isFinite() && bounds.top.isFinite())
                    check(bounds.width() in 5f..80f && bounds.height() in 10f..330f)
                    checks++
                }
            }
        }
        check(TakuClockMotion.spring(0f) == 0f && TakuClockMotion.spring(1f) == 1f)
        check((0..100).maxOf { TakuClockMotion.spring(it/100f) } in 1f..1.025f)
        val rasterChecks = checkDigitTopology()
        val strokeChecks = checkStrokeWidths()
        checkTimeFormatting()
        return "PASS $checks native path checks / all 1,440 times\nPASS $rasterChecks digit rasters: connected strokes and open counters\nPASS $strokeChecks stroke/terminal checks; 48 clock formats"
    }

    private fun checkTimeFormatting() {
        val face = TakuClockFaceView(this, true)
        face.setTimeZone(android.icu.util.TimeZone.getTimeZone("UTC"))
        for (is24 in listOf(false, true)) for (hour in 0..23) {
            face.setIs24Hour(is24)
            face.refresh(Date((hour * 3600L + 51 * 60) * 1000))
            val shownHour = if (is24) hour else ((hour + 11) % 12 + 1)
            val expected = "%02d:51".format(java.util.Locale.ROOT, shownHour)
            check(face.contentDescription.toString().endsWith(expected)) {
                "Expected $expected, got ${face.contentDescription}"
            }
        }
    }

    /** Measure actual Android filled outlines across their normals, not Paint configuration. */
    private fun checkStrokeWidths(): Int {
        val scale = 8
        val bitmap = Bitmap.createBitmap(96 * scale, 336 * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val path = Path()
        var checks = 0
        for (step in 0..20) {
            val p = step / 20f
            fun lerp(a: Float, b: Float) = a + (b - a) * p
            val expected = lerp(14f, 10.08f)
            fun render(digit: Char) {
                bitmap.eraseColor(Color.TRANSPARENT)
                TakuClockGlyphs.path(digit, p, path)
                canvas.save(); canvas.scale(scale.toFloat(), scale.toFloat())
                canvas.translate(8f, 8f); canvas.drawPath(path, paint); canvas.restore()
            }
            fun measure(x: Float, y: Float, nx: Float, ny: Float) {
                fun ink(offset: Float): Boolean {
                    val ix = ((x + nx * offset + 8) * scale).toInt()
                    val iy = ((y + ny * offset + 8) * scale).toInt()
                    return Color.alpha(bitmap.getPixel(ix, iy)) >= 128
                }
                check(ink(0f)) { "Missing stroke at $x/$y, progress $p" }
                var lo = 0f; var hi = 0f
                while (lo > -24 && ink(lo)) lo -= 0.125f
                while (hi < 24 && ink(hi)) hi += 0.125f
                val measured = hi - lo - 0.125f
                check(kotlin.math.abs(measured - expected) < 0.65f) {
                    "Stroke width $measured, expected $expected at $x/$y, progress $p"
                }
                checks++
            }
            render('2')
            val curves = listOf(
                floatArrayOf(lerp(61f,61.44f), lerp(61f,61.44f), lerp(61f,61.44f), lerp(59f,59.586667f)) to
                    floatArrayOf(lerp(42f,25f), lerp(70.05f,27.716f), lerp(88.25f,29.74f), lerp(105f,32.5f)),
                floatArrayOf(lerp(59f,59.586667f), lerp(57f,57.733333f), lerp(53f,54.026667f), lerp(47f,48.466667f)) to
                    floatArrayOf(lerp(105f,32.5f), lerp(121.75f,35.26f), lerp(137.05f,38.756f), lerp(160f,44f)),
            )
            for ((x,y) in curves) for (t in listOf(.2f,.35f,.5f,.65f,.8f)) {
                val u = 1-t
                fun point(v: FloatArray) = u*u*u*v[0] + 3*u*u*t*v[1] + 3*u*t*t*v[2] + t*t*t*v[3]
                fun tangent(v: FloatArray) = 3*u*u*(v[1]-v[0]) + 6*u*t*(v[2]-v[1]) + 3*t*t*(v[3]-v[2])
                val dx=tangent(x); val dy=tangent(y); val length=kotlin.math.sqrt(dx*dx+dy*dy)
                measure(point(x),point(y),dy/length,-dx/length)
            }
            val dx = lerp(-40f,-37.066667f); val dy = lerp(153f,34.96f)
            val length = kotlin.math.sqrt(dx*dx+dy*dy)
            // Stay clear of the deliberate diagonal/foot intersection in the compact glyph.
            for (t in listOf(.2f,.4f,.6f)) {
                measure(lerp(47f,48.466667f)+dx*t,lerp(160f,44f)+dy*t,dy/length,-dx/length)
            }
            render('8')
            measure(lerp(34f,37.32f),lerp(160f,40f),0f,1f)
            measure(lerp(7f,10.56f),lerp(83.5f,21.68f),1f,0f)
            render('7')
            val bottom = lerp(320f,84f)
            for (offset in listOf(-.3f,-.15f,0f,.15f,.3f)) {
                val ix = ((lerp(7f,18f)+expected*offset+8)*scale).toInt()
                val above = ((bottom-.25f+8)*scale).toInt()
                val below = ((bottom+.25f+8)*scale).toInt()
                check(Color.alpha(bitmap.getPixel(ix,above)) >= 128 &&
                    Color.alpha(bitmap.getPixel(ix,below)) < 128) {
                    "Seven terminal is not horizontal at progress $p, offset $offset"
                }
                checks++
            }
        }
        bitmap.recycle()
        return checks
    }

    /** Catch strokes breaking or counters closing during the morph, using Android's real rasterizer. */
    private fun checkDigitTopology(): Int {
        val width = 96
        val height = 336
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val path = Path()
        val pixels = IntArray(width * height)
        val queue = IntArray(pixels.size)
        var checks = 0
        for (digit in '0'..'9') for (step in -1..41) {
            val progress = step / 40f
            bitmap.eraseColor(Color.TRANSPARENT)
            TakuClockGlyphs.path(digit, progress, path)
            canvas.save()
            canvas.translate(8f, 8f)
            canvas.drawPath(path, paint)
            canvas.restore()
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val seen = BooleanArray(pixels.size)
            var strokes = 0
            var counters = 0
            for (start in pixels.indices) {
                if (seen[start]) continue
                val ink = Color.alpha(pixels[start]) >= 128
                var touchesEdge = false
                var head = 0
                var tail = 1
                queue[0] = start
                seen[start] = true
                while (head < tail) {
                    val at = queue[head++]
                    val x = at % width
                    val y = at / width
                    if (x == 0 || x == width - 1 || y == 0 || y == height - 1) touchesEdge = true
                    for (next in intArrayOf(if (x > 0) at - 1 else -1,
                            if (x < width - 1) at + 1 else -1,
                            if (y > 0) at - width else -1,
                            if (y < height - 1) at + width else -1)) {
                        if (next < 0 || seen[next] || (Color.alpha(pixels[next]) >= 128) != ink) continue
                        seen[next] = true
                        queue[tail++] = next
                    }
                }
                if (ink) {
                    check(!touchesEdge) { "$digit at $progress clips the drawing bounds" }
                    strokes++
                } else if (!touchesEdge) counters++
            }
            val expectedCounters = when (digit) { '0', '4', '6', '9' -> 1; '8' -> 2; else -> 0 }
            check(strokes == 1 && counters == expectedCounters) {
                "$digit at $progress: $strokes connected strokes, $counters counters; expected 1/$expectedCounters"
            }
            checks++
        }
        bitmap.recycle()
        return checks
    }
}
