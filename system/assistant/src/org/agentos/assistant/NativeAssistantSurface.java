package org.agentos.assistant;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;

/** Shared native controls/capture surface; independent of which package owns the voice role. */
final class NativeAssistantSurface {
    interface Host {
        Window window();
        void hide();
        void launch(Intent intent);
        default void setUiEnabled(boolean enabled) {}
    }
    private final Host host;
    private void hide() { host.hide(); }
    private static final java.util.Set<NativeAssistantSurface> surfaces =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    static final File ROOT = new File("/data/user/0/org.agentos.assistant/files/runtime");
    static final File TASKS = new File(ROOT, "tasks");

    private final Context context;
    private AgentDrawerView drawer;
    private AgentEdgeWindow edgeLight;
    private String foreground = "Current app";
    private Bitmap invocationScreenshot;
    private String invocationPageText = "";
    private boolean assistReceived;
    private FrameLayout content;
    private JevHintView jevHint;
    private AgentRepository jevRepository;
    private boolean jevTest, jevStarted, visible, testCaptureClosed;
    private boolean manualScreen;
    private int invocationGeneration;

    public NativeAssistantSurface(Context context, Host host) {
        this.host = host;
        this.context = context;
        surfaces.add(this);
    }

    /** Activities can remain in the task stack after HOME; they no longer cover the screen. */
    void setHostVisible(boolean value) { visible = value; }

    /** Called on the main thread before requesting a new system capture. */
    static boolean prepareJevTestCapture() {
        for (NativeAssistantSurface session : surfaces) if (session.visible) return false;
        for (NativeAssistantSurface session : surfaces) {
            session.invocationScreenshot = null;
            session.invocationPageText = "";
            session.assistReceived = false;
            session.testCaptureClosed = false;
        }
        return true;
    }

    public void onPrepareShow(Bundle arguments, int flags) {
        String trigger = arguments == null ? "" : arguments.getString(AgentFrontendRoute.EXTRA_TRIGGER, "");
        host.setUiEnabled(!"nav_handle_long_press".equals(trigger) && !"nav_handle_double_tap".equals(trigger));
        // A new normal invocation may receive its screenshot before onShow.
        if (arguments == null || !arguments.containsKey("jevTestToken")) {
            invocationScreenshot = null;
            invocationPageText = "";
            assistReceived = false;
            testCaptureClosed = false;
        }
    }

