package org.agentos.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Process
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class BScreenActivity : Activity() {
    private lateinit var workspace: WorkspaceUi
    private val widgets get() = workspace.ui
    private lateinit var title: TextView
    private lateinit var refreshButton: View
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var catalog: Catalog? = null
    private var catalogPage: CatalogPage? = null
    private var selected: Lease? = null
    private var listOffset = 0
    private var readError: String? = null
    private var limitNeedsRefresh = false
    private var feedback: Feedback? = null
    private var pending: FeedbackArea? = null
    private var resumed = false
    private var reading = false
    private val changing get() = pending != null
    private val canEditLimit get() = !changing && readError == null && !limitNeedsRefresh
    private var readVersion = 0
    private val poll = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        WorkspaceUi.applySystemTheme(this)
        super.onCreate(savedInstanceState)
        workspace = WorkspaceUi(this)
        selected = savedInstanceState?.getBundle("lease")?.let { Lease.restore(it) }
        listOffset = savedInstanceState?.getInt("listOffset") ?: 0
        val root = workspace.root()
        val header = widgets.column().apply { setPadding(widgets.dp(20), 0, widgets.dp(20), widgets.dp(8)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val navigation = widgets.column()
        workspace.navigation(navigation, getString(R.string.bscreen_title), ::back)
        toolbar.addView(navigation, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = widgets.button(getString(R.string.workspace_refresh), false) { refresh() }.apply {
            minimumHeight = widgets.dp(48)
            minimumWidth = widgets.dp(48)
        }
        toolbar.addView(refreshButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = widgets.dp(8) })
        header.addView(toolbar)
        title = widgets.text("", 24, true).apply { isAccessibilityHeading = true }
        widgets.add(header, title, 4)
        root.addView(header)
        content = widgets.column().apply { setPadding(widgets.dp(20), 0, widgets.dp(20), widgets.dp(24)) }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::back)
        render(offset = if (selected == null) listOffset else 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        selected = null
        listOffset = 0
        feedback = null
        render(offset = 0)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (display?.displayId != Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        resumed = true
        refresh()
    }

    override fun onPause() {
        resumed = false
        readVersion++
        NativeClient.main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selected?.let { outState.putBundle("lease", it.save()) }
        outState.putInt("listOffset", if (selected == null) scroll.scrollY else listOffset)
    }

    private fun back() {
        if (selected == null) {
            finish()
        } else {
            selected = null
            feedback = null
            render(offset = listOffset)
        }
    }

    private fun active() = resumed && !isFinishing && !isDestroyed && display?.displayId == Display.DEFAULT_DISPLAY

    private fun refresh() {
        if (!active() || reading || changing) return
        NativeClient.main.removeCallbacks(poll)
        reading = true
        refreshButton.isEnabled = false
        val version = readVersion
        NativeClient.io.execute {
            val result = attempt { readCatalog(call("list")) }
            NativeClient.main.post {
                reading = false
                if (!active()) return@post
                if (version != readVersion) {
                    refresh()
                    return@post
                }
                refreshButton.isEnabled = !changing
                val updated = result.getOrNull()
                val error = result.exceptionOrNull()?.let { failure ->
                    ownerError(failure) ?: getString(R.string.workspace_unavailable_copy)
                }
                if ((updated != null && (updated != catalog || limitNeedsRefresh)) || error != readError) {
                    val offset = if (catalog == null && selected == null) listOffset else scroll.scrollY
                    if (updated != null) {
                        catalog = updated
                        limitNeedsRefresh = false
                    }
                    readError = error
                    render(offset)
                }
                NativeClient.main.postDelayed(poll, 4_000)
            }
        }
    }

    private fun <T> perform(
        operation: () -> T,
        area: FeedbackArea = FeedbackArea.Page,
        onSuccess: (T) -> Unit = {},
    ) {
        if (!active() || changing) return
        pending = area
        readVersion++
        NativeClient.main.removeCallbacks(poll)
        feedback = null
        render()
        NativeClient.io.execute {
            val result = attempt(operation)
            NativeClient.main.post {
                if (isFinishing || isDestroyed) return@post
                pending = null
                val previousSelection = selected
                result.onSuccess(onSuccess).onFailure { failure ->
                    val message = if (area == FeedbackArea.Limit) {
                        limitNeedsRefresh = true
                        limitError(failure)
                    } else errorText(failure)
                    feedback = Feedback(area, message)
                }
                render(if (previousSelection != null && selected == null) listOffset else scroll.scrollY)
                refresh()
            }
        }
    }

    private fun call(action: String, input: JSONObject = JSONObject()): JSONObject =
        WorkspaceCommands.call(applicationContext, "workspaces/$action", input, Process.myUid())

    private fun configure(change: PolicyChange) {
        val area = if (change is PolicyChange.Limit) FeedbackArea.Limit else FeedbackArea.Page
        perform({ writePolicy(change) }, area) { policySaved(it, area) }
    }

    private fun writePolicy(change: PolicyChange): Policy =
        readPolicy(call("configure", JSONObject().put("patch", change.json())).getJSONObject("policy"))

    private fun policySaved(policy: Policy, area: FeedbackArea) {
        catalog = catalog?.copy(policy = policy)
        val message = if (area == FeedbackArea.Limit) R.string.workspace_limit_saved else R.string.workspace_saved
        feedback = Feedback(area, getString(message))
    }

    private fun render(offset: Int = scroll.scrollY) {
        refreshButton.isEnabled = !changing && !reading
        val lease = selected
        title.setText(if (lease == null) R.string.workspace_title else R.string.workspace_detail_back)
        val snapshot = catalog
        if (snapshot != null && lease == null) {
            val page = catalogPage ?: CatalogPage().also { catalogPage = it }
            page.render(snapshot)
        } else {
            catalogPage = null
            content.removeAllViews()
            feedbackText(FeedbackArea.Page)?.let { note(it) }
            when {
                readError != null -> note(checkNotNull(readError))
                snapshot == null -> note(getString(R.string.workspace_loading))
                else -> {
                    val screen = snapshot.screens.firstOrNull { it.lease == lease }
                    if (screen == null) note(getString(R.string.workspace_error_changed)) else renderDetails(screen)
                }
            }
        }
        scroll.post { scroll.scrollTo(0, offset) }
    }

    private fun liveNote() = widgets.text("", 14, false).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun note(message: String) {
        widgets.add(content, liveNote().apply { text = message }, 8)
    }

    private fun feedbackText(area: FeedbackArea): String? = when {
        pending == area -> getString(if (area == FeedbackArea.Limit) R.string.workspace_limit_saving else R.string.workspace_action_pending)
        feedback?.area == area -> feedback?.message
        else -> null
    }

    private fun showNote(view: TextView, message: String?) {
        view.visibility = if (message == null) View.GONE else View.VISIBLE
        if (view.text != message.orEmpty()) view.text = message.orEmpty()
    }

    private inner class CatalogPage {
        private val readNotice = liveNote()
        private val pageFeedback = liveNote()
        private val active = widgets.listGroup()
        private val settings = widgets.card()
        private val background = policySwitch(R.string.workspace_allow)
        private val backgroundCopy = widgets.text("", 14, false)
        private val jev = policySwitch(R.string.workspace_jev_close)
        private val decrease = limitButton("−", R.string.workspace_limit_decrease, -1)
        private val increase = limitButton("+", R.string.workspace_limit_increase, 1)
        private val value = widgets.text("", 18, true).apply {
            gravity = Gravity.CENTER
            minimumWidth = widgets.dp(40)
            setPadding(widgets.dp(4), 0, widgets.dp(4), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        private val bounds = widgets.text("", 14, false)
        private val limitFeedback = liveNote()
        private val permissions = widgets.card()
        private val apps = widgets.button(getString(R.string.workspace_allowed_apps), false, ::chooseApps).apply {
            minimumHeight = widgets.dp(48)
        }
        private val revocations = widgets.column()
        private var displayedScreens: List<ManagedWorkspace>? = null
        private var displayedRevocations: List<Revocation>? = null

        init {
            content.removeAllViews()
            widgets.add(content, readNotice, 8)
            widgets.add(content, pageFeedback, 8)
            widgets.section(content, getString(R.string.workspace_active), active)
            widgets.section(content, getString(R.string.workspace_settings), settings)
            widgets.section(content, getString(R.string.workspace_permissions), permissions)

            widgets.add(settings, background, 0)
            widgets.add(settings, backgroundCopy, 6)
            widgets.add(settings, jev, 12)
            copy(settings, getString(R.string.workspace_jev_close_copy))
            widgets.divider(settings, 0)
            widgets.add(settings, limitRow(), 12)
            widgets.add(settings, limitFeedback, 6)
            widgets.add(settings, bounds, 6)
            copy(settings, getString(R.string.workspace_limit_copy))
            background.setOnCheckedChangeListener { _, allowed ->
                if (allowed != catalog?.policy?.allowBackground) configure(PolicyChange.Background(allowed))
            }
            jev.setOnCheckedChangeListener { _, enabled ->
                if (enabled != catalog?.policy?.jevCloseEnabled) configure(PolicyChange.JevClose(enabled))
            }

            widgets.add(permissions, apps, 0)
            copy(permissions, getString(R.string.workspace_apps_note))
            widgets.add(permissions, revocations, 0)
        }

        private fun limitRow(): LinearLayout {
            val stepper = LinearLayout(this@BScreenActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(decrease, LinearLayout.LayoutParams(-2, -2))
                addView(value, LinearLayout.LayoutParams(-2, -2))
                addView(increase, LinearLayout.LayoutParams(-2, -2))
            }
            val label = widgets.text(getString(R.string.workspace_limit), 16, true)
            val stacked = resources.configuration.let { it.screenWidthDp < 360 || it.fontScale >= 1.3f }
            return if (stacked) widgets.column().apply {
                widgets.add(this, label, 0)
                addView(stepper, LinearLayout.LayoutParams(-2, -2).apply { topMargin = widgets.dp(8) })
            } else LinearLayout(this@BScreenActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                addView(stepper, LinearLayout.LayoutParams(-2, -2).apply { marginStart = widgets.dp(12) })
            }
        }

        private fun policySwitch(label: Int) = Switch(this@BScreenActivity).apply {
            setText(label)
            setTextColor(workspace.theme.ink)
            minimumHeight = widgets.dp(48)
            switchPadding = widgets.dp(12)
        }

        fun render(snapshot: Catalog) {
            val canEdit = !changing && readError == null
            showNote(readNotice, readError)
            showNote(pageFeedback, feedbackText(FeedbackArea.Page))
            showNote(limitFeedback, feedbackText(FeedbackArea.Limit))
            active.visibility = if (readError == null) View.VISIBLE else View.GONE
            if (displayedScreens != snapshot.screens) {
                renderScreens(active, snapshot.screens)
                displayedScreens = snapshot.screens
            }
            if (displayedRevocations != snapshot.revocations) {
                renderRevocations(revocations, snapshot.revocations)
                displayedRevocations = snapshot.revocations
            }

            val policy = snapshot.policy
            if (background.isChecked != policy.allowBackground) background.isChecked = policy.allowBackground
            if (jev.isChecked != policy.jevCloseEnabled) jev.isChecked = policy.jevCloseEnabled
            background.isEnabled = canEdit
            jev.isEnabled = canEdit
            val copy = getString(if (policy.allowBackground) R.string.workspace_allow_copy else R.string.workspace_background_off_copy)
            if (backgroundCopy.text != copy) backgroundCopy.text = copy
            val current = policy.maxActiveScreens
            val range = snapshot.limitRange
            val number = current.toString()
            if (value.text != number) {
                value.text = number
                value.contentDescription = getString(R.string.workspace_limit_current, current)
            }
            val allowed = getString(R.string.workspace_limit_range, range.first, range.last)
            if (bounds.text != allowed) bounds.text = allowed
            setButtonEnabled(decrease, canEditLimit && current - 1 in range)
            setButtonEnabled(increase, canEditLimit && current + 1 in range)
            setButtonEnabled(apps, canEdit)
        }
    }

    private fun limitButton(symbol: String, label: Int, delta: Int): Button = widgets.button(symbol, false) {
        val snapshot = catalog ?: return@button
        val next = snapshot.policy.maxActiveScreens + delta
        if (canEditLimit && next in snapshot.limitRange) configure(PolicyChange.Limit(next))
    }.apply {
        contentDescription = getString(label)
        minimumHeight = widgets.dp(48)
        minimumWidth = widgets.dp(48)
        setPadding(widgets.dp(8), widgets.dp(4), widgets.dp(8), widgets.dp(4))
        textSize = 22f
    }

    private fun setButtonEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.5f
    }

    private fun renderScreens(list: LinearLayout, screens: List<ManagedWorkspace>) {
        list.removeAllViews()
        val summary = widgets.column().apply {
            setPadding(widgets.dp(16), widgets.dp(12), widgets.dp(16), widgets.dp(12))
        }
        if (screens.isEmpty()) {
            copy(summary, getString(R.string.workspace_empty_title), strong = true, margin = 0)
            copy(summary, getString(R.string.workspace_empty_copy))
        } else {
            val screenCount = screens.size
            val taskCount = screens.mapTo(HashSet()) { it.lease.threadId }.size
            val counts = getString(R.string.workspace_task_counts,
                resources.getQuantityString(R.plurals.workspace_spaces, screenCount, screenCount),
                resources.getQuantityString(R.plurals.workspace_tasks, taskCount, taskCount))
            copy(summary, counts, margin = 0)
        }
        list.addView(summary)
        for (screen in screens) {
            widgets.divider(list, 16)
            val row = widgets.column().apply {
                setPadding(widgets.dp(16), widgets.dp(10), widgets.dp(16), widgets.dp(10))
                foreground = RippleDrawable(ColorStateList.valueOf(workspace.theme.border), null, null)
                isFocusable = true
                isScreenReaderFocusable = true
                setOnClickListener {
                    listOffset = scroll.scrollY
                    selected = screen.lease
                    feedback = null
                    render(offset = 0)
                }
            }
            copy(row, screen.app, strong = true, margin = 0)
            if (screen.task != screen.app) copy(row, screen.task, margin = 2)
            widgets.add(row, widgets.text(screen.status, 14, true).apply { setTextColor(workspace.theme.accent) }, 3)
            list.addView(row)
        }
    }

    private fun renderRevocations(group: LinearLayout, revocations: List<Revocation>) {
        group.removeAllViews()
        if (revocations.isEmpty()) return
        widgets.add(group, widgets.text(getString(R.string.bscreen_access_changes), 14, true), 16)
        for (receipt in revocations) {
            copy(group, receipt.task, strong = true)
            if (receipt.packages.isNotEmpty()) copy(group, receipt.packages.joinToString(", "))
            copy(group, getString(receipt.status))
            if (receipt.error.isNotEmpty()) copy(group, receipt.error)
        }
    }

    private fun renderDetails(screen: ManagedWorkspace) {
        val card = widgets.card()
        copy(card, screen.app, strong = true)
        copy(card, screen.task)
        copy(card, getString(R.string.workspace_task_counts, getString(R.string.workspace_detail_state), screen.status))
        copy(card, getString(R.string.workspace_task_counts, getString(R.string.workspace_detail_input), getString(screen.inputOwner)))
        copy(card, getString(R.string.workspace_shared_note))
        if (screen.cleanupFailed) copy(card, getString(R.string.workspace_cleanup_retry))
        if (!screen.agentAllowed) copy(card, getString(R.string.workspace_agent_blocked_copy))
        if (screen.lastError.isNotEmpty()) copy(card, screen.lastError)
        widgets.add(content, card, 8)
        button(card, getString(R.string.workspace_view_live), screen.canPreview) { screenAction("preview", screen) }
        copy(card, getString(R.string.workspace_detail_hint))
        button(card, getString(R.string.workspace_pause), screen.canPreview && screen.inputOwner != R.string.workspace_input_paused) {
            screenAction("pause", screen)
        }
        button(card, getString(R.string.workspace_open_task)) { screenAction("open", screen) }
        button(card, getString(R.string.workspace_close), !screen.cleanupFailed) { confirmClose(screen.lease) }
    }

    private fun screenAction(action: String, screen: ManagedWorkspace) {
        perform({ call(action, screen.lease.json()) })
    }

    private fun confirmClose(lease: Lease) {
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_close_title)
            .setMessage(R.string.workspace_close_copy)
            .setNegativeButton(R.string.workspace_cancel, null)
            .setPositiveButton(R.string.workspace_close) { _, _ ->
                perform({ call("release", lease.json().put("returnToThread", true)).has("returnError") }) { returnFailed ->
                    if (selected == lease) selected = null
                    feedback = Feedback(FeedbackArea.Page, getString(if (returnFailed) R.string.bscreen_return_failed else R.string.bscreen_closed))
                }
            }.show()
    }

    private fun chooseApps() {
        perform({ readApps(call("apps")) }) { apps -> if (active()) showApps(apps) }
    }

    private fun showApps(apps: List<AppAccess>) {
        val changes = mutableMapOf<String, Boolean>()
        val labels = apps.map { app ->
            buildString {
                append(app.label).append('\n').append(app.pkg)
                if (!app.installed) append('\n').append(getString(R.string.bscreen_not_installed))
            }
        }.toTypedArray()
        val allowed = BooleanArray(apps.size) { apps[it].allowed }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.workspace_allowed_apps)
            .setNegativeButton(R.string.workspace_cancel, null)
        if (apps.isEmpty()) {
            builder.setMessage(R.string.workspace_apps_empty).show()
            return
        }
        builder.setMultiChoiceItems(labels, allowed) { _, index, checked ->
                val app = apps[index]
                if (checked == app.allowed) changes.remove(app.pkg) else changes[app.pkg] = checked
            }
            .setPositiveButton(R.string.workspace_save) { _, _ ->
                if (changes.isNotEmpty()) {
                    perform({
                        val blocked = readPolicy(call("list").getJSONObject("policy")).blockedPackages.toMutableSet()
                        for ((pkg, allowedNow) in changes) {
                            if (allowedNow) blocked.remove(pkg) else blocked.add(pkg)
                        }
                        writePolicy(PolicyChange.BlockedApps(blocked))
                    }) { policySaved(it, FeedbackArea.Page) }
                }
            }.show()
    }

    private fun copy(parent: LinearLayout, text: String, strong: Boolean = false, margin: Int = 6) =
        widgets.add(parent, widgets.text(text, if (strong) 16 else 14, strong), margin)

    private fun button(parent: LinearLayout, text: String, enabled: Boolean = true, onClick: () -> Unit) {
        val control = widgets.button(text, false) { onClick() }
        control.isEnabled = enabled && !changing
        control.alpha = if (control.isEnabled) 1f else 0.5f
        widgets.add(parent, control, 10)
    }

    private fun ownerError(failure: Throwable): String? = when (failure.message) {
        "USER_UNAVAILABLE", "USER_LOCKED" -> getString(R.string.bscreen_user_unavailable)
        else -> null
    }

    private fun errorText(failure: Throwable): String = ownerError(failure) ?: when (failure.message) {
        "THREAD_UNAVAILABLE" -> getString(R.string.bscreen_thread_unavailable)
        "AGENT_FRONTEND_UNAVAILABLE" -> getString(R.string.bscreen_frontend_unavailable)
        else -> if (failure is IOException) workspace.error(failure.message) else getString(R.string.workspace_unavailable_copy)
    }

    private fun limitError(failure: Throwable): String = ownerError(failure) ?: getString(
        if (failure.message == "INVALID_WORKSPACE_LIMIT") R.string.workspace_limit_invalid else R.string.workspace_limit_failed,
    )

    private fun readCatalog(result: JSONObject): Catalog {
        val limits = result.getJSONObject("limits").getJSONObject("maxActiveScreens")
        return Catalog(
            policy = readPolicy(result.getJSONObject("policy")),
            limitRange = limits.getInt("min")..limits.getInt("max"),
            screens = result.getJSONArray("screens").read(::readScreen),
            revocations = result.getJSONArray("revocations").read { receipts, index ->
                val receipt = receipts.getJSONObject(index)
                Revocation(
                    workspace.taskLabel(receipt),
                    receipt.getJSONArray("packageNames").read(JSONArray::getString),
                    when (receipt.getString("status")) {
                        "pending" -> R.string.bscreen_access_pending
                        "released" -> R.string.bscreen_access_released
                        "retained" -> R.string.bscreen_access_retained
                        "superseded" -> R.string.bscreen_access_superseded
                        else -> R.string.workspace_cleanup_failed
                    },
                    receipt.optString("error", ""),
                )
            },
        )
    }

    private fun readScreen(screens: JSONArray, index: Int): ManagedWorkspace {
        val screen = screens.getJSONObject(index)
        val status = screen.getString("status")
        val owner = screen.getString("controlOwner")
        val stopping = screen.optBoolean("stopping")
        return ManagedWorkspace(
            Lease(screen.getString("threadId"), screen.getString("screenKey"), screen.getString("generation")),
            screen.optString("appLabel").ifBlank { screen.optString("packageName").ifBlank { getString(R.string.workspace_task_fallback) } },
            workspace.taskLabel(screen),
            if (stopping && status !in unavailable) getString(R.string.workspace_status_stopping) else workspace.status(screen),
            when {
                status == "cleanup_failed" || owner == "paused" -> R.string.workspace_input_paused
                owner == "user" -> R.string.workspace_input_you
                else -> R.string.workspace_input_agent
            },
            screen.getBoolean("admitted") && screen.optInt("displayId", -1) > 0 && !stopping && status !in unavailable,
            status == "cleanup_failed",
            screen.optBoolean("agentAllowed", true),
            screen.optString("lastError", ""),
        )
    }

    private fun readApps(result: JSONObject): List<AppAccess> = result.getJSONArray("apps").read { apps, index ->
        val app = apps.getJSONObject(index)
        AppAccess(app.getString("packageName"), app.getString("label"), app.getBoolean("installed"), app.getBoolean("allowed"))
    }

    private fun readPolicy(policy: JSONObject) = Policy(
        policy.getBoolean("allowBackground"),
        policy.getInt("maxActiveScreens"),
        policy.getJSONArray("blockedPackages").read(JSONArray::getString).toSet(),
        policy.optBoolean("jevCloseEnabled"),
    )

    private fun <T> attempt(operation: () -> T): Result<T> = try {
        Result.success(operation())
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private inline fun <T> JSONArray.read(item: (JSONArray, Int) -> T): List<T> = List(length()) { item(this, it) }

    private enum class FeedbackArea { Page, Limit }
    private data class Feedback(val area: FeedbackArea, val message: String)

    private data class Catalog(val policy: Policy, val limitRange: IntRange, val screens: List<ManagedWorkspace>, val revocations: List<Revocation>)
    private data class Policy(val allowBackground: Boolean, val maxActiveScreens: Int,
        val blockedPackages: Set<String>, val jevCloseEnabled: Boolean)
    private data class AppAccess(val pkg: String, val label: String, val installed: Boolean, val allowed: Boolean)
    private data class Revocation(val task: String, val packages: List<String>, val status: Int, val error: String)
    private data class ManagedWorkspace(
        val lease: Lease, val app: String, val task: String, val status: String, val inputOwner: Int,
        val canPreview: Boolean, val cleanupFailed: Boolean, val agentAllowed: Boolean, val lastError: String,
    )

    private data class Lease(val threadId: String, val screenKey: String, val generation: String) {
        fun json(): JSONObject = JSONObject().put("threadId", threadId).put("screenKey", screenKey).put("generation", generation)
        fun save() = Bundle().apply {
            putString("threadId", threadId)
            putString("screenKey", screenKey)
            putString("generation", generation)
        }

        companion object {
            fun restore(bundle: Bundle): Lease? {
                val threadId = bundle.getString("threadId") ?: return null
                val screenKey = bundle.getString("screenKey") ?: return null
                val generation = bundle.getString("generation") ?: return null
                return Lease(threadId, screenKey, generation)
            }
        }
    }

    private sealed interface PolicyChange {
        data class Background(val allowed: Boolean) : PolicyChange
        data class JevClose(val enabled: Boolean) : PolicyChange
        data class Limit(val value: Int) : PolicyChange
        data class BlockedApps(val packages: Set<String>) : PolicyChange

        fun json(): JSONObject = when (this) {
            is Background -> JSONObject().put("allowBackground", allowed)
            is JevClose -> JSONObject().put("jevCloseEnabled", enabled)
            is Limit -> JSONObject().put("maxActiveScreens", value)
            is BlockedApps -> JSONObject().put("blockedPackages", JSONArray(packages))
        }
    }

    private companion object {
        val unavailable = setOf("stale", "disconnected", "unavailable", "rebind_required", "cleanup_failed", "stopping")
    }
}
