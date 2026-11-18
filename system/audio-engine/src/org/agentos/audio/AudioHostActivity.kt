// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Activity
import android.os.Bundle
import android.widget.*

/** Visible device host for an explicitly authorized developer CLI microphone session. */
class AudioHostActivity : Activity() {
    companion object { @Volatile var visible = false }
    private var client: AudioClient? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,48,32,32);setBackgroundColor(0xfff2f6fa.toInt()) }
        val status=TextView(this).apply { text="Developer audio window\nWhile this window is visible, an authorized CLI can dictate or translate audio from the selected app. Leaving stops the session.";textSize=18f;setTextColor(0xff172332.toInt()) }
        layout.addView(status)
        layout.addView(Button(this).apply { text="Stop and close";setOnClickListener { finish() } })
        setContentView(layout)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(android.view.Gravity.TOP)
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),1)
        client=AudioClient(this,object : AudioClient.Listener {
            override fun connected() {}
            override fun event(event: String) {}
            override fun failed(message: String) { status.text=message }
        }).also { it.connect() }
    }
    override fun onResume() { super.onResume();visible=true }
    override fun onPause() { visible=false;sendBroadcast(android.content.Intent("org.agentos.audio.HOST_CLOSED").setPackage(packageName));super.onPause() }
    override fun onDestroy() { client?.close();super.onDestroy() }
}