    public View onCreateContentView() {
        drawer =
                new AgentDrawerView(
                        context,
                        new AgentDrawerView.Host() {
                            public void sensitive(boolean value) {
                                Window window = host.window();
                                if (value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                            }

                            public Bitmap screenshot() {
                                return invocationScreenshot;
                            }
                            public String pageText() { return invocationPageText; }
                            public void suggestScreen() { showManualSuggestion(); }

                            public void dismiss() {
                                hide();
                            }

                            public String foreground() {
                                return foreground;
                            }

                            public void openMirror() {
                                context.startActivity(
                                        new Intent(context, MirrorActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                hide();
                            }
                        });
        Window window = host.window();
        edgeLight = new AgentEdgeWindow(context);
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.setBlurBehindRadius(20);
        window.setAttributes(attributes);
        content = new FrameLayout(context);
        content.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        return content;
    }

    public void onShow(Bundle arguments, int flags) {
        if (AgentFrontendLauncher.openGesture(context, arguments, host::launch)) {
            hide();
            return;
        }
        // Other system assist calls (assist key, ordinary voice) open the same chat as
        // the launcher icon. Native-only calls (advanced controls, settings pages, Jev tests,
        // screenshot context, unsent native composer state) keep the native session below.
        if (AgentFrontendLauncher.openConversation(context, arguments)) {
            hide();
            return;
        }
        visible = true;
        host.window().setLayout(-1, -1);
        host.window()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        // Android may deliver assist capture before onShow. onHide clears the prior invocation.
        updateForeground();
        if (arguments != null && arguments.containsKey("captureForeground"))
            foreground = arguments.getString("captureForeground", foreground);
        invocationGeneration++;
        jevTest = JevTestTrigger.consume(arguments);
        manualScreen = jevTest && arguments.getBoolean("manualScreen",false);
        jevStarted = false;
        if (jevHint != null) content.removeView(jevHint);
        jevHint = null;
        drawer.setVisibility(jevTest ? View.GONE : View.VISIBLE);
        if (jevTest) {
            host.window().clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            jevHint = new JevHintView(context, this::hide,manualScreen);
            content.addView(jevHint, new FrameLayout.LayoutParams(-1,-1));
            startJevTest();
            final int generation = invocationGeneration;
            content.postDelayed(() -> {
                if (generation == invocationGeneration && jevTest && !jevStarted) {
                    if (invocationScreenshot != null) { assistReceived = true; startJevTest(); return; }
                    jevStarted = true;
                    testCaptureClosed = true;
                    invocationScreenshot = null;
                    jevHint.error("The system did not return a main-screen screenshot; no model was called. Check assistant screenshot permissions and retry.");
                }
            }, 5000);
        } else {
            String conversation = arguments == null ? null : arguments.getString("conversationId");
            if (arguments != null && arguments.getBoolean(AgentFrontendRoute.NATIVE_CONTROLS_ONLY, false))
                drawer.showNativeControls(conversation);
            else drawer.show(conversation);
            if (arguments != null && "modelConnection".equals(arguments.getString("page")))
                drawer.modelConnections();
            if (arguments != null && "account".equals(arguments.getString("page")))
                drawer.accountPage();
            if (arguments != null && "aiUsage".equals(arguments.getString("page")))
                drawer.aiUsage();
            if (arguments != null && "jevConnection".equals(arguments.getString("page")))
                drawer.jevConnections();
            host.window().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            // Ordinary Chat uses the DS sheet without a screen-wide listening decoration.
            // Speech recognition and screenshot/Jev entry points remain independent.
            edgeLight.hide();
        }
    }

    public void onHandleAssist() {
        if (testCaptureClosed || (jevTest && jevStarted)) return;
        assistReceived = true;
        invocationPageText = ""; // Screen suggestions only use the original image.
        if (jevTest) startJevTest();
    }

    public void onHandleScreenshot(Bitmap image) {
        if (testCaptureClosed || (jevTest && jevStarted)) return;
        invocationScreenshot = image;
        if (jevTest) startJevTest();
    }

    private void startJevTest() {
        if (!jevTest || jevStarted || invocationScreenshot == null || jevHint == null) return;
        jevStarted = true;
        final int generation = invocationGeneration;
        final Bitmap capture;
        try { capture = JevScreenDecision.capture(invocationScreenshot); }
        catch (Exception e) { jevHint.error("This screenshot is unavailable; no model was called."); return; }
        if (jevRepository == null) jevRepository = new AgentRepository(context);
        AgentRepository.Callback<JevScreenDecision> callback=new AgentRepository.Callback<JevScreenDecision>() {
                    public void ready(JevScreenDecision decision) {
                        if (generation != invocationGeneration || !jevTest) return;
                        // Safe receipt contains no screenshot text, credential, or raw response.
                        android.util.Log.i("TakuJevDecision", decision.receipt.toString());
                        jevHint.result(decision, () -> {
                            if (decision.expired()) { jevHint.error("Suggestion expired. Trigger it again."); return; }
                            try { BackgroundScreenTasks.enqueue(context,decision);hide(); }
                            catch(Exception failed) { jevHint.error(failed.getMessage()==null?"Background task did not start. Retry":failed.getMessage()); }
                        });
                    }
                    public void failed(String message) {
                        if (generation == invocationGeneration && jevTest) jevHint.error(message);
                    }
                };
        if(manualScreen) jevRepository.analyzeScreen(capture,foreground,android.os.SystemClock.elapsedRealtime(),callback);
        else jevRepository.analyzeJev(capture,"","",foreground,android.os.SystemClock.elapsedRealtime(),callback);
    }

    private void showManualSuggestion() {
        if(!visible || jevStarted) return;
        if(invocationScreenshot==null) {
            android.widget.Toast.makeText(context,"No screen from before invocation was captured. Enable screenshots, return to the target app, and long-press the power button again",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        invocationGeneration++;
        manualScreen=true; jevTest=true; jevStarted=false; testCaptureClosed=false;
        drawer.hide(); drawer.setVisibility(View.GONE); edgeLight.hide();
        host.window().clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        if(jevHint!=null)content.removeView(jevHint);
        jevHint=new JevHintView(context,this::hide,true);
        content.addView(jevHint,new FrameLayout.LayoutParams(-1,-1));
        startJevTest();
    }

    private void updateForeground() {
        foreground = "Current app";
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(20)) {
                int display = (Integer) task.getClass().getField("displayId").get(task);
                if (display == 0
                        && task.topActivity != null
                        && !task.topActivity.getPackageName().equals(context.getPackageName())) {
                    foreground = task.topActivity.flattenToShortString();
                    return;
                }
            }
        } catch (Exception ignored) {
            // Unknown is an honest fallback; never substitute a fabricated source app.
        }
    }

    public void onBackPressed() {
        if (jevTest) { hide(); return; }
        if (!drawer.back()) hide();
    }

    public void onHide() {
        invocationGeneration++;
        visible = false;
        testCaptureClosed = true;
        jevTest = false;
        manualScreen = false;
        if (jevHint != null && content != null) content.removeView(jevHint);
        jevHint = null;
        if (edgeLight != null) edgeLight.hide();
        if (drawer != null) drawer.hide();
        invocationScreenshot = null;
        invocationPageText = ""; assistReceived = false;
    }

    public void onDestroy() {
        surfaces.remove(this);
        if (jevRepository != null) jevRepository.close();
        if (edgeLight != null) edgeLight.hide();
        if (drawer != null) drawer.destroy();
    }
}
