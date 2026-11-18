package org.agentos.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import java.io.IOException
import java.util.HashSet

/** Resolve the one actual audible app; a model-supplied package is a consistency check. */
internal class AudioPlaybackTarget {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun resolve(context: Context, requested: String?): String {
            if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") != PackageManager.PERMISSION_GRANTED)
                throw IOException("PLAYBACK_IDENTITY_PERMISSION_REQUIRED")
            val packages = HashSet<String>()
            for (config in context.getSystemService(AudioManager::class.java).activePlaybackConfigurations) {
                val usage = config.audioAttributes.usage
                if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) continue
                val active: Boolean
                val uid: Int
                try {
                    active = config.javaClass.getMethod("isActive").invoke(config) as Boolean
                    uid = (config.javaClass.getMethod("getClientUid").invoke(config) as Number).toInt()
                } catch (_: ReflectiveOperationException) {
                    throw IOException("PLAYBACK_IDENTITY_UNAVAILABLE")
                }
                if (!active || uid < 0) continue
                val names = context.packageManager.getPackagesForUid(uid) ?: continue
                for (name in names) {
                    if (name == context.packageName || name == "org.agentos.audio") continue
                    if (context.packageManager.getLaunchIntentForPackage(name) != null) packages.add(name)
                }
            }
            if (packages.size != 1)
                throw IOException(if (packages.isEmpty()) "PLAYBACK_SOURCE_UNAVAILABLE" else "PLAYBACK_SOURCE_AMBIGUOUS")
            val actual = packages.iterator().next()
            if (requested != null && requested.isNotEmpty() && actual != requested)
                throw IOException("PLAYBACK_SOURCE_CHANGED")
            return actual
        }
    }
}
