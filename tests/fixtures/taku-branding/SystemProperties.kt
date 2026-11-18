// Host test double for Android properties; never reads a phone or real settings.
package android.os

object SystemProperties {
    val values = mutableMapOf<String, String>()
    @JvmStatic
    fun get(key: String, default: String): String = values[key] ?: default
}
