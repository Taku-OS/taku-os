package org.taku.test.clockmotion

import android.app.Activity
import android.graphics.Path
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
        val label = TextView(this).apply { text = report; setTextColor(-1); textSize = 14f }
        root.addView(label, FrameLayout.LayoutParams(-1, -2).apply { topMargin = (16*density).toInt() })
        if (intent.getBooleanExtra("digits", false)) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (line in listOf("01234", "56789")) {
                column.addView(TakuTallClockView(this).apply {
                    time = line; progress = intent.getFloatExtra("progress", 0f)
                }, LinearLayout.LayoutParams(-1, (330*density).toInt()))
            }
            root.addView(column, FrameLayout.LayoutParams(-1,-2).apply { topMargin = (70*density).toInt() })
            return
        }
        val tall = TakuClockFaceView(this, true).apply { id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE }
        val small = TakuClockFaceView(this, false).apply { id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL; visibility = View.INVISIBLE }
        for (face in listOf(tall, small)) {
            face.setIs24Hour(true)
            // A fixed test instant, only in this harness. Production always uses the real clock.
            face.setTimeZone(android.icu.util.TimeZone.getTimeZone("UTC"))
            face.refresh(Date(34200000L))
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
        return "PASS $checks native path checks / all 1,440 times"
    }
}
