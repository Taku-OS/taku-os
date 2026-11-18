// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context

/**
 * Single source of truth for the global floating-window switch. The call page historically wrote
 * the same key to its activity preferences; both legacy and media values are read, and an explicit
 * "off" on either side wins so an existing close is not undone by the migration.
 */
object OverlayPreference {
    private const val MEDIA_FILE="media-translation"
    private const val LEGACY_FILE="MediaTranslationActivity"
    private const val KEY="overlay"

    fun enabled(context: Context): Boolean {
        val media=context.getSharedPreferences(MEDIA_FILE,Context.MODE_PRIVATE)
        val legacy=context.getSharedPreferences(LEGACY_FILE,Context.MODE_PRIVATE)
        val values=ArrayList<Boolean>(2)
        if(media.contains(KEY))values.add(media.getBoolean(KEY,true))
        if(legacy.contains(KEY))values.add(legacy.getBoolean(KEY,true))
        return values.all{it}
    }

    fun set(context: Context,enabled: Boolean) {
        context.getSharedPreferences(MEDIA_FILE,Context.MODE_PRIVATE).edit().putBoolean(KEY,enabled).apply()
        context.getSharedPreferences(LEGACY_FILE,Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
