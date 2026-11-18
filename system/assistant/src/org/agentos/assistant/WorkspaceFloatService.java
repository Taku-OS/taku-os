package org.agentos.assistant;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** One movable, view-only window. All execution and input authority stays in TaskBroker. */
public final class WorkspaceFloatService extends Service {
    private static String pendingId;
    private static Runnable pendingAttached;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context windowContext;
    private WindowManager manager;
    private WindowManager.LayoutParams layout;
    private FrameLayout root;
    private LinearLayout card;
    private LinearLayout header;
    private LinearLayout footer;
    private ChatWorkspacePreview preview;
    private WorkspaceRequirementView requirement;
    private TextView title, status;
    private ImageView appIcon;
    private View stateDot;
    private Button primary;
    private ImageButton secondary;
    private boolean editorOpen;
    private boolean firstFrameReady, deferredEditor, deferredEditorFocus;
    private int compactX, compactY;
    private boolean restoreCompactPosition;
    private String currentPackage = "";
    private int lastStateLabel = R.string.chat_workspace_connecting;
    private ScrollView body;
    private JSONObject lease;
    private String launchId;
    private boolean attached, polling, editing, suppressed, destroyed, positioned;
    private int epoch;
    private float dragX, dragY;
    private int startX, startY;
    private boolean moved;
    private final Runnable poll = this::refresh;

    public static boolean show(Context context, JSONObject lease) {
        return show(context, lease, null);
    }

