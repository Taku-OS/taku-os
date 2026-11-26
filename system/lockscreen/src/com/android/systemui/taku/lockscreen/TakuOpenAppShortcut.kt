package com.android.systemui.taku.lockscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.util.Log
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The "Open app" lock-screen shortcut's target: one launchable activity per user, stored as a
 * flattened component in `Settings.Secure` [SETTING]. Unset or no longer launchable means Taku.
 * Every writer (picker activity, `cmd statusbar lockscreen shortcuts app`) goes through [validate],
 * and the shortcut resolves the target again when it is pressed.
 */
object TakuOpenAppTarget {
    const val SETTING = "taku_lockscreen_open_app"

    class Target(val component: ComponentName, val label: CharSequence, val isDefault: Boolean)

    private fun launcherIntent(component: ComponentName?): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)

    /** The component when it is an enabled, exported launcher activity of this user; else null. */
    fun validate(pm: PackageManager, component: ComponentName): ComponentName? =
        pm.resolveActivity(launcherIntent(component), 0)
            ?.activityInfo
            ?.takeIf { it.exported && it.enabled }
            ?.let { ComponentName(it.packageName, it.name) }

    /** Taku's launcher activity, the default target. */
    fun defaultComponent(pm: PackageManager): ComponentName? =
        pm.getLaunchIntentForPackage(TakuLockscreenNotifications.TAKU_PACKAGE)?.component

    fun resolve(pm: PackageManager, stored: String?): Target? {
        val chosen = stored?.let(ComponentName::unflattenFromString)?.let { validate(pm, it) }
        val component = chosen ?: defaultComponent(pm) ?: return null
        val label = try {
            pm.getActivityInfo(component, 0).loadLabel(pm)
        } catch (missing: PackageManager.NameNotFoundException) {
            component.packageName
        }
        return Target(component, label, chosen == null)
    }

    fun launchIntent(component: ComponentName): Intent =
        launcherIntent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** All launcher activities of this user, by label, for the chooser. */
    fun candidates(pm: PackageManager): List<Target> =
        pm.queryIntentActivities(launcherIntent(null), 0)
            .map { info ->
                Target(ComponentName(info.activityInfo.packageName, info.activityInfo.name), info.loadLabel(pm), false)
            }
            .distinctBy { it.component }
            .sortedBy { it.label.toString().lowercase() }

    /**
     * The shortcut glyph: the app's themed (monochrome) icon, enlarged out of the adaptive-icon safe
     * zone. The keyguard tints shortcut icons, so a full-colour icon would become a solid shape;
     * without a monochrome layer the caller uses a generic apps glyph.
     */
    fun monochromeIcon(pm: PackageManager, component: ComponentName): Drawable? {
        val icon = try {
            pm.getActivityIcon(component)
        } catch (missing: PackageManager.NameNotFoundException) {
            return null
        }
        val mono = (icon as? AdaptiveIconDrawable)?.monochrome ?: return null
        return InsetDrawable(mono, -AdaptiveIconDrawable.getExtraInsetFraction())
    }
}

/**
 * Lock-screen shortcut that opens one chosen app (Taku by default). Stock shortcuts cannot launch an
 * arbitrary app; this one plugs into the stock shortcut system, so selection, the picker, long-press
 * and launching behave like every other shortcut. An app that cannot show over the lock screen goes
 * through the bouncer first, because the result is [OnTriggeredResult.StartActivity] with
 * `canShowWhileLocked = false`.
 */
@SysUISingleton
class TakuOpenAppQuickAffordanceConfig
@Inject
constructor(
    private val context: Context,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : KeyguardQuickAffordanceConfig {
    override val key: String = KEY

    override fun pickerName(): String = context.getString(R.string.controls_open_app)

    override val pickerIconResourceId: Int = R.drawable.ic_apps_expressive

    private fun current(): TakuOpenAppTarget.Target? =
        TakuOpenAppTarget.resolve(userTracker.userContext.packageManager,
            secureSettings.getStringForUser(TakuOpenAppTarget.SETTING, userTracker.userId))

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        secureSettings.observerFlow(USER_ALL, TakuOpenAppTarget.SETTING)
            .onStart { emit(Unit) }
            .map {
                val target = current() ?: return@map KeyguardQuickAffordanceConfig.LockScreenState.Hidden
                val description = ContentDescription.Loaded(target.label.toString())
                val glyph = TakuOpenAppTarget.monochromeIcon(userTracker.userContext.packageManager, target.component)
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    if (glyph != null) Icon.Loaded(glyph, description)
                    else Icon.Resource(R.drawable.ic_apps_expressive, description)
                )
            }
            .flowOn(backgroundDispatcher)

    /** The picker offers "configure" for the selected shortcut: the app chooser. */
    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState =
        KeyguardQuickAffordanceConfig.PickerScreenState.Default(
            configureIntent = Intent().setClassName(context.packageName, TakuShortcutAppActivity::class.java.name)
        )

    override fun onTriggered(expandable: Expandable?): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        val target = current()
        if (target == null) {
            Log.w(TAG, "No launchable target for the Open app shortcut")
            return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled(false)
        }
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
            TakuOpenAppTarget.launchIntent(target.component), canShowWhileLocked = false)
    }

    companion object {
        private const val TAG = "TakuOpenAppShortcut"
        const val KEY = "taku_open_app"
        /** `UserHandle.USER_ALL` (hidden API): observe the setting for every user, read the current one. */
        private const val USER_ALL = -1
    }
}
