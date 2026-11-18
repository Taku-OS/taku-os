// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

/** Bounded caption view. Manual scrolling pauses follow until Latest or the bottom is reached. */
class MediaTranslationOverlay(private val context: Context,title: String,stop: ()->Unit) : AutoCloseable {
    private val windows=context.getSystemService(WindowManager::class.java)
    private fun dp(value: Int)=(value*context.resources.displayMetrics.density).toInt()
    private val root=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(8))
        background=GradientDrawable().apply{setColor(0xf0192333.toInt());cornerRadius=dp(16).toFloat()}
    }
    private var follow=true
    private val caption=TextView(context).apply{textSize=18f;setTextColor(-1);setPadding(0,0,0,dp(5))}
    private val latest=Button(context).apply{text="Jump to latest";textSize=11f;visibility=View.GONE}
    private val scroll=object: ScrollView(context) {
        override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int){super.onLayout(changed,l,t,r,b);if(follow)scrollTo(0,maxOf(0,caption.bottom-height))}
    }.apply {
        addView(caption);setOnTouchListener{_,event->
            if(event.actionMasked==MotionEvent.ACTION_MOVE){follow=false;latest.visibility=View.VISIBLE}
            if(event.actionMasked==MotionEvent.ACTION_UP && !canScrollVertically(1)){follow=true;latest.visibility=View.GONE}
            false
        }
    }
    private val status=TextView(context).apply{textSize=11f;setTextColor(0xffb7c9de.toInt())}
    init {
        latest.setOnClickListener{follow=true;latest.visibility=View.GONE;scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
        root.addView(TextView(context).apply{text=title;textSize=14f;setTextColor(0xff83ddce.toInt())})
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(status)
        root.addView(LinearLayout(context).apply{
            addView(latest,LinearLayout.LayoutParams(0,dp(40),1f));addView(Button(context).apply{text="Stop translation";textSize=11f;setOnClickListener{stop()}},LinearLayout.LayoutParams(0,dp(40),1f))
        })
        windows.addView(root,WindowManager.LayoutParams(minOf(dp(320),context.resources.displayMetrics.widthPixels-dp(24)),dp(230),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM or Gravity.END;x=dp(12);y=dp(90)})
    }
    fun update(message: String,text: String){status.text=message;val next=text.takeLast(4000);if(caption.text.toString()!=next){caption.text=next;scroll.requestLayout()}}
    fun setVisible(visible: Boolean){root.visibility=if(visible)View.VISIBLE else View.GONE}
    override fun close(){runCatching{windows.removeView(root)}}
}
