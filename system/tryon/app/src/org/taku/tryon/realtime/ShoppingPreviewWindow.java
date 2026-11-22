// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.animation.LinearInterpolator;
import android.content.Context;
import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.ViewConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.taku.tryon.ui.Ui;

/**
 * One service-owned overlay and Camera2 Surface for the whole shopping run.
 * Resizing, a 1px transparent camera in pill mode, and capture redaction never
 * detach/reparent the TextureView. No model, key, file or Activity navigation.
 */
final class ShoppingPreviewWindow implements LocalShoppingFrameBridge.Source {
    interface Actions {
        default void presentationReady(ShoppingPreviewWindow window) {}
        default void resultReady() {}
        void cameraReady();
        void failed(String message);
        default void presentationFailed(String code) { failed("The try-on window is unavailable."); }
        void close();
    }
    private static final int INK = Color.rgb(13, 13, 13);
    private final Context context;
    private final Actions actions;
    private final boolean directFrames;
    private final Ui ui;
    private final ShoppingPreviewChrome chrome;
    private final WindowManager windows;
    private final ShoppingPreviewHost host;
    private final Handler main = new Handler(Looper.getMainLooper());
    final FrameLayout root, cameraPane;
    private final FrameLayout content;
    private ValueAnimator motionAnimator;
    private ShoppingPreviewMotion.Frame motionFrame;
    private float visualRadius, backgroundFull;
    private boolean movingPresentation;
    private float motionProgress = 1f;
    final TakuCameraPreview camera;
    final WindowManager.LayoutParams params;
    private final LinearLayout videoControls;
    private final FrameLayout pill, pillVisual, header, bottomHandle;
    private final View backplate, compactMark, workingDots;
    private final TextView title;
    private boolean characterPreview;
    private String characterModelLabel;
    private final Button minimize, stop, pillClose;
    private final ShoppingIslandStyle.Capsule capsule;
    private ShoppingIslandGeometry.Mode mode = ShoppingIslandGeometry.Mode.PREVIEW;
    private final ShoppingPreviewLayout.FloatingFrame floatingFrame = new ShoppingPreviewLayout.FloatingFrame();
    private final ShoppingPreviewLayout.DisplayState displayState = new ShoppingPreviewLayout.DisplayState();
    private boolean geometryRefreshPosted;
    private long presentationAnimation;
    private final ShoppingPreviewMotion.Epoch motionEpoch = new ShoppingPreviewMotion.Epoch();
    private ShoppingIslandGeometry.Layout currentLayout;
    private final TextView status, pillStatus;
    private final Button resize, resizeHandle;
    private final ImageView returned, original;
    private final ShoppingBodyGuide bodyGuide;
    private final DirectPreviewRenderer<Bitmap> directRenderer;
    private volatile long directGeneration;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "shopping-original-preview"); thread.setDaemon(true); return thread;
    });
    private final AtomicReference<OriginalFrame> pendingOriginal = new AtomicReference<>();
    private final AtomicBoolean decoding = new AtomicBoolean();
    volatile boolean collapsed, expanded, captureHidden;
    private boolean attached, attaching, readyReported, generatedFrameShown;
    private final ShoppingPreviewReveal firstReveal = new ShoppingPreviewReveal();
    private boolean automaticReveal;
    private float mediaDownX, mediaDownY;
    private int mediaStartX, mediaStartY;
    private boolean mediaDragged, mediaPressed;
    private long mediaLastTap, interactionEpoch;
    private volatile boolean closed, closing;
    private volatile boolean originalHeld;
    private volatile long originalEpoch;
    private Bitmap displayedOriginal;
    private CallTryOnService.Snapshot snapshot;
    private String cameraDetail = "Opening local camera…";
    private final Runnable compareHold = () -> {
        if (mediaPressed && !mediaDragged && !collapsed && !closed && !closing && !captureHidden) {
            originalEpoch++; originalHeld = true; render(snapshot);
        }
    };
    private final Runnable refreshGeometry = () -> {
        geometryRefreshPosted=false;
        if (!attached || closed || closing || !characterPreview) return;
        var display=display();
        if (!displayState.needsReflow(display,attached,closed,closing,characterPreview)) return;
        if (movingPresentation) cancelPresentation();
        layout(false);
    };
    private final ComponentCallbacks configuration = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration value) {
            if (closed || closing) return;
            interruptAutomaticReveal(); cancelMediaHold(); releaseOriginal(); cancelPresentation();
            if (directRenderer != null) directRenderer.invalidatePresentation();
            layout(false);
        }
        @Override public void onLowMemory() {
            releaseOriginal();
            if (directRenderer != null) directRenderer.invalidatePresentation();
        }
    };

    ShoppingPreviewWindow(Context context, Actions actions) {
        this(context, actions, false);
    }

    ShoppingPreviewWindow(Context context, Actions actions, boolean directFrames) {
        this.context = context; this.actions = actions; this.directFrames = directFrames; ui = new Ui(context);
        windows = context.getSystemService(WindowManager.class);
        root = new PreviewRoot(context);
        // One explicit backplate owns the expanded outline. Keep the root transparent
        // and shadow-free so the old capsule cannot leave a second rounded silhouette.
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        visualRadius);
            }
        });
        root.setClipToOutline(true);
        root.setContentDescription("Live try-on window");
        backplate = new View(context);
        root.addView(backplate, new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(context);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        cameraPane = new FrameLayout(context);
        TakuCameraPreview.Listener cameraListener = (message, showing) -> {
            if (closed || closing) return;
            boolean firstReady = showing && !readyReported;
            if (firstReady) readyReported = true;
            cameraDetail = message; render(snapshot);
            if (firstReady) {
                if (!ShoppingPreviewReveal.showCameraImmediately(directFrames, characterPreview) && !generatedFrameShown) {
                    showPreparingPill();
                }
                actions.cameraReady();
            }
            else if (!showing && !message.startsWith("Opening")) actions.failed(message);
        };
        camera = directFrames ? new TakuCameraPreview(context, cameraListener, true)
                : new TakuCameraPreview(context, cameraListener);
        cameraPane.addView(camera, new FrameLayout.LayoutParams(-1, -1));
        returned = new ImageView(context); returned.setScaleType(ImageView.ScaleType.FIT_CENTER);
        returned.setBackgroundColor(INK); returned.setVisibility(View.INVISIBLE);
        returned.setContentDescription("Live try-on result");
        if (directFrames) cameraPane.addView(returned, new FrameLayout.LayoutParams(-1, -1));
        directRenderer = directFrames ? new DirectPreviewRenderer<>(ShoppingPreviewWindow::decodeReturnedFrame,
                new DirectPreviewRenderer.Display<Bitmap>() {
                    public void show(Bitmap bitmap) {
                        returned.setImageBitmap(bitmap); returned.setVisibility(View.VISIBLE);
                        boolean first = !generatedFrameShown;
                        generatedFrameShown = true; updateGuide();actions.resultReady();
                        if (first && collapsed && !movingPresentation) layout(false);
                        revealFirstResult();
                    }
                    public void clear() {
                        returned.setVisibility(View.INVISIBLE); returned.setImageDrawable(null);
                        generatedFrameShown = false; updateGuide();
                    }
                    public void release(Bitmap bitmap) { if (!bitmap.isRecycled()) bitmap.recycle(); }
                }, main::post) : null;
        original = new ImageView(context);
        original.setScaleType(directFrames ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.MATRIX);
        original.setBackgroundColor(INK); original.setVisibility(View.INVISIBLE);
        original.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateOriginalTransform());
        original.setContentDescription("Original camera view for comparison");
        cameraPane.addView(original, new FrameLayout.LayoutParams(-1, -1));
        bodyGuide = new ShoppingBodyGuide(context);
        bodyGuide.setVisibility(View.INVISIBLE);
        cameraPane.addView(bodyGuide, new FrameLayout.LayoutParams(-1, -1));
        content.addView(cameraPane, new FrameLayout.LayoutParams(-1, -1));
        cameraPane.setContentDescription("Try-on video. Drag to move, pinch to resize, hold to compare, double tap to minimize.");
        installIslandGesture(cameraPane, false);

        chrome = new ShoppingPreviewChrome(context);
        header = chrome.header; title = chrome.title; status = chrome.statusChip;
        FrameLayout.LayoutParams statusPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        statusPosition.leftMargin = ui.dp(12); statusPosition.topMargin = ui.dp(12);
        cameraPane.addView(status, statusPosition);
        minimize = chrome.minimize;
        minimize.setOnClickListener(view -> setCollapsed(true));
        stop = chrome.close;
        stop.setOnClickListener(view -> actions.close());
        header.setContentDescription("Drag to move try-on. Double tap to minimize.");
        content.addView(header, new FrameLayout.LayoutParams(-1, ui.dp(48), Gravity.TOP)); installDrag(header);

        videoControls = chrome.toolbar;
        resize = chrome.fullscreen;
        resize.setOnClickListener(view -> setFullscreen(mode != ShoppingIslandGeometry.Mode.FULLSCREEN));
        chrome.shoppingClose.setOnClickListener(view -> { interruptAutomaticReveal(); actions.close(); });
        Button raw = chrome.compare;
        raw.setOnTouchListener((view, event) -> {
            if (closed || closing || captureHidden || collapsed) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                originalEpoch++; originalHeld = true; render(snapshot); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                releaseOriginal(); view.performClick(); return true;
            }
            return true;
        });
        raw.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_SPACE && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_DPAD_CENTER) return false;
            if (closed || closing || captureHidden || collapsed) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && !originalHeld) { originalEpoch++; originalHeld = true; render(snapshot); }
            else if (event.getAction() == KeyEvent.ACTION_UP) releaseOriginal();
            return true;
        });
        raw.setOnFocusChangeListener((view, focused) -> { if (!focused && originalHeld) releaseOriginal(); });
        content.addView(videoControls, new FrameLayout.LayoutParams(-1, ui.dp(48)));

        resizeHandle = chrome.resizeTarget;
        content.addView(resizeHandle, new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        installResize(resizeHandle);
        bottomHandle = chrome.hintRow;
        bottomHandle.setContentDescription("Swipe up to minimize try-on");
        bottomHandle.setOnClickListener(view -> setCollapsed(true));
        content.addView(bottomHandle, new FrameLayout.LayoutParams(-1, ui.dp(24)));
        installIslandGesture(bottomHandle, false);

        pill = new FrameLayout(context);
        pill.setContentDescription("Open try-on window");

        pill.setOnClickListener(view -> setCollapsed(false));
        pillVisual = new FrameLayout(context);
        pill.addView(pillVisual, new FrameLayout.LayoutParams(-1, ui.dp(32), Gravity.CENTER_VERTICAL));
        compactMark = chrome.compactMark();
        FrameLayout.LayoutParams compactMarkPosition = new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER);
        pillVisual.addView(compactMark, compactMarkPosition);
        workingDots=new ShoppingIslandStyle.Dots(context);
        pillVisual.addView(workingDots,new FrameLayout.LayoutParams(ui.dp(16),ui.dp(16),Gravity.CENTER));
        pillStatus = ui.text("Preparing", 12, ShoppingIslandStyle.TEXT, false);
        ShoppingIslandStyle.text(pillStatus, 12); pillStatus.setGravity(Gravity.CENTER);
        pillStatus.setSingleLine(true);pillStatus.setHorizontallyScrolling(false);
        pillStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pillStatus.setPadding(ui.dp(4),ui.dp(6),ui.dp(4),ui.dp(6));
        // The VPN/status lane can leave only 58dp beside the physical lens.
        // Keep the full state at the default scale; large accessibility text may ellipsize.
        pillStatus.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        pillStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pillVisual.addView(pillStatus, new FrameLayout.LayoutParams(ui.dp(66), -1, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        pillClose=chrome.compactClose;
        pillClose.setOnClickListener(view -> { interruptAutomaticReveal(); actions.close(); });
        // The dot node keeps the same stop action while leaving the right lobe to the status.
        pill.addView(pillClose,new FrameLayout.LayoutParams(ui.dp(44),-1,Gravity.LEFT|Gravity.TOP));
        pillStatus.setVisibility(View.GONE);
        root.addView(pill, new FrameLayout.LayoutParams(-1, -1)); pill.setVisibility(View.GONE);
        installIslandGesture(pill, true);
        capsule = new ShoppingIslandStyle.Capsule(context.getResources().getDisplayMetrics().density);

        params = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setTitle("Taku Live Try-On");
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        host = ShoppingPreviewHost.create(context, root);
        root.setOnApplyWindowInsetsListener((view,insets) -> { scheduleGeometryRefresh(); return insets; });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { scheduleGeometryRefresh(); view.requestApplyInsets(); }
            @Override public void onViewDetachedFromWindow(View view) {
                main.removeCallbacks(refreshGeometry); geometryRefreshPosted=false;
            }
        });
        root.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS,
                        characterPreview ? "Stop cosplay" : "Stop try-on"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE,
                        characterPreview ? "Minimize cosplay" : "Minimize try-on"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, "Full screen"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) { interruptAutomaticReveal(); actions.close(); return true; }
                if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) { setCollapsed(true); return true; }
                if (action == AccessibilityNodeInfo.ACTION_EXPAND) { setFullscreen(true); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        context.registerComponentCallbacks(configuration);
        layout(true);
    }

    void show() {
        if (closed || closing || attached || attaching) return;
        attaching = true;
        if (!ShoppingPreviewReveal.showCameraImmediately(directFrames, characterPreview)) params.alpha = 0f;
        host.attach(params, () -> {
            attaching = false;
            if (closed || closing) { host.close(); return; }
            if (attached) return;
            attached = true;
            scheduleGeometryRefresh();
            actions.presentationReady(this); startAttachedCamera();
        }, message -> {
            attaching = false;
            if (!closed && !closing) actions.presentationFailed(message);
        });
    }
    private void startAttachedCamera() {
        if(closed||closing||!attached)return;
        camera.start();
        if (ShoppingPreviewReveal.showCameraImmediately(directFrames, characterPreview)) reveal(); else revealFirstResult();
    }

    /** Post after the actual attach/insets traversal; root insets are window-relative, not screen bounds. */
    private void scheduleGeometryRefresh() {
        if (closed || closing || geometryRefreshPosted) return;
        geometryRefreshPosted=true;
        main.post(refreshGeometry);
    }

    private void showPreparingPill() {
        mode = ShoppingIslandGeometry.Mode.COMPACT; params.alpha = captureHidden ? 0f : 1f; layout(false);
    }

    boolean isAttached() { return attached && !closed && !closing; }
    boolean isCameraOpen() { return !closing && camera.isCameraOpen(); }

    @Override public CallTryOnBridge.Frame latest(long afterSequence) {
        return directFrames && !closed && !closing ? camera.latestDirectFrame(afterSequence) : null;
    }

    /** These bridge callbacks may arrive on the bridge worker; renderer owns UI dispatching. */
    @Override public void begin(long generation) {
        if (!directFrames || closed || closing || !directRenderer.begin(generation))
            throw new IllegalStateException("direct_preview_unavailable");
        directGeneration = generation;
        main.post(() -> { if (directGeneration == generation && !closed) releaseOriginal(); });
    }

    @Override public boolean offer(byte[] jpeg, int width, int height, long generation) {
        return directFrames && !closed && !closing && directRenderer.offer(jpeg, width, height, generation);
    }

    @Override public void end(long generation) {
        if (!directFrames) return;
        directRenderer.end(generation);
        main.post(() -> { if (directGeneration == generation) releaseOriginal(); });
    }

    private static Bitmap decodeReturnedFrame(byte[] jpeg, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (!"image/jpeg".equals(options.outMimeType) || options.outWidth != width || options.outHeight != height
                || width < 1 || height < 1 || width > 1920 || height > 1920) return null;
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (bitmap != null && (bitmap.getWidth() != width || bitmap.getHeight() != height)) {
            bitmap.recycle(); return null;
        }
        return bitmap;
    }

    void setCaptureHidden(boolean value) {
        if (closed || closing) return;
        captureHidden = value;
        if (value) {
            // The normal capture handoff hides a new window before its camera starts.
            // This is not a user presentation choice; only an already running reveal is fenced.
            if (automaticReveal) interruptAutomaticReveal();
            cancelMediaHold(); releaseOriginal();
        }
        cancelPresentation();
        // Keep the View hierarchy drawable on its very first attachment. A View alpha
        // of zero can skip TextureView's first draw and deadlock cameraReady while the
        // capture page waits for readiness. Hide at the window compositor instead.
        root.setAlpha(1f); params.alpha = value ? 0f : 1f;
        if (value) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        layout(false);
        if (!value) revealFirstResult();
    }

    void expand() { if (!closed && !closing) setCollapsed(false); }
    void setCollapsed(boolean value) {
        interruptAutomaticReveal();
        if (closed || closing || mode == (value ? ShoppingIslandGeometry.Mode.COMPACT : ShoppingIslandGeometry.Mode.PREVIEW) && !movingPresentation) return;
        releaseOriginal();
        ShoppingPreviewMotion.Frame from = currentFrame();
        cancelPresentation();
        ShoppingIslandGeometry.Layout anchor = compactLayout();
        if (value) {
            mode = ShoppingIslandGeometry.Mode.COMPACT; collapsed = true; expanded = false;
            if (!animateAllowed()) { layout(false); return; }
            final int bodyHeight = currentLayout.window().height();
            animatePresentation(ShoppingPreviewMotion.closingDurationMs(from, anchor.window()),
                    p -> applyMotion(closingFrame(from,anchor,bodyHeight,p)),
                    () -> layout(false));
        } else {
            mode = ShoppingIslandGeometry.Mode.PREVIEW; collapsed = false; expanded = false;
            ShoppingIslandGeometry.Layout target = floatingFrame.layout(display(), density(), mode, characterPreview);
            backgroundFull = 0; applyBodyLayout(target, false);
            if (!animateAllowed()) { layout(false); return; }
            animatePresentation(ShoppingPreviewMotion.OPEN_MS,
                    p -> applyMotion(openingFrame(from, anchor, target, p)),
                    () -> layout(false));
        }
    }
    void setFullscreen(boolean value) {
        interruptAutomaticReveal(); setFullscreenInternal(value);
    }
    private void setFullscreenInternal(boolean value) {
        if (closed || closing || mode == (value ? ShoppingIslandGeometry.Mode.FULLSCREEN : ShoppingIslandGeometry.Mode.PREVIEW) && !movingPresentation) return;
        releaseOriginal();
        ShoppingPreviewMotion.Frame from = currentFrame();
        ShoppingIslandGeometry.Layout before = currentLayout;
        float beforeTone = backgroundFull;
        cancelPresentation();
        mode = value ? ShoppingIslandGeometry.Mode.FULLSCREEN : ShoppingIslandGeometry.Mode.PREVIEW;
        collapsed = false; expanded = value;
        ShoppingIslandGeometry.Layout target = floatingFrame.layout(display(), density(), mode, characterPreview);
        if (!animateAllowed()) { layout(false); automaticReveal=false; return; }
        animatePresentation(ShoppingPreviewMotion.MORPH_MS, p -> {
            float q = 1-p; backgroundFull = beforeTone + ((value ? 1 : 0)-beforeTone)*(1-q*q*q);
            applyBodyLayout(ShoppingPreviewMotion.layout(before, target, p), value);
            applyMotion(ShoppingPreviewMotion.morph(from, target.window(), value ? 0 : previewRadius(), p));
        }, () -> { layout(false); automaticReveal = false; });
    }
    String presentation() { return mode.name().toLowerCase(java.util.Locale.ROOT); }
    /** GUI and Agent moves both update the same retained frame and safe-area clamp. Main thread only. */
    boolean moveTo(int x, int y) {
        if (!canResizePresentation()) return false;
        interruptAutomaticReveal();
        if (originalHeld) releaseOriginal();
        floatingFrame.move(x,y); layout(false);
        return canResizePresentation();
    }
    Bundle position() {
        Bundle value=new Bundle();
        value.putInt("x",params.x);value.putInt("y",params.y);
        value.putInt("width",params.width);value.putInt("height",params.height);
        value.putBoolean("canMove",canResizePresentation());
        value.putString("host",host.id());
        return value;
    }
    String geometryDiagnostic() {
        return "windowX=" + params.x + " windowY=" + params.y + " windowWidth=" + params.width + " windowHeight=" + params.height
                + " host=" + host.id() + " motionActive=" + movingPresentation + " motionProgress=" + motionProgress;
    }
    private float density() { return context.getResources().getDisplayMetrics().density; }
    private ShoppingIslandGeometry.Layout compactLayout() {
        return ShoppingPreviewLayout.applicationOverlayLayout(display(),density(),ShoppingIslandGeometry.Mode.COMPACT,0,false,0,0,characterPreview);
    }
    private ShoppingPreviewMotion.Frame openingFrame(ShoppingPreviewMotion.Frame from,ShoppingIslandGeometry.Layout anchor,
                                                      ShoppingIslandGeometry.Layout target,float progress) {
        ShoppingIslandGeometry.Display d=display();
        boolean hole=ShoppingIslandGeometry.trustedHole(d,density());
        int cx=hole?(d.cutoutLeft()+d.cutoutRight())/2:d.width()/2;
        int top=hole?Math.max(0,(d.cutoutTop()+d.cutoutBottom())/2-ui.dp(16)):anchor.window().y();
        return ShoppingPreviewMotion.islandOpening(from,anchor.window(),target.window(),cx,top,ui.dp(32),previewRadius(),progress);
    }
    private ShoppingPreviewMotion.Frame closingFrame(ShoppingPreviewMotion.Frame from,ShoppingIslandGeometry.Layout anchor,int bodyHeight,float p) {
        var d=display();boolean hole=ShoppingIslandGeometry.trustedHole(d,density());
        int cx=hole?(d.cutoutLeft()+d.cutoutRight())/2:d.width()/2;
        int top=hole?Math.max(0,(d.cutoutTop()+d.cutoutBottom())/2-ui.dp(16)):anchor.window().y();
        return ShoppingPreviewMotion.islandClosing(from,anchor.window(),cx,top,ui.dp(32),p);
    }
    private boolean animateAllowed() { return attached && !captureHidden && !closed && !closing && ValueAnimator.areAnimatorsEnabled(); }
    /** Kept separate from show(): the isolated UI fixture can exercise real motion without starting a camera. */
    private void reveal() { reveal(null); }
    private void reveal(Runnable completion) {
        if (!animateAllowed()) {
            mode = ShoppingIslandGeometry.Mode.PREVIEW; layout(false);
            if (completion != null) completion.run();
            return;
        }
        ShoppingPreviewMotion.Frame from = movingPresentation ? currentFrame()
                : ShoppingPreviewMotion.resting(compactLayout().window(), ui.dp(16), true);
        cancelPresentation();
        mode = ShoppingIslandGeometry.Mode.PREVIEW; collapsed = false; expanded = false;
        ShoppingIslandGeometry.Layout target = floatingFrame.layout(display(), density(), mode, characterPreview);
        backgroundFull = 0; applyBodyLayout(target, false);
        ShoppingIslandGeometry.Layout anchor = compactLayout(); configurePill(anchor);
        animatePresentation(ShoppingPreviewMotion.OPEN_MS,
                p -> applyMotion(openingFrame(from, anchor, target, p)),
                () -> { layout(false); if (completion != null) completion.run(); });
    }
    private float previewRadius() { return ui.dp(22); }
    private void revealFirstResult() {
        long ticket = firstReveal.claim(directFrames && generatedFrameShown && attached
                && !closed && !closing && !captureHidden, characterPreview);
        if (ticket == 0) return;
        automaticReveal = true;
        reveal(() -> {
            if (!firstReveal.current(ticket) || closed || closing || captureHidden) return;
            setFullscreenInternal(true);
        });
    }
    private void interruptAutomaticReveal() {
        firstReveal.interrupt();
        if (automaticReveal) {
            automaticReveal = false; cancelPresentation();
            if (attached && !closed && !closing) layout(false);
        }
    }
    private interface MotionStep { void draw(float progress); }
    private void animatePresentation(long duration, MotionStep step, Runnable completion) {
        final long epoch = motionEpoch.next(); presentationAnimation = epoch;
        movingPresentation = true; motionProgress = 0;
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1); motionAnimator = animator;
        animator.setDuration(duration); animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            if (!motionEpoch.current(epoch) || closed || closing || captureHidden) return;
            motionProgress = (Float) value.getAnimatedValue(); step.draw(motionProgress);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!motionEpoch.current(epoch) || closed || closing || captureHidden) return;
                motionAnimator = null; movingPresentation = false; motionProgress = 1;
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                completion.run();
            }
        });
        step.draw(0); animator.start();
    }
    private void cancelPresentation() {
        presentationAnimation = motionEpoch.next();
        if (motionAnimator != null) { ValueAnimator old = motionAnimator; motionAnimator = null; old.cancel(); }
        movingPresentation = false; root.animate().cancel();
        root.setScaleX(1); root.setScaleY(1);
        if (!captureHidden && !closing) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }
    private ShoppingPreviewMotion.Frame currentFrame() {
        if (motionFrame != null) return motionFrame;
        return ShoppingPreviewMotion.resting(new ShoppingIslandGeometry.Box(params.x, params.y, Math.max(1,params.width), Math.max(1,params.height)),
                expanded ? 0 : previewRadius(), collapsed);
    }
    private void applyMotion(ShoppingPreviewMotion.Frame frame) {
        motionFrame = frame;
        params.alpha = captureHidden || closing ? 0f : 1f;
        if (captureHidden || closing) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        ShoppingIslandGeometry.Box box = frame.box();
        params.x = box.x(); params.y = box.y(); params.width = box.width(); params.height = box.height();
        visualRadius = frame.radius(); root.invalidateOutline();
        content.setTranslationY(frame.offsetY()); content.setAlpha(frame.bodyAlpha());
        content.setTranslationX(currentLayout!=null?(box.width()-currentLayout.window().width())/2f:0);
        var anchor=compactLayout();
        FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)pill.getLayoutParams();
        pp.width=anchor.window().width();pp.height=anchor.window().height();pp.gravity=Gravity.TOP|Gravity.LEFT;
        pp.leftMargin=anchor.window().x()-box.x();pp.topMargin=anchor.window().y()-box.y();pill.setLayoutParams(pp);
        pill.setVisibility(frame.pillAlpha() > 0 ? View.VISIBLE : View.INVISIBLE); pill.setAlpha(frame.pillAlpha());
        backplate.setVisibility(View.VISIBLE); backplate.setAlpha(frame.bodyAlpha()==0?0:1-frame.pillAlpha());
        backplate.setBackground(chrome.motionBackplate(backgroundFull, frame.radius(), frame.ink()));
        root.setScaleX(1); root.setScaleY(1); updateLayout();
    }

    void setCharacterModel(String modelLabel) {
        automaticReveal = false; characterPreview = true; characterModelLabel = modelLabel;
        chrome.characterPreview();
        status.setText("Opening camera");
        root.setContentDescription("Live cosplay · " + modelLabel);
        returned.setContentDescription("Live character result · " + modelLabel);
        params.setTitle("Taku Live Cosplay");
        header.setContentDescription("Drag to move cosplay. Double tap to minimize.");
        cameraPane.setContentDescription("Cosplay video. Drag to move, pinch to resize, hold to compare, double tap to minimize.");
        installDrag(content);
        layout(false);
    }

    void render(CallTryOnService.Snapshot value) {
        if (closed || closing) return;
        snapshot = value;
        String effect = characterPreview ? "Character" : "Try-on";
        String text = characterPreview && !generatedFrameShown ? (!readyReported ? "Opening camera" : "Camera · Connecting") :
                !readyReported ? "Preparing try-on" :
                value == null ? "Connecting" : originalHeld ? "Original · " + value.remainingSeconds + "s" :
                "running".equals(value.state) && value.returnedFrames > 0 ? effect + " · " + value.remainingSeconds + "s" :
                value.active ? "Connecting · " + value.remainingSeconds + "s" : effect + " stopped";
        status.setText(text);
        chrome.comparing(originalHeld);
        pillStatus.setText(generatedFrameShown ? "Live" : "Preparing…");
        pillStatus.setVisibility(View.VISIBLE);
        pill.setContentDescription((characterPreview ? "Open cosplay window. " : "Open try-on window. ") + pillStatus.getText());
        if (characterPreview) root.setContentDescription("Live cosplay · " + characterModelLabel
                + (value != null && value.active ? ". " + value.remainingSeconds + " seconds remaining." : ". Connecting."));
        updateGuide();
    }

    private void updateGuide() {
        // Only a decoded return changes Preparing to Live. This also updates a
        // manually opened window after its automatic reveal was canceled.
        if (status != null) status.setVisibility(generatedFrameShown ? View.GONE : View.VISIBLE);
        if (pillStatus != null) { pillStatus.setText(generatedFrameShown?"Live":"Preparing…");pillStatus.setVisibility(View.VISIBLE); }
        if (workingDots != null) workingDots.setVisibility(generatedFrameShown?View.GONE:View.VISIBLE);
        if (compactMark != null) compactMark.setVisibility(generatedFrameShown?View.VISIBLE:View.GONE);
        // The renderer invokes this only after a real decoded return is attached, never on a counter or estimate.
        if (chrome != null) {
            if (characterPreview) chrome.characterState(readyReported, snapshot != null && snapshot.active, generatedFrameShown);
            else chrome.live(generatedFrameShown && snapshot != null && snapshot.active);
        }
        if (bodyGuide != null) bodyGuide.setVisibility(!characterPreview && readyReported && !generatedFrameShown && !collapsed
                && !closed && !closing ? View.VISIBLE : View.INVISIBLE);
    }

    /** Called on the existing bridge IO actor; at most one undecoded input and one decode exist. */
    void offerOriginalFrame(byte[] jpeg, int width, int height) {
        if (!originalHeld || captureHidden || collapsed || closed || closing || jpeg == null || jpeg.length > CallTryOnBridge.MAX_JPEG
                || width < 1 || height < 1 || width > 1920 || height > 1920) return;
        OriginalFrame prepared;
        try { prepared = new OriginalFrame(jpeg.clone(), width, height, originalEpoch); }
        catch (OutOfMemoryError unavailable) { return; }
        pendingOriginal.set(prepared);
        if (!originalHeld || closed || closing || captureHidden || collapsed || prepared.epoch != originalEpoch) {
            pendingOriginal.compareAndSet(prepared, null); return;
        }
        scheduleDecode();
    }

    private void scheduleDecode() {
        if (!decoding.compareAndSet(false, true)) return;
        try {
            decoder.execute(() -> {
                Bitmap bitmap = null;
                OriginalFrame frame = pendingOriginal.getAndSet(null);
                try {
                    if (frame != null && originalHeld && frame.epoch == originalEpoch && !closed) {
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length, options);
                        if (options.outWidth == frame.width && options.outHeight == frame.height
                                && options.outWidth > 0 && options.outHeight > 0 && options.outWidth <= 1920 && options.outHeight <= 1920) {
                            options.inJustDecodeBounds = false; options.inSampleSize = 1;
                            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 960) options.inSampleSize *= 2;
                            bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length, options);
                        }
                    }
                } catch (RuntimeException | OutOfMemoryError ignored) { /* A comparison never changes the live run. */ }
                finally {
                    Bitmap completed = bitmap;
                    if (completed != null) {
                        if (!main.post(() -> {
                            try {
                                if (closed || closing || !originalHeld || frame.epoch != originalEpoch || captureHidden || collapsed) {
                                    completed.recycle(); return;
                                }
                                Bitmap previous = displayedOriginal; displayedOriginal = completed;
                                original.setImageBitmap(completed); updateOriginalTransform(); original.setVisibility(View.VISIBLE);
                                if (previous != null) previous.recycle();
                            } finally { decodeFinished(); }
                        })) { completed.recycle(); decodeFinished(); }
                    } else decodeFinished();
                }
            });
        } catch (RejectedExecutionException closedDuringSubmit) { decoding.set(false); pendingOriginal.set(null); }
    }

    private void decodeFinished() {
        decoding.set(false);
        if (pendingOriginal.get() != null && !closed && !closing) scheduleDecode();
    }
    private void updateOriginalTransform() {
        // Direct source and return JPEGs are already upright and mirrored once by the camera actor.
        if (directFrames) return;
        Bitmap bitmap = displayedOriginal;
        if (bitmap == null || bitmap.isRecycled() || original.getWidth() < 1 || original.getHeight() < 1) return;
        original.setImageMatrix(camera.originalImageMatrix(original.getWidth(), original.getHeight(), bitmap.getWidth(), bitmap.getHeight()));
    }

    private void releaseOriginal() {
        originalHeld = false; originalEpoch++; pendingOriginal.set(null);
        original.setVisibility(View.INVISIBLE); original.setImageDrawable(null);
        if (displayedOriginal != null) { displayedOriginal.recycle(); displayedOriginal = null; }
        render(snapshot);
    }

    /** Completion waits for the exact CameraDevice.onClosed, including late open callbacks. */
    void close(Runnable afterClosed) {
        if (closed || closing) { if (afterClosed != null) camera.close(afterClosed); return; }
        closing = true; firstReveal.interrupt(); automaticReveal=false; cancelMediaHold(); cancelPresentation(); releaseOriginal(); decoder.shutdown();
        main.removeCallbacks(refreshGeometry); geometryRefreshPosted=false;
        if (directRenderer != null) directRenderer.close();
        root.setAlpha(1f); params.alpha = 0f;
        params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (Build.VERSION.SDK_INT >= 31) params.setBlurBehindRadius(0);
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; updateLayout();
        camera.close(() -> {
            closed = true; attached = false; attaching = false; camera.dispose();
            host.close();
            context.unregisterComponentCallbacks(configuration);
            if (afterClosed != null) afterClosed.run();
        });
    }

    private Button control(String label, String description) {
        Button button = ui.button(label, false); button.setContentDescription(description);
        ShoppingIslandStyle.text(button, 14); button.setTextColor(ShoppingIslandStyle.TEXT);
        button.setBackgroundColor(Color.TRANSPARENT); button.setMaxLines(1);
        button.setPadding(ui.dp(8), 0, ui.dp(8), 0); button.setMinHeight(ui.dp(44)); button.setMinimumHeight(ui.dp(44));
        button.setMinWidth(ui.dp(44)); button.setMinimumWidth(ui.dp(44)); return button;
    }

    private ShoppingIslandGeometry.Display display() {
        android.view.WindowMetrics metrics = windows.getCurrentWindowMetrics(); Rect bounds = metrics.getBounds();
        Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        DisplayCutout cutout = metrics.getWindowInsets().getDisplayCutout(); Rect hole = new Rect();
        // getBoundingRectTop() is a reserved status-bar band on some Pixels, not a physical lens.
        // Only a credible physical cutout path can anchor the visual island to the camera.
        if (Build.VERSION.SDK_INT >= 31 && cutout != null) {
            Path path = cutout.getCutoutPath();
            if (path != null && !path.isEmpty()) { RectF physical = new RectF(); path.computeBounds(physical, true); physical.roundOut(hole); }
        }
        return new ShoppingIslandGeometry.Display(bounds.width(), bounds.height(), safe.left, safe.top, safe.right, safe.bottom,
                hole.left, hole.top, hole.right, hole.bottom);
    }
    private void layout(boolean initial) {
        var display=display();
        displayState.applied(display);
        ShoppingIslandGeometry.Layout target = mode == ShoppingIslandGeometry.Mode.COMPACT
                ? ShoppingPreviewLayout.applicationOverlayLayout(display,density(),mode,0,false,0,0,characterPreview)
                : floatingFrame.layout(display, density(), mode, characterPreview);
        collapsed = mode == ShoppingIslandGeometry.Mode.COMPACT; expanded = mode == ShoppingIslandGeometry.Mode.FULLSCREEN;
        backgroundFull = expanded ? 1 : 0;
        // The camera subtree retains its last logical size in compact mode. Only its
        // compositor visibility changes; no SurfaceTexture is detached or reparented.
        ShoppingIslandGeometry.Layout body = collapsed && currentLayout != null ? currentLayout
                : collapsed ? floatingFrame.layout(display, density(), ShoppingIslandGeometry.Mode.PREVIEW, characterPreview) : target;
        applyBodyLayout(body, expanded);
        configurePill(compactLayout());
        params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (Build.VERSION.SDK_INT >= 31) params.setBlurBehindRadius(0);
        applyMotion(ShoppingPreviewMotion.resting(target.window(), expanded ? 0 : collapsed ? ui.dp(16) : previewRadius(), collapsed));
        content.setImportantForAccessibility(collapsed ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (collapsed) {
            header.setVisibility(View.INVISIBLE); videoControls.setVisibility(View.INVISIBLE);
            resizeHandle.setVisibility(View.INVISIBLE); bottomHandle.setVisibility(View.INVISIBLE);
            cameraPane.setAlpha(0f);
        }
        updateGuide();
    }
    private void configurePill(ShoppingIslandGeometry.Layout anchor) {
        capsule.geometry(anchor.leftLobe(),anchor.rightLobe(),anchor.holeWidth());pillVisual.setBackground(capsule);
        FrameLayout.LayoutParams visual=(FrameLayout.LayoutParams)pillVisual.getLayoutParams();
        visual.width=-1;visual.height=anchor.headerHeight();visual.gravity=Gravity.TOP|Gravity.LEFT;
        visual.topMargin=anchor.headerContentTop();pillVisual.setLayoutParams(visual);
        for(View mark:new View[]{compactMark,workingDots}) {
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)mark.getLayoutParams();
            lp.gravity=Gravity.LEFT|Gravity.CENTER_VERTICAL;
            lp.leftMargin=ui.dp(8);mark.setLayoutParams(lp);
        }
        workingDots.setVisibility(!generatedFrameShown?View.VISIBLE:View.GONE);
        compactMark.setVisibility(generatedFrameShown?View.VISIBLE:View.GONE);
        FrameLayout.LayoutParams label=(FrameLayout.LayoutParams)pillStatus.getLayoutParams();
        label.width=Math.max(1,anchor.rightLobe());label.rightMargin=0;pillStatus.setLayoutParams(label);
        pillClose.setVisibility(View.VISIBLE);
        pillStatus.setVisibility(View.VISIBLE);
    }
    private void applyBodyLayout(ShoppingIslandGeometry.Layout body, boolean full) {
        currentLayout = body;
        ShoppingIslandGeometry.Box box = body.window(), video = body.video();
        FrameLayout.LayoutParams contentLayout = (FrameLayout.LayoutParams) content.getLayoutParams();
        contentLayout.width = box.width(); contentLayout.height = box.height();
        contentLayout.gravity = Gravity.TOP | Gravity.LEFT; content.setLayoutParams(contentLayout);
        FrameLayout.LayoutParams cameraLayout = (FrameLayout.LayoutParams) cameraPane.getLayoutParams();
        cameraLayout.width = video.width(); cameraLayout.height = video.height();
        cameraLayout.gravity = Gravity.TOP | Gravity.LEFT; cameraLayout.leftMargin = video.x(); cameraLayout.topMargin = video.y();
        cameraPane.setLayoutParams(cameraLayout); cameraPane.setAlpha(1f); cameraPane.setVisibility(View.VISIBLE);
        cameraPane.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        cameraPane.setBackground(ui.rounded(INK, 0, full ? 8 : 22)); cameraPane.setClipToOutline(true);
        if (!characterPreview) {
            returned.setScaleType(full ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            original.setScaleType(directFrames ? full ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.MATRIX);
        } else {
            returned.setScaleType(ImageView.ScaleType.FIT_CENTER);
            original.setScaleType(directFrames ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.MATRIX);
        }
        ShoppingIslandGeometry.Box media = ShoppingPreviewLayout.mediaBox(video, false);
        for (View image : new View[]{camera, returned, original, bodyGuide}) {
            FrameLayout.LayoutParams mediaLayout = (FrameLayout.LayoutParams) image.getLayoutParams();
            if (mediaLayout == null) continue;
            mediaLayout.width = media.width(); mediaLayout.height = media.height();
            mediaLayout.gravity = Gravity.TOP | Gravity.LEFT; mediaLayout.leftMargin = media.x(); mediaLayout.topMargin = media.y();
            image.setLayoutParams(mediaLayout);
        }
        chrome.layout(body, full);
        if (!characterPreview) header.setVisibility(View.GONE);
        videoControls.setVisibility(View.VISIBLE);
        status.setVisibility(!generatedFrameShown ? View.VISIBLE : View.GONE);
        bottomHandle.setVisibility(View.GONE); resizeHandle.setVisibility(characterPreview && !full ? View.VISIBLE : View.GONE);
        camera.updateTransform();
    }

    private boolean canResizePresentation() {
        return ShoppingPreviewLayout.movable(attached,closed,closing,captureHidden,mode,movingPresentation);
    }

    /** Two-finger sizing changes this same View's bounds, never the camera/RTC session. */
    private final class PreviewRoot extends FrameLayout {
        private boolean pinchTouch;
        private final ScaleGestureDetector pinch;
        PreviewRoot(Context context) {
            super(context);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                    if (!canResizePresentation()) return false;
                    releaseOriginal(); cancelPresentation();
                    return true;
                }
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    if (!canResizePresentation()) return false;
                    floatingFrame.resize(ShoppingPreviewLayout.scaledWidth(params.width, detector.getScaleFactor()));
                    ShoppingPreviewWindow.this.layout(false); return true;
                }
            });
            pinch.setQuickScaleEnabled(false);
            pinch.setStylusScaleEnabled(false);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if(captureHidden||closing||closed)return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) interruptAutomaticReveal();
            if (canResizePresentation() && event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && !pinchTouch) {
                pinchTouch = true;
                // A second finger cancels a pending Compare/Close press before resizing takes ownership.
                MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                try { super.dispatchTouchEvent(cancel); } finally { cancel.recycle(); }
            }
            if (canResizePresentation() || pinchTouch) pinch.onTouchEvent(event);
            if (pinchTouch) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) pinchTouch = false;
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            return canResizePresentation() || super.onTouchEvent(event);
        }
    }
    private void updateLayout() {
        if (closed) return;
        try {
            // Pending binds still need the newest capture flag and rotation geometry.
            host.update(params);
        }
        catch (RuntimeException revoked) { if (!closing) actions.presentationFailed("preview_host_failed"); }
    }
    private void installDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean dragged; long lastTap;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (closed || closing || captureHidden || collapsed || expanded || movingPresentation) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = params.x; startY = params.y; dragged = false; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > ui.dp(6)) dragged = true;
                    if (dragged) {
                        moveTo(startX + Math.round(dx), startY + Math.round(dy));
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (!dragged) {
                        long now = SystemClock.uptimeMillis();
                        if (lastTap != 0 && now - lastTap <= ViewConfiguration.getDoubleTapTimeout()) {
                            lastTap = 0; setCollapsed(true);
                        } else { lastTap = now; view.performClick(); }
                    } else lastTap = 0;
                    return true;
                }
                return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
    }

    private void installResize(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startWidth;
            boolean changed;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (closed || closing || captureHidden || collapsed || expanded || movingPresentation) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startWidth = params.width; changed = false;
                    releaseOriginal(); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) < ui.dp(3) && !changed) return true;
                    changed = true;
                    floatingFrame.resize(ShoppingPreviewGeometry.resizedWidth(startWidth, dx, dy));
                    // This only updates the existing overlay/View bounds; the CameraDevice, stream,
                    // TextureView and active model remain owned by the same shopping session.
                    layout(false); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (!changed) view.performClick();
                    return true;
                }
                return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
    }
    private void installIslandGesture(View handle, boolean opening) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float x, y; boolean active, canceled;
            long holdEpoch;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (closed || closing || captureHidden) return false;
                if (handle == cameraPane) return shoppingMediaTouch(view, event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        x=event.getRawX();y=event.getRawY();active=true;canceled=false;holdEpoch=interactionEpoch;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        return active;
                    }
                    case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { canceled=true;active=false;return true; }
                    case MotionEvent.ACTION_UP -> {
                        if (!active || canceled || holdEpoch!=interactionEpoch) { active=false; return true; } active=false;
                        float dx=event.getRawX()-x,dy=event.getRawY()-y;
                        int threshold=Math.max(ui.dp(28),ViewConfiguration.get(context).getScaledTouchSlop()*2);
                        if (opening ? ShoppingIslandGesture.expand(dx,dy,threshold) : ShoppingIslandGesture.collapse(dx,dy,threshold)) {
                            setCollapsed(!opening); return true;
                        }
                        if (Math.abs(dx)+Math.abs(dy)<ViewConfiguration.get(context).getScaledTouchSlop()) view.performClick();
                        return true;
                    }
                    default -> { return active; }
                }
            }
        });
    }
    private void cancelMediaHold() {
        interactionEpoch++; main.removeCallbacks(compareHold); mediaPressed=false; mediaDragged=false; mediaLastTap=0;
    }

    /** Shopping media owns drag/hold; its controls overlay the video rather than reserving a white row. */
    private boolean shoppingMediaTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mediaDownX=event.getRawX(); mediaDownY=event.getRawY(); mediaStartX=params.x;
                mediaStartY=ShoppingPreviewLayout.mediaDragStartY(params.y,currentLayout,floatingFrame.moved,expanded,characterPreview);
                mediaDragged=false; mediaPressed=true;
                main.postDelayed(compareHold, ViewConfiguration.getLongPressTimeout()); return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx=event.getRawX()-mediaDownX, dy=event.getRawY()-mediaDownY;
                if (Math.abs(dx)+Math.abs(dy)>ViewConfiguration.get(context).getScaledTouchSlop()) {
                    mediaDragged=true; main.removeCallbacks(compareHold);
                    if (originalHeld) releaseOriginal();
                    if (!expanded && !collapsed && !movingPresentation) {
                        moveTo(mediaStartX+Math.round(dx),mediaStartY+Math.round(dy));
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                main.removeCallbacks(compareHold); mediaPressed=false;
                boolean held=originalHeld; if (held) releaseOriginal();
                if (!mediaDragged && !held) {
                    long now=SystemClock.uptimeMillis();
                    if (mediaLastTap!=0 && now-mediaLastTap<=ViewConfiguration.getDoubleTapTimeout()) {
                        mediaLastTap=0; setCollapsed(true);
                    } else { mediaLastTap=now; view.performClick(); }
                } else mediaLastTap=0;
                return true;
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(compareHold); mediaPressed=false; mediaLastTap=0;
                if (originalHeld) releaseOriginal(); return true;
            }
            default -> { return mediaPressed; }
        }
    }
    private static final class OriginalFrame {
        final byte[] jpeg; final int width, height; final long epoch;
        OriginalFrame(byte[] jpeg, int width, int height, long epoch) {
            this.jpeg = jpeg; this.width = width; this.height = height; this.epoch = epoch;
        }
    }
}