    public static boolean show(Context context, JSONObject lease, Runnable onAttached) {
        if (lease == null || !AgentRepository.validId(lease.optString("threadId"))) return false;
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.workspace_float_permission, Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            final String id = java.util.UUID.randomUUID().toString();
            pendingId = id; pendingAttached = onAttached;
            Intent intent = new Intent(context, WorkspaceFloatService.class);
            intent.putExtra("launchId", id);
            for (String key : new String[]{"threadId", "screenKey", "generation"})
                intent.putExtra(key, lease.getString(key));
            if (context.startService(intent) == null) { pendingId = null; pendingAttached = null; return false; }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (id.equals(pendingId)) {
                    pendingId = null; pendingAttached = null;
                    hide(context.getApplicationContext());
                    Toast.makeText(context.getApplicationContext(), R.string.workspace_float_unavailable, Toast.LENGTH_SHORT).show();
                }
            }, 8000);
            return true;
        } catch (Exception unavailable) {
            pendingId = null; pendingAttached = null;
            Toast.makeText(context, R.string.workspace_float_unavailable, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static void hide(Context context) {
        context.stopService(new Intent(context, WorkspaceFloatService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        // Standard adb/DUMP diagnostics; no task text, credentials or frame bytes.
        writer.println("attached=" + attached + " suppressed=" + suppressed + " editor=" + editorOpen
                + " firstFrame=" + firstFrameReady + " epoch=" + epoch);
        writer.println(preview == null ? "preview=none" : preview.diagnostics());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null) throw new IllegalArgumentException();
            launchId = intent.getStringExtra("launchId");
            JSONObject next = new JSONObject();
            for (String key : new String[]{"threadId", "screenKey", "generation"})
                next.put(key, intent.getStringExtra(key));
            if (!AgentRepository.validId(next.optString("threadId"))
                    || !next.optString("screenKey").matches("task-[a-f0-9]{20}")
                    || !next.optString("generation").matches("[a-f0-9-]{36}")) throw new IllegalArgumentException();
            if (!WorkspaceUi.same(next, lease) || root == null) {
                removeWindow(); lease = next; positioned = false; build();
            } else {
                // Each new presentation handoff must acknowledge a fresh validated
                // frame, including a repeated show while its editor is open.
                firstFrameReady = false;
                deferredEditor |= editorOpen;
                editorOpen = false; requirement.setVisibility(View.GONE);
                editing(false); editorLabels(); clamp();
            }
            main.removeCallbacks(poll); refresh();
        } catch (Exception unavailable) {
            Toast.makeText(this, R.string.workspace_float_unavailable, Toast.LENGTH_SHORT).show(); stopSelf();
        }
        return START_NOT_STICKY; // Never resurrect a screen after death, reboot or revoked ownership.
    }

    private int dp(float value) { return Math.round(value * windowContext.getResources().getDisplayMetrics().density); }
    private int color(int resource) { return windowContext.getColor(resource); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(windowContext); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, ChatDesign.Role role) {
        TextView view = new TextView(windowContext); view.setText(value);
        view.setTextColor(color(R.color.taku_text_inverse)); ChatDesign.text(view, role); return view;
    }

    private void build() {
        firstFrameReady = false; deferredEditor = false; deferredEditorFocus = false;
        lastStateLabel = R.string.chat_workspace_connecting;
        final int windowEpoch = epoch;
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        manager = windowContext.getSystemService(WindowManager.class);
        root = new FrameLayout(windowContext); root.setVisibility(View.GONE); suppressed = true;
        root.setClipChildren(false); root.setClipToPadding(false);
        card = column(); card.setPadding(dp(8), 0, dp(8), dp(4));
        GradientDrawable shell = new GradientDrawable(); shell.setColor(color(R.color.workspace_viewer_background)); shell.setCornerRadius(dp(16));
        card.setBackground(shell); card.setElevation(dp(2)); card.setClipToOutline(true);
        root.addView(card, new FrameLayout.LayoutParams(-1, -1));
        header = new LinearLayout(windowContext); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48)); header.setPadding(0, 0, 0, 0);
        appIcon = new ImageView(windowContext); appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams appSize = new LinearLayout.LayoutParams(dp(20), dp(20)); appSize.setMarginEnd(dp(6)); header.addView(appIcon, appSize);
        title = text(getString(R.string.workspace_float_title), ChatDesign.Role.META_MEDIUM);
        title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        stateDot = new View(windowContext); LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(dp(6),dp(6));
        dotSize.setMarginStart(dp(4)); dotSize.setMarginEnd(dp(4)); header.addView(stateDot,dotSize);
        status = text(getString(R.string.chat_workspace_connecting), ChatDesign.Role.META_MEDIUM);
        status.setTextColor(color(R.color.workspace_viewer_secondary)); status.setMaxLines(1);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END); header.addView(status,new LinearLayout.LayoutParams(-2,-2));
        header.setContentDescription(getString(R.string.workspace_float_drag)); header.setOnTouchListener(this::drag);
        ImageButton close = icon(R.drawable.ic_taku_close, R.string.workspace_float_close, this::stopSelf);
        // PRD compact window mapping: a quieter 16 dp glyph and 28 dp visible
        // plate, with the native 48 dp target retained. Not a new DS token.
        GradientDrawable closePlate = new GradientDrawable();
        closePlate.setColor(color(R.color.workspace_viewer_control)); closePlate.setCornerRadius(dp(6));
        close.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24FFFFFF),
                new android.graphics.drawable.InsetDrawable(closePlate, dp(10)), null));
        // setBackground can adopt the inset drawable's padding; set glyph padding last.
        close.setPadding(dp(16), dp(16), dp(16), dp(16));
        header.addView(close);
        card.addView(header);
        body = new ScrollView(windowContext); body.setFillViewport(false);
        LinearLayout contents = column(); body.addView(contents); card.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        DrawerTheme theme = new DrawerTheme(windowContext, false);
        preview = new ChatWorkspacePreview(windowContext, theme, value -> expand());
        preview.setOnFrameReady(() -> {
            if (destroyed || suppressed || !attached || windowEpoch != epoch) return;
            firstFrameReady = true;
            if (primary != null) primary.setEnabled(true);
            if (launchId != null && launchId.equals(pendingId)) {
                Runnable callback = pendingAttached; pendingId = null; pendingAttached = null;
                if (callback != null) callback.run();
            }
            if (deferredEditor) {
                final boolean focus = deferredEditorFocus;
                deferredEditor = false; deferredEditorFocus = false;
                main.post(() -> { if (!destroyed && windowEpoch == epoch && firstFrameReady) editor(true, focus); });
            }
        });
        preview.floatingLease(lease); contents.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        requirement = new WorkspaceRequirementView(windowContext, lease);
        requirement.setOnEditingChanged(this::editing);
        requirement.setOnAttentionRequired(() -> main.post(() -> {
            if (!destroyed && root != null && layout != null && !editorOpen) editor(true, false);
        }));
        try { deferredEditor = WorkspaceExecutionRequest.unresolved(new JSONObject(getSharedPreferences("workspace-interactions",0)
                .getString(WorkspaceExecutionRequest.identity(lease)+".addRequirement","{}"))); }
        catch (Exception ignored) { deferredEditor = false; }
        editorOpen = false;
        requirement.setVisibility(editorOpen ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, -2); inputParams.topMargin = dp(8);
        contents.addView(requirement, inputParams);
        requirement.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (editing && b - t > ob - ot && body != null)
                body.post(() -> { if (body != null && editing) body.smoothScrollTo(0, Math.max(0, requirement.getBottom() - body.getHeight())); });
        });
        footer = new LinearLayout(windowContext); footer.setGravity(Gravity.CENTER);
        primary = new Button(windowContext); primary.setAllCaps(false);
        primary.setIncludeFontPadding(false);
        ChatDesign.text(primary, ChatDesign.Role.META_MEDIUM); primary.setMinHeight(dp(48)); primary.setMinimumHeight(dp(48));
        primary.setMaxLines(1); primary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        primary.setTextColor(color(R.color.taku_text_inverse)); primary.setPadding(dp(8),dp(4),dp(8),dp(4));
        primary.setBackground(compactActionBackground());
        primary.setOnClickListener(v -> { if(editorOpen) editor(false); else expand(); }); footer.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
        secondary = icon(R.drawable.ic_taku_chat,R.string.workspace_float_chat,this::openChat);
        footer.addView(secondary, new LinearLayout.LayoutParams(dp(48),dp(48)));
        contents.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        editorLabels();
        layout = new WindowManager.LayoutParams(Math.min(dp(184), safeArea().width()), dp(360),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.setFitInsetsTypes(0); layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        layout.setTitle("Taku workspace preview");
        root.setOnApplyWindowInsetsListener((v, insets) -> { clamp(); return insets; });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> clamp());
        clamp(); manager.addView(root, layout); attached = true;
        // Preview is only started after both native state and the exact lease are checked.
    }

    private ImageButton icon(int drawable, int description, Runnable action) {
        ImageButton view = new ImageButton(windowContext); view.setContentDescription(getString(description));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageDrawable(ChatDesign.icon(windowContext, drawable, color(R.color.taku_text_inverse)));
        view.setBackground(compactActionBackground()); view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); view.setOnClickListener(v -> action.run()); return view;
    }

    private android.graphics.drawable.Drawable compactActionBackground() {
        GradientDrawable mask = new GradientDrawable(); mask.setColor(android.graphics.Color.WHITE); mask.setCornerRadius(dp(6));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24FFFFFF), null, mask);
    }

    private android.graphics.drawable.Drawable expandBackground() {
        GradientDrawable face = new GradientDrawable(); face.setColor(color(R.color.taku_surface)); face.setCornerRadius(dp(6));
        // A compact 32 dp visible face inside the full 48 dp tap target.
        android.graphics.drawable.Drawable inset = new android.graphics.drawable.InsetDrawable(face, 0, dp(8), 0, dp(8));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14000000), inset, null);
    }

    private Rect safeArea() {
        android.view.WindowMetrics metrics = manager.getCurrentWindowMetrics(); Rect bounds = new Rect(metrics.getBounds());
        Insets insets = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        bounds.left += insets.left + dp(8); bounds.top += insets.top + dp(8);
        bounds.right -= insets.right + dp(8); bounds.bottom -= insets.bottom + dp(8);
        return bounds;
    }

    private void clamp() {
        if (root == null || layout == null || manager == null) return;
        Rect area = safeArea();
        boolean enlarged = windowContext.getResources().getConfiguration().fontScale > 1.3f;
        // Native 48 dp targets establish the minimum; PRD proportions guide ordinary view mode.
        int preferredWidth = editorOpen ? dp(enlarged ? 328 : 288)
                : Math.max(dp(enlarged ? 252 : 176),Math.min(dp(enlarged ? 280 : 184),Math.round((area.width()+dp(16))*.42f)));
        int width = Math.max(1, Math.min(preferredWidth, area.width()));
        status.setMaxWidth(Math.round(width * .32f));
        int headerHeight = Math.max(dp(48),header == null ? 0 : header.getMeasuredHeight());
        int editorHeight = editorOpen ? Math.max(dp(48),requirement.getMeasuredHeight())+dp(8) : 0;
        int footerHeight = Math.max(dp(48),footer==null?0:footer.getMeasuredHeight());
        // A read-only viewport, not a shrunken portrait phone. The full viewer
        // preserves the complete frame; this compact card crops without stretching.
        int frameHeight = editorOpen ? 0 : Math.max(dp(96), Math.min(dp(200),
                Math.min(Math.round((width-dp(16))*1.25f), area.height()-headerHeight-footerHeight-dp(4))));
        if(preview != null && !editorOpen) preview.setFloatingPreviewLimit(frameHeight);
        int height = Math.max(1, Math.min(headerHeight+frameHeight+editorHeight+footerHeight+dp(4), area.height()));
        if(requirement != null) requirement.setAvailableHeight(height);
        int x = positioned ? layout.x : area.left;
        int y = positioned ? layout.y : Math.max(area.top, area.bottom - height - dp(64));
        if (restoreCompactPosition && !editorOpen && manager.getCurrentWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.ime()).bottom == 0) {
            x = compactX; y = compactY; restoreCompactPosition = false;
        }
        x = Math.max(area.left, Math.min(x, area.right - width)); y = Math.max(area.top, Math.min(y, area.bottom - height));
        boolean changed = x != layout.x || y != layout.y || width != layout.width || height != layout.height;
        layout.x = x; layout.y = y; layout.width = width; layout.height = height; positioned = true;
        if (attached && changed) try { manager.updateViewLayout(root, layout); } catch (RuntimeException unavailable) { stopSelf(); }
    }

    private void editorLabels() {
        renderHeaderState();
        primary.setText(editorOpen ? R.string.workspace_float_back_preview : R.string.workspace_float_expand); ChatDesign.text(primary,ChatDesign.Role.META_MEDIUM);
        primary.setTextColor(color(editorOpen ? R.color.taku_text_inverse : R.color.taku_text_primary));
        primary.setBackground(editorOpen ? compactActionBackground() : expandBackground());
        primary.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.graphics.drawable.Drawable arrow = ChatDesign.icon(windowContext,
                editorOpen ? R.drawable.ic_taku_arrow_left : R.drawable.ic_taku_arrow_up_right, color(R.color.taku_text_inverse));
        arrow.setBounds(0, 0, dp(16), dp(16)); primary.setCompoundDrawablePadding(dp(6));
        primary.setCompoundDrawablesRelative(editorOpen ? arrow : null, null, null, null);
        LinearLayout.LayoutParams actionSize = (LinearLayout.LayoutParams) primary.getLayoutParams();
        actionSize.width = 0; actionSize.weight = 1; primary.setLayoutParams(actionSize);
        primary.setEnabled(editorOpen || firstFrameReady);
        secondary.setVisibility(editorOpen ? View.VISIBLE : View.GONE);
        preview.setVisibility(editorOpen ? View.GONE : View.VISIBLE);
    }

    private void renderHeaderState() {
        status.setText(editorOpen ? R.string.workspace_float_add_note : lastStateLabel);
        ChatDesign.text(status, ChatDesign.Role.META_MEDIUM);
        // Ordinary Ready needs no second line. Attention states remain text,
        // while the complete state is always available to accessibility.
        status.setVisibility(editorOpen || lastStateLabel != R.string.workspace_float_ready ? View.VISIBLE : View.GONE);
        stateDot.setVisibility(lastStateLabel == R.string.workspace_float_ready ? View.GONE : View.VISIBLE);
        String description = title.getText() + ". " + status.getText();
        header.setContentDescription(getString(R.string.workspace_float_drag) + ". " + description);
        header.setTooltipText(description);
    }
    private void editor(boolean open) {
        editor(open, true);
    }
    private void editor(boolean open, boolean focus) {
        if (open && !firstFrameReady) { deferredEditor = true; deferredEditorFocus |= focus; return; }
        if (open && !editorOpen && layout != null) { compactX = layout.x; compactY = layout.y; }
        if (!open && editorOpen) restoreCompactPosition = true;
        editorOpen = open;
        requirement.setVisibility(open ? View.VISIBLE : View.GONE); editorLabels();
        if(!open) { requirement.clearFocus(); editing(false); }
        clamp();
        if(open && focus) { editing(true); requirement.post(()->{ if(editorOpen && requirement!=null) requirement.focusInput(); }); }
        else body.smoothScrollTo(0,0);
    }

    private boolean drag(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragX = event.getRawX(); dragY = event.getRawY(); startX = layout.x; startY = layout.y; moved = false; return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - dragX, dy = event.getRawY() - dragY;
            if (Math.hypot(dx, dy) > ViewConfiguration.get(windowContext).getScaledTouchSlop()) moved = true;
            if (moved) { layout.x = startX + Math.round(dx); layout.y = startY + Math.round(dy); clamp();
                if (attached) manager.updateViewLayout(root, layout); }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) { if (!moved) view.performClick(); return true; }
        return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
    }

    private void editing(boolean value) {
        if (layout == null || editing == value) return;
        editing = value;
        if (value) layout.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        else {
            if (root != null) windowContext.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
            layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
        if (attached) manager.updateViewLayout(root, layout); clamp();
        if (value) main.postDelayed(() -> {
            if (!destroyed && attached && editing && root != null && root.findFocus() != null)
                windowContext.getSystemService(InputMethodManager.class).showSoftInput(root.findFocus(), InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    private void refresh() {
        if (destroyed || root == null || polling) return;
        polling = true; final int token = epoch; final JSONObject origin = lease;
        NativeClient.io.execute(() -> {
            JSONObject current = null; boolean blocked = true;
            JSONObject catalog = null; long checkedAt = 0;
            try {
                catalog = NativeClient.exchange(WorkspaceUi.request("list", null));
                checkedAt = android.os.SystemClock.elapsedRealtime();
                JSONArray entries = ChatWorkspacePreviewProtocol.select(
                        catalog, origin.getString("threadId"));
                for (int i = 0; i < entries.length(); i++) if (WorkspaceUi.same(origin, entries.optJSONObject(i))) current = entries.getJSONObject(i);
                JSONObject state = NativeClient.state(origin.getString("threadId"));
                JSONArray requests = state.optJSONArray("requests");
                blocked = state.optBoolean("waitingForUser") || requests != null && requests.length() > 0;
            } catch (Exception unavailable) { current = null; }
            final JSONObject latest = current; final boolean needsInput = blocked;
            final JSONObject verifiedCatalog = catalog; final long verifiedAt = checkedAt;
            main.post(() -> {
                if (destroyed || token != epoch || root == null) return;
                polling = false;
                if (needsInput || latest == null) {
                    preview.stop();
                    // Existing request-specific Surfaces retain priority. No duplicate approval or automatic navigation.
                    suppressed = true; editing(false); root.setVisibility(View.GONE);
                } else {
                    if (suppressed) { root.setVisibility(View.VISIBLE); suppressed = false; clamp(); }
                    title.setText(getString(R.string.workspace_float_app, appName(latest))); ChatDesign.text(title, ChatDesign.Role.META_MEDIUM);
                    String pkg=latest.optString("packageName");
                    if(!pkg.equals(currentPackage)) { currentPackage=pkg;
                        try { appIcon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); }
                        catch(Exception missing) { appIcon.setImageResource(R.drawable.ic_tako_mark); }
                    }
                    lastStateLabel = ChatWorkspacePreview.compactState(latest);
                    renderHeaderState();
                    GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(color(ChatWorkspacePreview.stateColor(latest)));stateDot.setBackground(dot);
                    preview.acceptFloatingCatalog(origin.optString("threadId"), verifiedCatalog, verifiedAt);
                }
                main.postDelayed(poll, 1000);
            });
        });
    }

    private String appName(JSONObject screen) {
        String pkg = screen.optString("packageName");
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception unavailable) { return screen.optString("appLabel", getString(R.string.chat_workspace_app)); }
    }

    private void expand() {
        if (suppressed || lease == null) return;
        Intent intent = new Intent(this, MirrorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("returnConversationId", lease.optString("threadId"));
        for (String key : new String[]{"threadId", "screenKey", "generation"}) intent.putExtra(key, lease.optString(key));
        startActivity(intent); stopSelf();
    }

    private void openChat() {
        if (lease == null) return;
        startActivity(new Intent(this, PanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("conversationId", lease.optString("threadId"))); stopSelf();
    }

    private void removeWindow() {
        ++epoch; polling = false; main.removeCallbacks(poll);
        if (preview != null) preview.stop(); if (requirement != null) requirement.stop();
        if (attached) { editing(false); try { manager.removeViewImmediate(root); } catch (RuntimeException ignored) {} }
        attached = false; root = null; card=null; header=null; footer=null; body = null; preview = null; requirement = null; layout = null; currentPackage="";
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (lease == null) return;
        removeWindow(); positioned = false; suppressed = false;
        try { build(); refresh(); } catch (Exception unavailable) { stopSelf(); }
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (launchId != null && launchId.equals(pendingId)) { pendingId = null; pendingAttached = null; }
        removeWindow(); super.onDestroy();
    }
}
