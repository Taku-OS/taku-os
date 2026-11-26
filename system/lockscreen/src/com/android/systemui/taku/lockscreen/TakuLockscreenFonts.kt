package com.android.systemui.taku.lockscreen

import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * The design's typefaces, installed as product files by `image/taku.mk` (see `system/lockscreen/README.md`).
 * A missing file falls back to the system sans-serif rather than failing the lock screen.
 */
object TakuLockscreenFonts {
    private const val TAG = "TakuLockscreenFonts"
    const val DIRECTORY = "/product/fonts/taku"

    /** Space Grotesk Regular: date (Heading/xl) and the compact clock. */
    val spaceGrotesk: Typeface by lazy { load("SpaceGrotesk-Regular.ttf") }

    /** Hanken Grotesk Regular / Medium: notification cards. */
    val hankenGrotesk: Typeface by lazy { load("HankenGrotesk-Regular.ttf") }
    val hankenGroteskMedium: Typeface by lazy { load("HankenGrotesk-Medium.ttf") }

    private fun load(name: String): Typeface {
        val file = File(DIRECTORY, name)
        return try {
            if (file.canRead()) Typeface.createFromFile(file) else Typeface.SANS_SERIF.also { Log.w(TAG, "Missing $file") }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unreadable $file", error)
            Typeface.SANS_SERIF
        }
    }
}
