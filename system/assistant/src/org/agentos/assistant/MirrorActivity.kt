package org.agentos.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.agentos.workspace.WorkspaceInputConnection
import org.agentos.workspace.WorkspaceInputConnection.Lease
import org.json.JSONObject

/** Read-only by default. Managed input is mediated by the broker's exact screen lease. */
class MirrorActivity : Activity() {
    private lateinit var workspace: WorkspaceUi
    private val main = Handler(Looper.getMainLooper())
    private val commands = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private var inputSession: InputSession? = null
    private lateinit var preview: ImageView
    private lateinit var state: TextView
    private lateinit var details: TextView
    private lateinit var note: TextView
    private var control: Button? = null
    private var stop: Button? = null
    private lateinit var appIcon: ImageView
    private lateinit var edit: ImageButton
    private lateinit var minimize: ImageButton
    private lateinit var backInApp: ImageButton
    private lateinit var activityToggle: ImageButton
    private lateinit var statusDot: View
    private var requirement: WorkspaceRequirementView? = null
    private var resumeRequest: WorkspaceExecutionRequest? = null
    private var activityRepository: AgentRepository? = null
    private lateinit var activityRows: LinearLayout
    private lateinit var stepRows: LinearLayout
    private lateinit var stepsFrame: FrameLayout
    private lateinit var activityContent: View
    private var activityOpen = false
    private var activityLoading = false
    private var editingRequirement = false
    private var resumeUnknown = false
    private var resumeFailed = false
    private var currentScreen: JSONObject? = null
    private var currentPackage = ""
    private val activityPoll = Runnable { loadActivity() }
    private val expireResumeNotice = Runnable { showNote("") }
    private var activitySignature = ""
    private var expansion: ConversationExpansion? = null
    @Volatile private var resumed = false
    @Volatile private var userControl = false
    @Volatile private var connected = false
    @Volatile private var frames: LocalSocket? = null
    @Volatile private var watchGeneration = 0
    @Volatile private var ownerCheckedAt = 0L
    @Volatile private var controlGeneration = 0L
    @Volatile private var controlRevision = -1L
    private var lastObservedControlRevision = -1L
    private var lease: JSONObject? = null
    private var inputWindow: IBinder? = null
    private var inputLease: Lease? = null
    private var inputEditing = false
    private var admittedThread: String? = null
    private var returnedToConversation = false
    private var suppressWorkspaceReturn = false
    @Volatile private var width = 720
    @Volatile private var height = 1280
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downControlRevision = -1L
    private var downControlGeneration = 0L
    private var tracking = false
    private var changing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        WorkspaceUi.applySystemTheme(this)
        super.onCreate(savedInstanceState)
        workspace = WorkspaceUi(this)
        WorkspaceFloatService.hide(this)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::returnToConversation
        )
        try {
            val source = intent
            if (source.hasExtra("threadId") || source.hasExtra("screenKey") || source.hasExtra("generation")) {
                lease = readLease(source)
            }
        } catch (_: Exception) {
            finish()
            return
        }
        buildLayout()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            val next = readLease(intent)
            WorkspaceFloatService.hide(this)
            if (WorkspaceUi.same(lease, next)) {
                setIntent(intent)
                minimize.contentDescription = getString(exitLabel())
                requirement?.start()
            } else {
                // Readers and input gates stay bound to one lease for the Activity's lifetime.
                val fresh = Intent(this, MirrorActivity::class.java)
                intent.extras?.let { fresh.putExtras(it) }
                startActivity(fresh)
                suppressWorkspaceReturn = true
                finish()
            }
        } catch (_: Exception) {
            showNote(getString(R.string.workspace_interaction_changed))
        }
    }

    private fun readLease(source: Intent): JSONObject = WorkspaceCommands.request(
        "workspaces/preview",
        JSONObject().put("threadId", source.getStringExtra("threadId"))
            .put("screenKey", source.getStringExtra("screenKey"))
            .put("generation", source.getStringExtra("generation"))
    )

    @Deprecated("Uses the platform back dispatcher")
    override fun onBackPressed() = returnToConversation()

    private fun returnsHome(): Boolean = intent?.getBooleanExtra(AgentFrontendLauncher.RETURN_WORKSPACE_HOME, false) == true

    private fun exitLabel(): Int = if (returnsHome()) R.string.workspace_back else R.string.workspace_interaction_minimize

    override fun finish() {
        discardPendingInput()
        syncInputWindow(null)
        // Home launches restore its saved page even if the preview closes before admission.
        // Replacing a lease must not bring Home over the newly opened viewer.
        if (returnsHome() && !suppressWorkspaceReturn && !returnedToConversation) {
            returnedToConversation = true
            AgentFrontendLauncher.returnToWorkspaceHome(this)
        }
        super.finish()
    }

    private fun returnToConversation() {
        if (isFinishing || isDestroyed || returnedToConversation) return
        if (returnsHome()) {
            if (!AgentFrontendLauncher.returnToWorkspaceHome(this)) {
                Toast.makeText(this, R.string.workspace_unavailable_copy, Toast.LENGTH_LONG).show()
                return
            }
            returnedToConversation = true
            finish()
            return
        }
        val thread = admittedThread
        if (thread != null && thread == lease?.optString("threadId")) {
            if (!AgentFrontendLauncher.openThread(this, thread)) {
                Toast.makeText(this, R.string.workspace_unavailable_copy, Toast.LENGTH_LONG).show()
                return
            }
            returnedToConversation = true
        }
        finish()
    }

    private fun buildLayout() {
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = color(R.color.workspace_viewer_background)
        window.navigationBarColor = color(R.color.workspace_viewer_background)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val root = column()
        root.setBackgroundColor(color(R.color.workspace_viewer_background))
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            requirement?.setAvailableHeight(bottom - top - root.paddingTop - root.paddingBottom)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
            val resized = root.paddingBottom != maxOf(bars.bottom, keyboard)
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard))
            requirement?.setAvailableHeight(root.height - root.paddingTop - root.paddingBottom)
            if (resized && keyboard > bars.bottom) root.post {
                val focused = root.findFocus()
                if (focused is EditText) {
                    val bounds = Rect()
                    focused.getDrawingRect(bounds)
                    focused.requestRectangleOnScreen(bounds, true)
                }
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        appIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_tako_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(appIcon, LinearLayout.LayoutParams(dp(32), dp(32)))
        val labels = column()
        details = text(getString(R.string.workspace_interaction_title), ChatDesign.Role.CONTEXT).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        state = text(getString(R.string.workspace_connecting), ChatDesign.Role.META_MEDIUM).apply {
            maxLines = 2
            setTextColor(color(R.color.workspace_viewer_secondary))
        }
        val stateRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = dot(R.color.workspace_viewer_secondary)
        stateRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        stateRow.addView(state, LinearLayout.LayoutParams(-1, -2))
        labels.addView(details)
        labels.addView(stateRow)
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        edit = icon(R.drawable.ic_taku_edit, R.string.workspace_interaction_edit) {
            setEditingRequirement(!editingRequirement)
        }.apply { isEnabled = lease != null }
        header.addView(edit)
        minimize = icon(R.drawable.ic_taku_fullscreen_exit, exitLabel()) {
            if (returnsHome()) {
                returnToConversation()
                return@icon
            }
            val origin = lease
            val requested = origin != null && WorkspaceFloatService.show(this, origin) {
                if (!isFinishing && !isDestroyed) finish()
            }
            if (!requested) showNote(getString(R.string.workspace_interaction_float_unavailable))
        }.apply { isEnabled = lease != null }
        header.addView(minimize)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val frame = FrameLayout(this).apply {
            background = surface(R.color.workspace_viewer_control, 16)
            clipToOutline = true
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.workspace_preview_description)
            setOnTouchListener { _, event -> gesture(event) }
        }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        backInApp = icon(R.drawable.ic_taku_arrow_left, R.string.workspace_screen_back) {
            try { input(JSONObject().put("type", "key").put("keyCode", 4)) }
            catch (_: Exception) { showNote(getString(R.string.workspace_input_failed)) }
        }.apply {
            background = surface(R.color.workspace_viewer_background, 6)
            visibility = View.GONE
        }
        frame.addView(backInApp, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        val footer = column().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        root.addView(WorkspaceFocusScrollView(this, frame, footer), LinearLayout.LayoutParams(-1, 0, 1f))
        stepsFrame = FrameLayout(this).apply { minimumHeight = dp(48); visibility = View.GONE }
        stepRows = column().apply { setPadding(0, 0, dp(48), 0) }
        stepsFrame.addView(stepRows, FrameLayout.LayoutParams(-1, -2))
        activityToggle = icon(R.drawable.ic_taku_chevron_down, R.string.chat_expand_activity) {
            activityOpen = !activityOpen
            expansion?.toggle("workspace/details")
            activityContent.visibility = if (activityOpen) View.VISIBLE else View.GONE
            activityToggle.rotation = if (activityOpen) 0f else -90f
            activityToggle.contentDescription = getString(if (activityOpen) R.string.chat_collapse_activity else R.string.chat_expand_activity)
        }.apply { rotation = -90f }
        stepsFrame.addView(activityToggle, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        footer.addView(stepsFrame, LinearLayout.LayoutParams(-1, -2))
        activityRows = column()
        activityContent = activityRows
        activityContent.visibility = View.GONE
        footer.addView(activityContent, LinearLayout.LayoutParams(-1, -2))
        note = text("", ChatDesign.Role.META).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        footer.addView(note)
        val origin = lease ?: return
        activityRepository = AgentRepository(this)
        expansion = ConversationExpansion(getSharedPreferences("agent-settings", 0), origin.optString("threadId"))
        activityOpen = expansion!!.contains("workspace/details")
        activityToggle.rotation = if (activityOpen) 0f else -90f
        activityToggle.contentDescription = getString(if (activityOpen) R.string.chat_collapse_activity else R.string.chat_expand_activity)
        resumeRequest = WorkspaceExecutionRequest(this, origin, "returnAndResume", ::resumeReceipt)
        val requirementView = WorkspaceRequirementView(this, origin)
        requirement = requirementView
        requirementView.visibility = View.GONE
        requirementView.setOnAttentionRequired { setEditingRequirement(true, false) }
        footer.addView(requirementView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val stack = resources.configuration.fontScale > 1.3f
        val actions = LinearLayout(this).apply {
            isBaselineAligned = false
            orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        control = button(getString(R.string.workspace_interaction_take_over), R.drawable.ic_taku_hand) {
            if (resumeUnknown) resumeRequest?.refresh()
            else if (returnNeeded()) {
                discardPendingInput()
                resumeRequest?.submit("")
            } else change("takeControl")
        }
        stop = button(getString(R.string.workspace_interaction_stop), R.drawable.ic_taku_stop) { change("pause") }
        actions.addView(control, LinearLayout.LayoutParams(if (stack) -1 else 0, if (stack) -2 else -1, if (stack) 0f else 1f))
        actions.addView(stop, LinearLayout.LayoutParams(if (stack) -1 else 0, if (stack) -2 else -1, if (stack) 0f else 1f).apply {
            if (stack) topMargin = dp(8) else marginStart = dp(8)
        })
        footer.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        controls(false)
    }

    private fun setEditingRequirement(value: Boolean, focusInput: Boolean = value) {
        val view = requirement ?: return
        editingRequirement = value
        if (value) {
            discardPendingInput()
            syncInputWindow(currentScreen)
        }
        view.visibility = if (value) View.VISIBLE else View.GONE
        edit.setImageResource(if (value) R.drawable.ic_taku_check else R.drawable.ic_taku_edit)
        edit.contentDescription = getString(if (value) R.string.workspace_interaction_done_editing else R.string.workspace_interaction_edit)
        if (value) {
            if (focusInput) { view.start(); view.focusInput() }
        } else {
            view.findFocus()?.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            syncInputWindow(currentScreen)
        }
        refreshInputSession()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)
    private fun color(resource: Int) = getColor(resource)
    private fun text(value: String, role: ChatDesign.Role) = TextView(this).apply {
        text = value
        setTextColor(color(R.color.workspace_viewer_foreground))
        includeFontPadding = false
        ChatDesign.text(this, role)
    }
    private fun icon(resource: Int, label: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource)
        setColorFilter(color(R.color.workspace_viewer_foreground))
        contentDescription = getString(label)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { action() }
    }
    private fun surface(resource: Int, radius: Int) = GradientDrawable().apply {
        setColor(color(resource))
        cornerRadius = dp(radius).toFloat()
    }
    private fun dot(resource: Int) = View(this).apply {
        background = surface(resource, 999)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun buttonIcon(view: Button, icon: Int) {
        if (view.tag == icon) return
        view.tag = icon
        val drawable = ChatDesign.icon(this, icon, color(R.color.workspace_viewer_foreground))
        drawable?.setBounds(0, 0, dp(20), dp(20))
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.compoundDrawablePadding = dp(4)
    }
    private fun button(label: String, icon: Int, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextColor(color(R.color.workspace_viewer_foreground))
        ChatDesign.text(this, ChatDesign.Role.BODY)
        background = surface(R.color.workspace_viewer_control, 6)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        buttonIcon(this, icon)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { action() }
    }

    private fun showNote(value: String) {
        if (!::note.isInitialized) return
        main.removeCallbacks(expireResumeNotice)
        note.text = value
        ChatDesign.text(note, ChatDesign.Role.META)
        note.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun returnNeeded(): Boolean {
        val screen = currentScreen ?: return false
        return screen.optString("controlOwner") == "user" || screen.optString("controlOwner") == "paused" || screen.optBoolean("resumeRequired")
    }

    private fun resumeReceipt(record: JSONObject) {
        val status = record.optString("status")
        resumeUnknown = status == "unknown" || record.optBoolean("requiresReconciliation")
        resumeFailed = status == "failed" && !record.optBoolean("requiresReconciliation")
        if (WorkspaceExecutionRequest.unresolved(record)) {
            userControl = false
            controlRevision = -1
            discardPendingInput()
        }
        when {
            status == "pending" -> showNote(getString(R.string.workspace_interaction_resuming))
            resumeUnknown -> showNote(getString(R.string.workspace_interaction_unknown))
            status == "failed" -> showNote(WorkspaceRequirementView.error(this, record.optString("error")))
            status == "accepted" -> {
                val remaining = WorkspaceRequirementView.acceptedNoticeRemaining(record, System.currentTimeMillis())
                showNote(if (remaining > 0) getString(R.string.workspace_interaction_resume_accepted) else "")
                if (remaining > 0) main.postDelayed(expireResumeNotice, remaining)
                // A receipt is historical. Only a fresh catalog can establish the current owner.
                refreshOwnerCatalog()
            }
        }
        controls(connected)
    }

    private fun refreshOwnerCatalog() {
        val origin = lease ?: return
        if (!resumed) return
        val token = watchGeneration
        NativeClient.io.execute {
            try {
                val screens = WorkspaceCommands.call(this, "workspaces/list", JSONObject().put("threadId", origin.getString("threadId")), Process.myUid()).optJSONArray("screens")
                    ?: return@execute
                for (index in 0 until screens.length()) {
                    val screen = screens.optJSONObject(index)
                    if (WorkspaceUi.same(origin, screen)) { owner(screen, token); break }
                }
            } catch (_: Exception) {
                // The frame watcher keeps input unavailable until a fresh catalog succeeds.
            }
        }
    }

    private fun loadActivity() {
        val repository = activityRepository ?: return
        val origin = lease ?: return
        if (!resumed || activityLoading) return
        activityLoading = true
        val token = watchGeneration
        repository.snapshot(origin.optString("threadId"), object : AgentRepository.Callback<JSONObject> {
            override fun ready(snapshot: JSONObject) {
                if (!resumed || token != watchGeneration || isFinishing || isDestroyed) return
                activityLoading = false
                try {
                    snapshot.optJSONObject("thread")?.let { thread ->
                        var entries = ArrayList<AgentConversation.Entry>()
                        for (entry in AgentConversation.read(thread).entries) {
                            if (entry.kind == AgentConversation.Kind.TOOL && entry.type != "contextCompaction" &&
                                entry.type != "enteredReviewMode" && entry.type != "exitedReviewMode") entries.add(entry)
                        }
                        if (entries.size > 4) {
                            val chosen = HashSet<String>()
                            for (index in entries.lastIndex downTo 0) {
                                if (chosen.size >= 4) break
                                if (stepWorking(entries[index].item)) chosen.add(entries[index].key())
                            }
                            for (index in entries.lastIndex downTo 0) {
                                if (chosen.size >= 4) break
                                chosen.add(entries[index].key())
                            }
                            val visible = ArrayList<AgentConversation.Entry>()
                            for (entry in entries) if (entry.key() in chosen) visible.add(entry)
                            entries = visible
                        }
                        bindActivity(entries, snapshot.optJSONObject("native"))
                    }
                } catch (_: Exception) {
                    // Unavailable history cannot supply progress.
                }
                main.postDelayed(activityPoll, 2000)
            }
            override fun failed(message: String) {
                if (!resumed || token != watchGeneration || isFinishing || isDestroyed) return
                activityLoading = false
                main.postDelayed(activityPoll, 2000)
            }
        })
    }

    private fun bindActivity(entries: List<AgentConversation.Entry>, nativeState: JSONObject?) {
        val outputs = nativeState?.optJSONObject("outputs")
        val signature = buildString {
            for (entry in entries) append(entry.key()).append(entry.item.toString()).append(outputs?.optString(entry.id) ?: "")
        }
        if (signature == activitySignature) return
        activitySignature = signature
        stepsFrame.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        activityContent.visibility = if (activityOpen && entries.isNotEmpty()) View.VISIBLE else View.GONE
        stepRows.removeAllViews()
        activityRows.removeAllViews()
        val labels = WorkspaceUi.toolLabels(this)
        for ((index, entry) in entries.withIndex()) {
            var title = NativeToolPresentation.title(entry.item, labels)
            if (entry.type == "commandExecution") title = getString(R.string.workspace_interaction_task_step)
            if (title == entry.item.optString("tool") || title.contains("__") || title.contains("functions."))
                title = getString(R.string.workspace_interaction_app_step)
            val stepStatus = NativeToolPresentation.status(entry.item, labels)
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(dot(stepColor(entry.item)), LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            val label = text(title, ChatDesign.Role.META).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(color(if (stepWorking(entry.item)) R.color.workspace_viewer_foreground else R.color.workspace_viewer_secondary))
            }
            row.addView(label, LinearLayout.LayoutParams(-1, -2))
            row.contentDescription = title + if (stepStatus.isEmpty()) "" else ". $stepStatus"
            stepRows.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < entries.lastIndex) {
                val divider = View(this).apply { setBackgroundColor(color(R.color.workspace_viewer_border)) }
                stepRows.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
            activityRows.addView(text(title + if (stepStatus.isEmpty()) "" else " · $stepStatus", ChatDesign.Role.META_MEDIUM).apply {
                setPadding(0, dp(8), 0, dp(4))
            })
            val value = NativeToolPresentation.details(entry.item, outputs?.optString(entry.id) ?: "", labels)
            if (value.isNotEmpty()) activityRows.addView(text(value, ChatDesign.Role.META).apply {
                setTextColor(color(R.color.workspace_viewer_secondary))
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(8))
            })
        }
    }

    private fun stepWorking(item: JSONObject) = item.optString("status") == "inProgress" || item.optString("status") == "in_progress"
    private fun stepColor(item: JSONObject): Int {
        if (item.opt("success") == false || item.optString("status") == "failed" || item.optString("status") == "errored" ||
            item.optString("type") == "commandExecution" && item.has("exitCode") && !item.isNull("exitCode") && item.optInt("exitCode") != 0)
            return R.color.taku_status_error
        if (stepWorking(item)) return R.color.taku_status_attention
        if (item.optString("status") == "completed") return R.color.taku_status_success
        return R.color.taku_status_waiting
    }

    override fun onResume() {
        super.onResume()
        if (!::preview.isInitialized || isFinishing) return
        resumed = true
        val token = ++watchGeneration
        resumeRequest?.start()
        requirement?.start()
        loadActivity()
        Thread({ watch(token) }, "workspace-preview").start()
    }

    override fun onPause() {
        stopWatching()
        super.onPause()
    }

    private fun stopWatching() {
        resumed = false
        userControl = false
        connected = false
        controlRevision = -1
        ++watchGeneration
        discardPendingInput()
        syncInputWindow(null)
        try { frames?.close() } catch (_: Exception) {}
        main.removeCallbacks(activityPoll)
        main.removeCallbacks(expireResumeNotice)
        activityLoading = false
        resumeRequest?.stop()
        requirement?.stop()
    }

    override fun onDestroy() {
        stopWatching()
        commands.shutdownNow()
        activityRepository?.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed) {
            syncInputWindow(currentScreen)
            refreshInputSession()
        } else discardPendingInput()
    }

    private fun syncInputWindow(screen: JSONObject?) {
        val token = window.peekDecorView()?.windowToken
        val origin = lease
        val displayId = screen?.optInt("displayId", -1) ?: -1
        val next = if (resumed && connected && origin != null && token != null && displayId > 0 &&
            WorkspaceUi.same(origin, screen)) {
            Lease(origin.getString("threadId"), origin.getString("screenKey"), origin.getString("generation"), displayId)
        } else null
        if (next == null && inputLease == null) return
        if (next != null && next == inputLease && token == inputWindow && inputEditing == editingRequirement) return
        try {
            val controller = WorkspaceInputConnection.controller() ?: return
            if (WorkspaceInputConnection.setMirror(controller, if (next == null) inputWindow else token, next, editingRequirement)) {
                inputWindow = if (next == null) null else token
                inputLease = next
                inputEditing = editingRequirement
            }
        } catch (error: Exception) {
            Log.w("TakuWorkspaceInput", "Mirror window update failed", error)
        }
    }

    private class Channel(name: String) : Closeable {
        val socket = LocalSocket()
        private val request: PrintWriter
        private val response: DataInputStream
        init {
            try {
                socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 8000
                val uid = socket.peerCredentials.uid
                if (uid != 0 && uid != 1000 && uid != 2000) throw IOException("SCREEN_HOST_IDENTITY")
            } catch (failure: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw failure
            }
            request = PrintWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8), true)
            response = DataInputStream(socket.inputStream)
        }
        fun call(value: String): ByteArray {
            request.println(value)
            val size = response.readInt()
            if (size < 0 || size > 4_000_000) throw IOException("INVALID_FRAME_SIZE")
            return ByteArray(size).also { response.readFully(it) }
        }
        override fun close() = socket.close()
    }

    private fun verifyLease(): JSONObject? {
        val origin = lease ?: return null
        val entries = WorkspaceCommands.call(this, "workspaces/list", JSONObject().put("threadId", origin.getString("threadId")), Process.myUid())
            .getJSONArray("screens")
        for (index in 0 until entries.length()) {
            val current = entries.getJSONObject(index)
            if (WorkspaceUi.same(origin, current)) {
                val status = current.optString("status")
                if (!current.optBoolean("admitted") || status == "unavailable" || status == "rebind_required" ||
                    status == "stale" || status == "disconnected") throw IOException("SCREEN_REBIND_REQUIRED")
                return current
            }
        }
        throw IOException("SCREEN_GENERATION_CHANGED")
    }

    private fun validateHost(channel: Channel, meta: JSONObject, binding: JSONObject?) {
        val peer = channel.socket.peerCredentials
        if (meta.getInt("uid") != peer.uid || meta.getInt("pid") != peer.pid || meta.getInt("displayId") <= 0)
            throw IOException("SCREEN_HOST_IDENTITY")
        if (binding != null) {
            val origin = lease ?: throw IOException("INVALID_SCREEN_LEASE")
            val key = origin.getString("screenKey")
            if (key != meta.getString("screenKey") || origin.getString("generation") != meta.getString("generation") ||
                binding.getInt("displayId") != meta.getInt("displayId") || "taku-mirror-$key" != meta.getString("mirrorSocket") ||
                "Taku-Independent-Screen-$key" != meta.getString("name")) throw IOException("SCREEN_GENERATION_CHANGED")
        } else if (meta.optString("screenKey") != "default" || meta.optString("mirrorSocket") != "taku-mirror")
            throw IOException("SCREEN_HOST_IDENTITY")
        val x = meta.getInt("width")
        val y = meta.getInt("height")
        if (x < 1 || y < 1 || x > 4096 || y > 4096) throw IOException("INVALID_SCREEN_SIZE")
        width = x
        height = y
    }

    private fun watch(token: Int) {
        while (resumed && token == watchGeneration) {
            try {
                var binding = verifyLease()
                if (!resumed || token != watchGeneration) return
                val socketName = lease?.let { "taku-mirror-${it.getString("screenKey")}" } ?: "taku-mirror"
                Channel(socketName).use { channel ->
                    if (!resumed || token != watchGeneration) return
                    frames = channel.socket
                    validateHost(channel, JSONObject(String(channel.call("status"), StandardCharsets.UTF_8)), binding)
                    owner(binding, token)
                    var checked = SystemClock.elapsedRealtime()
                    while (resumed && token == watchGeneration) {
                        if (SystemClock.elapsedRealtime() - checked > 1500) {
                            binding = verifyLease()
                            validateHost(channel, JSONObject(String(channel.call("status"), StandardCharsets.UTF_8)), binding)
                            owner(binding, token)
                            checked = SystemClock.elapsedRealtime()
                        }
                        val jpeg = channel.call("frame")
                        if (jpeg.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            if (bitmap != null) main.post {
                                if (resumed && token == watchGeneration && !isFinishing && !isDestroyed) {
                                    if (!connected) {
                                        connected = true
                                        syncInputWindow(currentScreen)
                                    }
                                    preview.setImageBitmap(bitmap)
                                    val current = currentScreen
                                    if (current != null) bindHeaderStatus(current) else state.setText(R.string.workspace_view_only)
                                    controls(true)
                                } else bitmap.recycle()
                            }
                        } else main.post {
                            if (resumed && token == watchGeneration && !isFinishing && !isDestroyed) state.setText(R.string.workspace_waiting_frame)
                        }
                        Thread.sleep(200)
                    }
                }
            } catch (_: Exception) {
                if (token == watchGeneration) { connected = false; userControl = false; controlRevision = -1 }
                main.post {
                    if (resumed && token == watchGeneration && !isFinishing && !isDestroyed) {
                        discardPendingInput()
                        syncInputWindow(null)
                        preview.setImageDrawable(null)
                        state.setText(R.string.workspace_connection_lost)
                        controls(false)
                    }
                }
                try { Thread.sleep(1200) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            } finally {
                if (token == watchGeneration) frames = null
            }
        }
    }

    private fun owner(current: JSONObject?, token: Int) {
        val apply = Runnable {
            if (!resumed || token != watchGeneration || isFinishing || isDestroyed) return@Runnable
            val revision = current?.optLong("controlRevision", -1) ?: -1
            if (changing || revision < lastObservedControlRevision) return@Runnable
            val owned = current != null && current.optString("controlOwner") == "user" && revision >= 0 && resumeRequest?.busy() != true
            if (revision != controlRevision || owned != userControl) discardPendingInput()
            lastObservedControlRevision = revision
            controlRevision = revision
            userControl = owned
            ownerCheckedAt = SystemClock.elapsedRealtime()
            currentScreen = current
            syncInputWindow(current)
            if (current != null && current.optBoolean("admitted") && WorkspaceUi.same(lease, current)) admittedThread = current.getString("threadId")
            details.text = if (current == null) getString(R.string.workspace_interaction_title)
                else getString(R.string.workspace_interaction_app, workspace.appLabel(current))
            ChatDesign.text(details, ChatDesign.Role.CONTEXT)
            if (current != null && current.optString("packageName") != currentPackage) {
                currentPackage = current.optString("packageName")
                try { appIcon.setImageDrawable(packageManager.getApplicationIcon(currentPackage)) }
                catch (_: Exception) { appIcon.setImageResource(R.drawable.ic_tako_mark) }
            }
            if (current != null) bindHeaderStatus(current)
            controls(connected)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    private fun fullStatus(screen: JSONObject): String {
        if (screen.optString("controlOwner") == "user") return workspace.status(screen)
        if (screen.optBoolean("resumeRequired") && !screen.optBoolean("canResume")) return getString(R.string.workspace_interaction_paused)
        return workspace.status(screen)
    }

    private fun bindHeaderStatus(screen: JSONObject) {
        state.text = fullStatus(screen)
        ChatDesign.text(state, ChatDesign.Role.META_MEDIUM)
        val status = screen.optString("status")
        val owner = screen.optString("controlOwner")
        val resource = if (status == "unavailable" || status == "failed" || status == "disconnected") R.color.taku_status_error
            else if (owner != "paused" && owner != "user" && !screen.optBoolean("resumeRequired") &&
                (screen.optString("activeTurnId").isNotEmpty() || status == "running" || status == "working" || screen.optBoolean("waitingForUser"))) R.color.taku_status_attention
            else R.color.workspace_viewer_secondary
        statusDot.background = surface(resource, 999)
    }

    private fun controls(available: Boolean) {
        val button = control ?: return
        val pending = resumeRequest?.busy() == true
        button.setText(controlLabel())
        ChatDesign.text(button, ChatDesign.Role.BODY)
        buttonIcon(button, if (resumeUnknown) R.drawable.ic_taku_refresh else if (returnNeeded()) R.drawable.ic_taku_play else R.drawable.ic_taku_hand)
        button.isEnabled = available && !changing && (!pending || resumeUnknown)
        // An uncertain resume receipt cannot take away the user's exact-lease interrupt.
        stop?.isEnabled = !changing && freshStopLease()
        backInApp.visibility = if (userControl) View.VISIBLE else View.GONE
        backInApp.isEnabled = available && canQueueInput()
        refreshInputSession()
    }

    private fun freshStopLease(): Boolean {
        val current = currentScreen ?: return false
        return WorkspaceUi.same(lease, current) && current.optLong("controlRevision", -1) >= 0 &&
            ownerCheckedAt > 0 && SystemClock.elapsedRealtime() - ownerCheckedAt <= 3000
    }

    private fun controlLabel() = when {
        resumeUnknown -> R.string.workspace_interaction_check_status
        returnNeeded() -> if (resumeFailed) R.string.workspace_interaction_retry_continue else R.string.workspace_interaction_return_continue
        else -> R.string.workspace_interaction_take_over
    }

    private fun change(action: String) {
        val origin = lease ?: return
        if (changing) return
        if (action == "pause" && !freshStopLease()) { controls(connected); return }
        changing = true
        controlRevision = -1
        userControl = false
        discardPendingInput()
        controls(false)
        state.setText(R.string.workspace_action_pending)
        val token = watchGeneration
        NativeClient.io.execute {
            try {
                val result = WorkspaceCommands.call(this, "workspaces/$action", origin, Process.myUid())
                main.post {
                    changing = false
                    if (!resumed || token != watchGeneration || isFinishing || isDestroyed) return@post
                    val screen = result.optJSONObject("screen")
                    if (screen != null && WorkspaceUi.same(origin, screen)) owner(screen, token)
                    if (action == "pause") {
                        if (screen != null && WorkspaceUi.same(origin, screen) && screen.optString("controlOwner") == "paused" &&
                            screen.optString("activeTurnId").isEmpty() && !screen.optBoolean("stopping")) returnToConversation()
                        else { showNote(getString(R.string.workspace_interaction_stop_unconfirmed)); controls(false) }
                    } else {
                        showNote("")
                        controls(connected)
                    }
                }
            } catch (error: Exception) {
                main.post {
                    changing = false
                    if (resumed && token == watchGeneration && !isFinishing && !isDestroyed) {
                        userControl = false
                        cancelGesture()
                        showNote(workspace.error(error.message))
                        controls(false)
                    }
                }
            }
        }
    }

    private fun gesture(event: MotionEvent): Boolean {
        if (!canQueueInput()) {
            cancelGesture()
            return false
        }
        val scale = minOf(preview.width.toFloat() / width, preview.height.toFloat() / height)
        if (scale <= 0) return false
        val left = (preview.width - width * scale) / 2
        val top = (preview.height - height * scale) / 2
        val x = (event.x - left) / scale
        val y = (event.y - top) / scale
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || x < 0 || y < 0 || x >= width || y >= height) {
            cancelGesture()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            preview.parent.requestDisallowInterceptTouchEvent(true)
            tracking = true
            downX = x
            downY = y
            downAt = event.eventTime
            downControlRevision = controlRevision
            downControlGeneration = controlGeneration
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && tracking) {
            cancelGesture()
            if (downControlRevision != controlRevision || downControlGeneration != controlGeneration) return true
            val duration = event.eventTime - downAt
            val distance = Math.hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
            try {
                val command = JSONObject()
                if (distance < 18 && duration < 500) command.put("type", "tap").put("x", Math.round(x)).put("y", Math.round(y))
                else command.put("type", "swipe").put("fromX", Math.round(downX)).put("fromY", Math.round(downY))
                    .put("toX", Math.round(x)).put("toY", Math.round(y)).put("durationMs", maxOf(150L, minOf(1200L, duration)))
                input(command, downControlRevision, downControlGeneration)
                preview.performClick()
            } catch (_: Exception) { state.setText(R.string.workspace_input_failed) }
        }
        return true
    }

    private fun cancelGesture() {
        tracking = false
        if (::preview.isInitialized) preview.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun canQueueInput(): Boolean = resumed && lease != null && connected && userControl &&
        controlRevision >= 0 && hasWindowFocus() && !changing && !editingRequirement &&
        SystemClock.elapsedRealtime() - ownerCheckedAt <= 3000 && resumeRequest?.busy() != true

    private fun discardPendingInput() {
        ++controlGeneration
        cancelGesture()
        commands.queue.clear()
        inputSession?.close()
        inputSession = null
    }

    private fun refreshInputSession() {
        if (!canQueueInput()) {
            if (inputSession != null) discardPendingInput()
            return
        }
        if (inputSession != null) return
        val screen = currentScreen ?: return
        val token = window.peekDecorView()?.windowToken ?: return
        val session = InputSession(context = this, window = token, screen = screen, revision = controlRevision)
        inputSession = session
        commands.execute { transferInput(session, null) }
    }

    private fun input(input: JSONObject, revision: Long = controlRevision, controlToken: Long = controlGeneration) {
        if (!canQueueInput() || revision != controlRevision || controlToken != controlGeneration) return
        refreshInputSession()
        val session = inputSession ?: return
        try {
            commands.execute { transferInput(session, input) }
        } catch (_: RejectedExecutionException) {
            showNote(getString(R.string.workspace_input_failed))
        }
    }

    private fun transferInput(session: InputSession, input: JSONObject?) {
        if (session.closed || !connected) return
        try {
            if (input == null) session.open() else session.send(input)
        } catch (_: Exception) {
            session.close()
            main.post {
                if (session !== inputSession || isFinishing || isDestroyed) return@post
                discardPendingInput()
                userControl = false
                state.setText(R.string.workspace_input_failed)
                controls(false)
            }
        }
    }

    /** The command worker owns the connection. The UI can close it when the lease changes. */
    private class InputSession(
        private val context: Context,
        private val window: IBinder,
        private val screen: JSONObject,
        private val revision: Long,
    ) {
        private var connection: ParcelFileDescriptor? = null
        private var request: DataOutputStream? = null
        private var reply: DataInputStream? = null
        private var legacy = false
        @Volatile var closed = false
            private set

        fun open() {
            val controller = WorkspaceInputConnection.controller()
            if (controller == null) {
                legacy = true
                return
            }
            val lease = Lease(screen.getString("threadId"), screen.getString("screenKey"),
                screen.getString("generation"), screen.getInt("displayId"))
            val (client, broker) = ParcelFileDescriptor.createSocketPair()
            var attached = false
            try {
                broker.use {
                    legacy = !WorkspaceInputConnection.openInput(controller, window, lease,
                        screen.getInt("userId"), revision, it)
                    if (legacy) return
                    val fd = client.fileDescriptor
                    val timeout = StructTimeval.fromMillis(8000)
                    Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, timeout)
                    Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, timeout)
                    synchronized(this) {
                        if (closed) return
                        request = DataOutputStream(FileOutputStream(fd))
                        reply = DataInputStream(FileInputStream(fd))
                        connection = client
                        attached = true
                    }
                }
            } finally {
                if (!attached) client.close()
            }
        }

        fun send(input: JSONObject) {
            if (closed) return
            if (legacy) {
                val args = JSONObject().put("threadId", screen.getString("threadId"))
                    .put("screenKey", screen.getString("screenKey"))
                    .put("generation", screen.getString("generation"))
                    .put("controlRevision", revision).put("input", input)
                WorkspaceCommands.call(context, "workspaces/input", args, Process.myUid())
                return
            }
            val output = request ?: throw IOException("WORKSPACE_INPUT_FAILED")
            output.writeUTF(input.toString())
            if (reply?.readUnsignedByte() != 1) throw IOException("WORKSPACE_INPUT_FAILED")
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            val client = connection ?: return
            try { Os.shutdown(client.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: ErrnoException) {}
            try { client.close() } catch (_: IOException) {}
        }
    }
}
