package org.agentos.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.UUID
import org.json.JSONObject

/** One movable, view-only window. All execution and input authority stays in TaskBroker. */
class WorkspaceFloatService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var windowContext: Context
    private var manager: WindowManager? = null
    private var layout: WindowManager.LayoutParams? = null
    private var root: FrameLayout? = null
    private var header: LinearLayout? = null
    private var footer: LinearLayout? = null
    private var preview: ChatWorkspacePreview? = null
    private var requirement: WorkspaceRequirementView? = null
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var appIcon: ImageView
    private lateinit var stateDot: View
    private lateinit var primary: Button
    private lateinit var secondary: ImageButton
    private var editorOpen = false
    private var reviewPending = false
    private var firstFrameReady = false
    private var deferredEditor = false
    private var deferredEditorFocus = false
    private var compactX = 0
    private var compactY = 0
    private var restoreCompactPosition = false
    private var currentPackage = ""
    private var lastStateLabel = R.string.chat_workspace_connecting
    private var body: ScrollView? = null
    private var lease: JSONObject? = null
    private var admittedThread: String? = null
    private var launchId: String? = null
    private var attached = false
    private var polling = false
    private var editing = false
    private var suppressed = false
    private var destroyed = false
    private var positioned = false
    private var epoch = 0
    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private val poll = Runnable { refresh() }

    companion object {
        private var pendingId: String? = null
        private var pendingAttached: Runnable? = null

        @JvmStatic
        @JvmOverloads
        fun show(context: Context, lease: JSONObject?, onAttached: Runnable? = null): Boolean {
            if (lease == null || !AgentGatewayProtocol.isUuid(lease.optString("threadId"))) return false
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, R.string.workspace_float_permission, Toast.LENGTH_SHORT).show()
                return false
            }
            try {
                WorkspaceCommands.request("workspaces/preview", JSONObject()
                    .put("threadId", lease.getString("threadId"))
                    .put("screenKey", lease.getString("screenKey"))
                    .put("generation", lease.getString("generation")))
                val id = UUID.randomUUID().toString()
                pendingId = id
                pendingAttached = onAttached
                val intent = Intent(context, WorkspaceFloatService::class.java)
                    .putExtra("launchId", id)
                    .putExtra("threadId", lease.getString("threadId"))
                    .putExtra("screenKey", lease.getString("screenKey"))
                    .putExtra("generation", lease.getString("generation"))
                if (context.startService(intent) == null) {
                    pendingId = null
                    pendingAttached = null
                    return false
                }
                val application = context.applicationContext
                Handler(Looper.getMainLooper()).postDelayed({
                    if (id == pendingId) {
                        pendingId = null
                        pendingAttached = null
                        hide(application)
                        Toast.makeText(application, R.string.workspace_float_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }, 8000)
                return true
            } catch (_: Exception) {
                pendingId = null
                pendingAttached = null
                Toast.makeText(context, R.string.workspace_float_unavailable, Toast.LENGTH_SHORT).show()
                return false
            }
        }

        @JvmStatic
        fun hide(context: Context) {
            context.stopService(Intent(context, WorkspaceFloatService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("attached=$attached suppressed=$suppressed editor=$editorOpen firstFrame=$firstFrameReady epoch=$epoch")
        writer.println(preview?.diagnostics() ?: "preview=none")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            requireNotNull(intent)
            launchId = intent.getStringExtra("launchId")
            val next = WorkspaceCommands.request("workspaces/preview", JSONObject()
                .put("threadId", intent.getStringExtra("threadId"))
                .put("screenKey", intent.getStringExtra("screenKey"))
                .put("generation", intent.getStringExtra("generation")))
            if (!WorkspaceUi.same(next, lease) || root == null) {
                removeWindow()
                lease = next
                admittedThread = null
                positioned = false
                build()
            } else {
                // Each presentation handoff waits for a fresh validated frame.
                firstFrameReady = false
                deferredEditor = deferredEditor || editorOpen
                editorOpen = false
                requirement?.visibility = View.GONE
                editing(false)
                editorLabels()
                clamp()
            }
            main.removeCallbacks(poll)
            refresh()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.workspace_float_unavailable, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun dp(value: Int) = Math.round(value * windowContext.resources.displayMetrics.density)
    private fun color(resource: Int) = windowContext.getColor(resource)
    private fun column() = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, role: ChatDesign.Role) = TextView(windowContext).apply {
        text = value
        setTextColor(color(R.color.taku_text_inverse))
        ChatDesign.text(this, role)
    }

    private fun build() {
        val origin = lease ?: return
        firstFrameReady = false
        reviewPending = WorkspaceUi.reviewPending(origin)
        deferredEditor = false
        deferredEditorFocus = false
        lastStateLabel = R.string.chat_workspace_connecting
        val windowEpoch = epoch
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windows = windowContext.getSystemService(WindowManager::class.java)
        manager = windows
        val windowRoot = FrameLayout(windowContext).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        root = windowRoot
        suppressed = true
        val card = column().apply {
            setPadding(dp(8), 0, dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(color(R.color.workspace_viewer_background))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(2).toFloat()
            clipToOutline = true
        }
        windowRoot.addView(card, FrameLayout.LayoutParams(-1, -1))
        val headerRow = LinearLayout(windowContext).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, 0, 0, 0)
        }
        header = headerRow
        appIcon = ImageView(windowContext).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        headerRow.addView(appIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
        title = text(getString(R.string.workspace_float_title), ChatDesign.Role.META_MEDIUM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        stateDot = View(windowContext)
        headerRow.addView(stateDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(4); marginEnd = dp(4) })
        status = text(getString(R.string.chat_workspace_connecting), ChatDesign.Role.META_MEDIUM).apply {
            setTextColor(color(R.color.workspace_viewer_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(status, LinearLayout.LayoutParams(-2, -2))
        headerRow.contentDescription = getString(R.string.workspace_float_drag)
        headerRow.setOnTouchListener(::drag)
        val close = icon(R.drawable.ic_taku_close, R.string.workspace_float_close, ::stopSelf)
        val closePlate = GradientDrawable().apply {
            setColor(color(R.color.workspace_viewer_control))
            cornerRadius = dp(6).toFloat()
        }
        close.background = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), InsetDrawable(closePlate, dp(10)), null)
        close.setPadding(dp(16), dp(16), dp(16), dp(16))
        headerRow.addView(close)
        card.addView(headerRow)
        val scroll = ScrollView(windowContext).apply { isFillViewport = false }
        body = scroll
        val contents = column()
        scroll.addView(contents)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val framePreview = ChatWorkspacePreview(windowContext, DrawerTheme(windowContext, false)) { expand() }
        preview = framePreview
        framePreview.setOnFrameReady {
            if (destroyed || suppressed || !attached || windowEpoch != epoch) return@setOnFrameReady
            firstFrameReady = true
            primary.isEnabled = true
            if (launchId != null && launchId == pendingId) {
                val callback = pendingAttached
                pendingId = null
                pendingAttached = null
                callback?.run()
            }
            if (deferredEditor) {
                val focus = deferredEditorFocus
                deferredEditor = false
                deferredEditorFocus = false
                main.post {
                    if (!destroyed && windowEpoch == epoch && firstFrameReady) editor(true, focus)
                }
            }
        }
        framePreview.floatingLease(origin)
        contents.addView(framePreview, LinearLayout.LayoutParams(-1, -2))
        val requirementView = WorkspaceRequirementView(windowContext, origin)
        requirement = requirementView
        requirementView.setOnEditingChanged(::editing)
        requirementView.setOnAttentionRequired {
            main.post {
                if (!destroyed && windowEpoch == epoch && root != null && layout != null && !editorOpen) editor(true, false)
            }
        }
        deferredEditor = try {
            WorkspaceExecutionRequest.unresolved(JSONObject(getSharedPreferences("workspace-interactions", 0)
                .getString(WorkspaceExecutionRequest.identity(origin) + ".addRequirement", "{}")))
        } catch (_: Exception) { false }
        editorOpen = false
        requirementView.visibility = View.GONE
        contents.addView(requirementView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        requirementView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (editing && bottom - top > oldBottom - oldTop) body?.post {
                val currentBody = body
                if (!destroyed && windowEpoch == epoch && currentBody != null && editing)
                    currentBody.smoothScrollTo(0, maxOf(0, requirementView.bottom - currentBody.height))
            }
        }
        val footerRow = LinearLayout(windowContext).apply { gravity = Gravity.CENTER }
        footer = footerRow
        primary = Button(windowContext).apply {
            isAllCaps = false
            includeFontPadding = false
            ChatDesign.text(this, ChatDesign.Role.META_MEDIUM)
            minHeight = dp(48)
            minimumHeight = dp(48)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.taku_text_inverse))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = compactActionBackground()
            setOnClickListener { if (editorOpen) editor(false) else expand() }
        }
        footerRow.addView(primary, LinearLayout.LayoutParams(0, -2, 1f))
        secondary = icon(R.drawable.ic_taku_chat, R.string.workspace_float_chat, ::openChat)
        footerRow.addView(secondary, LinearLayout.LayoutParams(dp(48), dp(48)))
        contents.addView(footerRow, LinearLayout.LayoutParams(-1, -2))
        editorLabels()
        val params = WindowManager.LayoutParams(
            minOf(dp(184), safeArea().width()), dp(360), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setTitle("Taku workspace preview")
        }
        layout = params
        windowRoot.setOnApplyWindowInsetsListener { _, insets -> clamp(); insets }
        windowRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clamp() }
        clamp()
        windows.addView(windowRoot, params)
        attached = true
    }

    private fun icon(drawable: Int, description: Int, action: () -> Unit) = ImageButton(windowContext).apply {
        contentDescription = getString(description)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageDrawable(ChatDesign.icon(windowContext, drawable, color(R.color.taku_text_inverse)))
        background = compactActionBackground()
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { action() }
    }

    private fun compactActionBackground(): RippleDrawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(6).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, mask)
    }

    private fun expandBackground(): RippleDrawable {
        val face = GradientDrawable().apply { setColor(color(R.color.taku_surface)); cornerRadius = dp(6).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x14000000), InsetDrawable(face, 0, dp(8), 0, dp(8)), null)
    }

    private fun safeArea(): Rect {
        val metrics = checkNotNull(manager).currentWindowMetrics
        val bounds = Rect(metrics.bounds)
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        bounds.left += insets.left + dp(8)
        bounds.top += insets.top + dp(8)
        bounds.right -= insets.right + dp(8)
        bounds.bottom -= insets.bottom + dp(8)
        return bounds
    }

    private fun clamp() {
        val windowRoot = root ?: return
        val params = layout ?: return
        val windows = manager ?: return
        val area = safeArea()
        val enlarged = windowContext.resources.configuration.fontScale > 1.3f
        val preferredWidth = if (editorOpen) dp(if (enlarged) 328 else 288)
            else maxOf(dp(if (enlarged) 252 else 176), minOf(dp(if (enlarged) 280 else 184), Math.round((area.width() + dp(16)) * .42f)))
        val width = maxOf(1, minOf(preferredWidth, area.width()))
        status.maxWidth = Math.round(width * .32f)
        val headerHeight = maxOf(dp(48), header?.measuredHeight ?: 0)
        val editorHeight = if (editorOpen) maxOf(dp(48), requirement?.measuredHeight ?: 0) + dp(8) else 0
        val footerHeight = maxOf(dp(48), footer?.measuredHeight ?: 0)
        val frameHeight = if (editorOpen) 0 else maxOf(dp(96), minOf(dp(200),
            minOf(Math.round((width - dp(16)) * 1.25f), area.height() - headerHeight - footerHeight - dp(4))))
        if (!editorOpen) preview?.setFloatingPreviewLimit(frameHeight)
        val height = maxOf(1, minOf(headerHeight + frameHeight + editorHeight + footerHeight + dp(4), area.height()))
        requirement?.setAvailableHeight(height)
        var x = if (positioned) params.x else area.left
        var y = if (positioned) params.y else maxOf(area.top, area.bottom - height - dp(64))
        if (restoreCompactPosition && !editorOpen && windows.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom == 0) {
            x = compactX
            y = compactY
            restoreCompactPosition = false
        }
        x = maxOf(area.left, minOf(x, area.right - width))
        y = maxOf(area.top, minOf(y, area.bottom - height))
        val changed = x != params.x || y != params.y || width != params.width || height != params.height
        params.x = x
        params.y = y
        params.width = width
        params.height = height
        positioned = true
        if (attached && changed) {
            try { windows.updateViewLayout(windowRoot, params) }
            catch (_: RuntimeException) { stopSelf() }
        }
    }

    private fun editorLabels() {
        renderHeaderState()
        primary.setText(if (editorOpen) R.string.workspace_float_back_preview else R.string.workspace_float_expand)
        ChatDesign.text(primary, ChatDesign.Role.META_MEDIUM)
        primary.setTextColor(color(if (editorOpen) R.color.taku_text_inverse else R.color.taku_text_primary))
        primary.background = if (editorOpen) compactActionBackground() else expandBackground()
        primary.setPadding(dp(8), dp(4), dp(8), dp(4))
        val arrow = ChatDesign.icon(windowContext, if (editorOpen) R.drawable.ic_taku_arrow_left else R.drawable.ic_taku_arrow_up_right,
            color(R.color.taku_text_inverse))
        arrow.setBounds(0, 0, dp(16), dp(16))
        primary.compoundDrawablePadding = dp(6)
        primary.setCompoundDrawablesRelative(if (editorOpen) arrow else null, null, null, null)
        val actionSize = primary.layoutParams as LinearLayout.LayoutParams
        actionSize.width = 0
        actionSize.weight = 1f
        primary.layoutParams = actionSize
        primary.isEnabled = editorOpen || firstFrameReady
        secondary.visibility = if (editorOpen) View.VISIBLE else View.GONE
        preview?.visibility = if (editorOpen) View.GONE else View.VISIBLE
    }

    private fun renderHeaderState() {
        status.setText(if (editorOpen) R.string.workspace_float_add_note else lastStateLabel)
        ChatDesign.text(status, ChatDesign.Role.META_MEDIUM)
        status.visibility = if (editorOpen || lastStateLabel != R.string.workspace_float_ready) View.VISIBLE else View.GONE
        stateDot.visibility = if (lastStateLabel == R.string.workspace_float_ready) View.GONE else View.VISIBLE
        val description = "${title.text}. ${status.text}"
        header?.contentDescription = "${getString(R.string.workspace_float_drag)}. $description"
        header?.tooltipText = description
    }

    private fun editor(open: Boolean, focus: Boolean = true) {
        if (destroyed) return
        if (open && reviewPending) { expand(); return }
        val requirementView = requirement ?: return
        if (open && !firstFrameReady) {
            deferredEditor = true
            deferredEditorFocus = deferredEditorFocus || focus
            return
        }
        if (open && !editorOpen) layout?.let { compactX = it.x; compactY = it.y }
        if (!open && editorOpen) restoreCompactPosition = true
        editorOpen = open
        requirementView.visibility = if (open) View.VISIBLE else View.GONE
        editorLabels()
        if (!open) { requirementView.clearFocus(); editing(false) }
        clamp()
        if (open && focus) {
            editing(true)
            val token = epoch
            requirementView.post {
                if (!destroyed && token == epoch && editorOpen) requirementView.focusInput()
            }
        } else body?.smoothScrollTo(0, 0)
    }

    private fun drag(view: View, event: MotionEvent): Boolean {
        val params = layout ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragX = event.rawX
            dragY = event.rawY
            startX = params.x
            startY = params.y
            moved = false
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.rawX - dragX
            val dy = event.rawY - dragY
            if (Math.hypot(dx.toDouble(), dy.toDouble()) > ViewConfiguration.get(windowContext).scaledTouchSlop) moved = true
            if (moved) {
                params.x = startX + Math.round(dx)
                params.y = startY + Math.round(dy)
                clamp()
                if (attached) manager?.updateViewLayout(root, params)
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!moved) view.performClick()
            return true
        }
        return event.actionMasked == MotionEvent.ACTION_CANCEL
    }

    private fun editing(value: Boolean) {
        val params = layout ?: return
        if (editing == value) return
        editing = value
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        if (value) params.flags = params.flags and flags.inv()
        else {
            root?.let { windowContext.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
            params.flags = params.flags or flags
        }
        if (attached) manager?.updateViewLayout(root, params)
        clamp()
        if (value) {
            val token = epoch
            main.postDelayed({
                val focused = root?.findFocus()
                if (!destroyed && token == epoch && attached && editing && focused != null)
                    windowContext.getSystemService(InputMethodManager::class.java).showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
    }

    private fun refresh() {
        val origin = lease ?: return
        if (destroyed || root == null || polling) return
        polling = true
        val token = epoch
        NativeClient.io.execute {
            var current: JSONObject? = null
            var blocked = true
            var catalog: JSONObject? = null
            var checkedAt = 0L
            try {
                catalog = WorkspaceCommands.call(this, "workspaces/list", JSONObject().put("threadId", origin.getString("threadId")), Process.myUid())
                checkedAt = SystemClock.elapsedRealtime()
                val entries = ChatWorkspacePreviewProtocol.select(catalog, origin.getString("threadId"))
                for (index in 0 until entries.length()) {
                    if (WorkspaceUi.same(origin, entries.optJSONObject(index))) current = entries.getJSONObject(index)
                }
                val state = NativeClient.state(origin.getString("threadId"))
                val requests = state.optJSONArray("requests")
                blocked = state.optBoolean("waitingForUser") || requests != null && requests.length() > 0
            } catch (_: Exception) { current = null }
            val latest = current
            val needsInput = blocked
            val verifiedCatalog = catalog
            val verifiedAt = checkedAt
            main.post {
                val windowRoot = root
                if (destroyed || token != epoch || windowRoot == null) return@post
                polling = false
                if (needsInput || latest == null) {
                    preview?.stop()
                    suppressed = true
                    editing(false)
                    windowRoot.visibility = View.GONE
                } else {
                    admittedThread = latest.getString("threadId")
                    if (suppressed) { windowRoot.visibility = View.VISIBLE; suppressed = false; clamp() }
                    title.text = getString(R.string.workspace_float_app, appName(latest))
                    ChatDesign.text(title, ChatDesign.Role.META_MEDIUM)
                    val pkg = latest.optString("packageName")
                    if (pkg != currentPackage) {
                        currentPackage = pkg
                        try { appIcon.setImageDrawable(packageManager.getApplicationIcon(pkg)) }
                        catch (_: Exception) { appIcon.setImageResource(R.drawable.ic_tako_mark) }
                    }
                    lastStateLabel = ChatWorkspacePreview.compactState(latest)
                    reviewPending = WorkspaceUi.reviewPending(latest)
                    if (reviewPending) {
                        deferredEditor = false
                        deferredEditorFocus = false
                        if (editorOpen) editor(false)
                    }
                    renderHeaderState()
                    stateDot.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color(ChatWorkspacePreview.stateColor(latest)))
                    }
                    preview?.acceptFloatingCatalog(origin.optString("threadId"), verifiedCatalog, verifiedAt)
                }
                main.postDelayed(poll, 1000)
            }
        }
    }

    private fun appName(screen: JSONObject): String {
        val pkg = screen.optString("packageName")
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (_: Exception) { screen.optString("appLabel", getString(R.string.chat_workspace_app)) }
    }

    private fun expand() {
        val origin = lease ?: return
        if (destroyed || suppressed || !firstFrameReady) return
        val intent = Intent(this, MirrorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("threadId", origin.optString("threadId"))
            .putExtra("screenKey", origin.optString("screenKey"))
            .putExtra("generation", origin.optString("generation"))
        startActivity(intent)
        stopSelf()
    }

    private fun openChat() {
        val thread = admittedThread ?: return
        if (destroyed || thread != lease?.optString("threadId")) return
        if (AgentFrontendLauncher.openThread(this, thread)) stopSelf()
        else Toast.makeText(this, R.string.workspace_unavailable_copy, Toast.LENGTH_LONG).show()
    }

    private fun removeWindow() {
        ++epoch
        polling = false
        main.removeCallbacks(poll)
        preview?.stop()
        requirement?.stop()
        if (attached) {
            editing(false)
            try { manager?.removeViewImmediate(root) } catch (_: RuntimeException) {}
        }
        attached = false
        root = null
        header = null
        footer = null
        body = null
        preview = null
        requirement = null
        layout = null
        manager = null
        currentPackage = ""
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (lease == null || destroyed) return
        removeWindow()
        positioned = false
        suppressed = false
        try { build(); refresh() }
        catch (_: Exception) { stopSelf() }
    }

    override fun onDestroy() {
        destroyed = true
        if (launchId != null && launchId == pendingId) {
            pendingId = null
            pendingAttached = null
        }
        removeWindow()
        super.onDestroy()
    }
}
