package com.android.systemui.taku.lockscreen

import android.content.ComponentName
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import dagger.Lazy
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lock-screen shortcut selections for Agents. The wallpaper picker reaches the same
 * [KeyguardQuickAffordanceInteractor] through SystemUI's CustomizationProvider, which needs
 * `CUSTOMIZE_SYSTEM_UI` (the shell lacks it). These operations call the interactor directly with the
 * picker's rules: only listed, enabled shortcuts can be selected, and device policy can disable them.
 * The "Open app" target uses [TakuOpenAppTarget], like the chooser activity.
 *
 * Every function blocks on the interactor's suspend calls; run them off the main thread.
 */
@SysUISingleton
class TakuLockscreenShortcuts
@Inject
constructor(
    private val interactor: Lazy<KeyguardQuickAffordanceInteractor>,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
) {
    suspend fun list(): JSONObject {
        val quickAffordances = interactor.get()
        val slots = quickAffordances.getSlotPickerRepresentations()
        val selections = quickAffordances.getSelections()
        val affordances = quickAffordances.getAffordancePickerRepresentations()
        return TakuLockscreenNotifications.Result.OK.json()
            .put("enabled", slots.isNotEmpty())
            .put("slots", JSONArray().apply {
                slots.forEach { slot ->
                    put(JSONObject()
                        .put("id", slot.id)
                        .put("maxSelected", slot.maxSelectedAffordances)
                        .put("selected", JSONArray().apply { selections[slot.id].orEmpty().forEach { put(it.id) } }))
                }
            })
            .put("shortcuts", JSONArray().apply {
                affordances.forEach { affordance ->
                    put(JSONObject()
                        .put("id", affordance.id)
                        .put("name", affordance.name)
                        .put("enabled", affordance.isEnabled)
                        .put("explanation", affordance.explanation ?: JSONObject.NULL))
                }
            })
            .put("openApp", openAppJson())
    }

    suspend fun set(slotId: String, shortcutId: String): TakuLockscreenNotifications.Result {
        val quickAffordances = interactor.get()
        val slots = quickAffordances.getSlotPickerRepresentations()
        if (slots.isEmpty()) return TakuLockscreenNotifications.Result.error("DISABLED_BY_POLICY")
        if (slots.none { it.id == slotId }) return TakuLockscreenNotifications.Result.error("INVALID_SLOT")
        val affordance = quickAffordances.getAffordancePickerRepresentations().firstOrNull { it.id == shortcutId }
            ?: return TakuLockscreenNotifications.Result.error("INVALID_SHORTCUT")
        if (!affordance.isEnabled) return TakuLockscreenNotifications.Result.error("SHORTCUT_UNAVAILABLE")
        return if (quickAffordances.select(slotId, shortcutId)) TakuLockscreenNotifications.Result.OK
        else TakuLockscreenNotifications.Result.error("WRITE_FAILED")
    }

    /** Empties a slot. Clearing an empty slot succeeds. */
    suspend fun clear(slotId: String): TakuLockscreenNotifications.Result {
        val quickAffordances = interactor.get()
        val slots = quickAffordances.getSlotPickerRepresentations()
        if (slots.isEmpty()) return TakuLockscreenNotifications.Result.error("DISABLED_BY_POLICY")
        if (slots.none { it.id == slotId }) return TakuLockscreenNotifications.Result.error("INVALID_SLOT")
        quickAffordances.unselect(slotId, null)
        return TakuLockscreenNotifications.Result.OK
    }

    fun openAppJson(): JSONObject {
        val stored = secureSettings.getStringForUser(TakuOpenAppTarget.SETTING, userTracker.userId)
        val target = TakuOpenAppTarget.resolve(userTracker.userContext.packageManager, stored)
        return JSONObject()
            .put("component", target?.component?.flattenToString() ?: JSONObject.NULL)
            .put("label", target?.label?.toString() ?: JSONObject.NULL)
            .put("isDefault", target?.isDefault ?: true)
    }

    /** Sets the "Open app" target to a launcher activity, or back to Taku with `default`. */
    fun setOpenApp(value: String): TakuLockscreenNotifications.Result {
        val stored = if (value == DEFAULT) {
            null
        } else {
            val component = ComponentName.unflattenFromString(value)
                ?: return TakuLockscreenNotifications.Result.error("INVALID_COMPONENT")
            TakuOpenAppTarget.validate(userTracker.userContext.packageManager, component)?.flattenToString()
                ?: return TakuLockscreenNotifications.Result.error("NOT_LAUNCHABLE")
        }
        return if (secureSettings.putStringForUser(TakuOpenAppTarget.SETTING, stored, userTracker.userId)) {
            TakuLockscreenNotifications.Result.OK
        } else {
            TakuLockscreenNotifications.Result.error("WRITE_FAILED")
        }
    }

    companion object {
        const val DEFAULT = "default"
    }
}
