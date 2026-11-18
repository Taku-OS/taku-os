// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Small idle translation window. Tapping it opens settings; closing is done from settings. */
class TranslationEntryOverlay(context: Context,open: ()->Unit) : AutoCloseable {
    private val app=context.applicationContext
    private val windows=app.getSystemService(WindowManager::class.java)
    private fun dp(value: Int)=(value*app.resources.displayMetrics.density).toInt()
    private val root=LinearLayout(app).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(14),dp(10),dp(14),dp(10))
        background=GradientDrawable().apply{setColor(0xf0192333.toInt());cornerRadius=dp(24).toFloat()}
        isClickable=true;isFocusable=true
        contentDescription="Realtime translation overlay; tap to open settings"
        setOnClickListener{open()}
    }
    init {
        root.addView(TextView(app).apply{text="Realtime translation";textSize=14f;setTextColor(0xff83ddce.toInt())})
        root.addView(TextView(app).apply{text="Ready";textSize=12f;setTextColor(0xffb7c9de.toInt());setPadding(dp(8),0,0,0)})
        if(Settings.canDrawOverlays(app)) {
            windows.addView(root,WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM or Gravity.END;x=dp(12);y=dp(90)})
        }
    }
    override fun close(){runCatching{windows.removeView(root)}}
}
