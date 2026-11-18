// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Process-wide owner for the translation window, shared by the activity and the overlay service. */
object OverlayHost {
    private var entry: TranslationEntryOverlay?=null
    private var session: MediaTranslationOverlay?=null
    private var suppressed=false

    /** Hides the idle entry while the settings page is visible; the service keeps running. */
    fun setEntrySuppressed(value: Boolean){suppressed=value;if(value)closeEntry()}

    /** Shows the idle entry unless a translation window already exists. */
    fun showEntry(context: Context): Boolean {
        if(session!=null || entry!=null)return true
        if(suppressed)return true
        if(!Settings.canDrawOverlays(context))return false
        entry=TranslationEntryOverlay(context){openSettings(context)}
        return true
    }

    /** Replaces the idle entry with the live translation window. */
    fun showSession(context: Context,title: String,stop: ()->Unit) {
        closeEntry()
        session?.close()
        session=MediaTranslationOverlay(context.applicationContext,title,stop)
    }

    fun update(message: String,text: String){session?.update(message,text)}

    fun setSessionVisible(visible: Boolean){session?.setVisible(visible)}

    fun closeSession(){session?.close();session=null}

    fun closeEntry(){entry?.close();entry=null}

    fun closeAll(){closeSession();closeEntry()}

    fun hasSession(): Boolean = session!=null

    private fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(context,MediaTranslationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
