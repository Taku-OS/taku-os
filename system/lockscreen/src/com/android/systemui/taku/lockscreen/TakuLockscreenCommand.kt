package com.android.systemui.taku.lockscreen

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * `cmd statusbar lockscreen ...`: the Agent's view of the lock screen. It runs on a background thread.
 * Card and Taku operations hop to the main thread, where the cards run, and call
 * [TakuLockscreenNotifications]. Shortcut operations call [TakuLockscreenShortcuts], the picker's
 * interactor, which blocks. Every reply is one JSON line.
 *
 * Constructed with [TakuKeyguardBlueprint], so the command exists whenever the Taku lock screen does.
 */
@SysUISingleton
class TakuLockscreenCommand
@Inject
constructor(
    registry: CommandRegistry,
    private val notifications: TakuLockscreenNotifications,
    private val shortcuts: TakuLockscreenShortcuts,
    private val lockStatus: TakuLockStatusSection,
    @Main private val mainExecutor: Executor,
    @Background backgroundExecutor: Executor,
) {
    init {
        // A duplicate name throws; SystemUI must still start, so fall back to the prefixed name.
        try {
            registry.registerCommand(COMMAND, { Handler() }, backgroundExecutor)
        } catch (taken: IllegalStateException) {
            Log.e(TAG, "cmd statusbar $COMMAND is taken; using $FALLBACK_COMMAND", taken)
            registry.registerCommand(FALLBACK_COMMAND, { Handler() }, backgroundExecutor)
        }
    }

    private fun onMain(block: () -> JSONObject): JSONObject {
        val task = FutureTask(block)
        mainExecutor.execute(task)
        return try {
            task.get(MAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            task.cancel(false)
            TakuLockscreenNotifications.Result.error("TIMEOUT").json()
        }
    }

    fun run(args: List<String>): JSONObject = when (args.getOrNull(0)) {
        "state" -> if (args.size == 1) onMain {
            TakuLockscreenNotifications.Result.OK.json().put("state", state())
        } else usage()
        "notifications" -> if (args.size == 1) onMain {
            TakuLockscreenNotifications.Result.OK.json()
                .put("state", state())
                .put("notifications", notifications.notificationsJson())
        } else usage()
        "open" -> if (args.size == 2) onMain { notifications.open(args[1]).json() } else usage()
        "dismiss" -> if (args.size == 2) onMain { notifications.dismiss(args[1]).json() } else usage()
        "action" -> {
            val index = args.getOrNull(2)?.toIntOrNull()
            if (args.size == 3 && index != null) onMain { notifications.action(args[1], index).json() } else usage()
        }
        "taku" -> if (args.size == 1) onMain { notifications.openTaku().json() } else usage()
        "shortcuts" -> shortcutsCommand(args.drop(1))
        else -> usage()
    }

    /** The lock screen's state plus the padlock the island shows for it. */
    private fun state(): JSONObject = notifications.stateJson().put("lockStatus", lockStatus.stateJson())

    private fun shortcutsCommand(args: List<String>): JSONObject = runBlocking {
        when (args.getOrNull(0)) {
            null, "list" -> if (args.size <= 1) shortcuts.list() else usage()
            "set" -> if (args.size == 3) shortcuts.set(args[1], args[2]).json() else usage()
            "clear" -> if (args.size == 2) shortcuts.clear(args[1]).json() else usage()
            "app" -> when (args.size) {
                1 -> TakuLockscreenNotifications.Result.OK.json().put("openApp", shortcuts.openAppJson())
                2 -> shortcuts.setOpenApp(args[1]).json().put("openApp", shortcuts.openAppJson())
                else -> usage()
            }
            else -> usage()
        }
    }

    private fun usage() = TakuLockscreenNotifications.Result.error("USAGE").json().put("usage", USAGE)

    private inner class Handler : Command {
        override fun execute(pw: PrintWriter, args: List<String>) = pw.println(run(args))
        override fun help(pw: PrintWriter) = pw.println(USAGE)
    }

    companion object {
        private const val TAG = "TakuLockscreenCommand"
        const val COMMAND = "lockscreen"
        const val FALLBACK_COMMAND = "taku-lockscreen"
        private const val MAIN_TIMEOUT_SECONDS = 5L
        const val USAGE = "lockscreen state | notifications | open KEY | dismiss KEY | action KEY INDEX | taku" +
            " | shortcuts [list] | shortcuts set SLOT SHORTCUT | shortcuts clear SLOT" +
            " | shortcuts app [COMPONENT|default]"
    }
}
