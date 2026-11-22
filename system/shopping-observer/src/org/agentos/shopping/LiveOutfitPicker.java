// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * A user-requested frozen-frame point picker. All mutating calls run on the main thread.
 * The caller retains the bitmap and must close this window before recycling it.
 * This class never captures, stores or submits an image, starts a camera, or executes try-on.
 */
final class LiveOutfitPicker implements AutoCloseable {
    interface Callback {
        void onPick(int x, int y);
        void onCancel();
    }

    private static final int TEXT = 0xebffffff, BODY = 0xccffffff, MUTED = 0xa3ffffff;
    private static final String DEFAULT_MESSAGE = "Tap the top you want to try on.";
    private final Context context;
    private final WindowManager manager;
    private final Callback callback;
    private final Typeface font;
    private final int windowType;
    private Bitmap preview;
    private LinearLayout root, progressRow;
    private TextView message;
    private String messageText = DEFAULT_MESSAGE;
    private PickImage image;
    private WindowManager.LayoutParams params;
    private boolean busy, cancelled;
    private volatile boolean closed, attached;
    private volatile int windowId = -1;

    LiveOutfitPicker(Context context, Bitmap preview, Callback callback) {
        requireMain();
        if (context == null || preview == null || preview.isRecycled() || preview.getWidth() <= 0
                || preview.getHeight() <= 0 || callback == null)
            throw new IllegalArgumentException("Missing outfit preview");
        this.context = context;
        this.preview = preview;
        this.callback = callback;
        manager = context.getSystemService(WindowManager.class);
        if (manager == null) throw new IllegalStateException("Window manager unavailable");
        // Only the already-authorized, connected service can own an accessibility overlay.
        // A normal Context uses its existing app-overlay permission (also useful for UI-only fixtures).
        windowType = context instanceof AccessibilityService ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        font = loadFont(context);
    }

    void show() {
        requireMain();
        if (closed) throw new IllegalStateException("Outfit picker is closed");
        if (root != null) return;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(12),dp(16),dp(16));
        GradientDrawable material = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff161616,0xff0d0d0d});
        material.setCornerRadius(dp(40)); material.setStroke(Math.max(1,dp(.5f)),0x0affffff);
        root.setBackground(material); root.setClipToOutline(true); root.setElevation(dp(6));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setAccessibilityPaneTitle("Select outfit");

        LinearLayout grip = new LinearLayout(context); grip.setGravity(Gravity.CENTER);
        grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        View bar = new View(context); bar.setBackground(shape(0x66ffffff,999));
        grip.addView(bar,new LinearLayout.LayoutParams(dp(36),dp(4)));
        root.addView(grip,new LinearLayout.LayoutParams(-1,dp(12)));
        root.addView(text("Select outfit",18,BODY),spaced(12));
        message = text(messageText,14,MUTED);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(message,spaced(8));

        image = new PickImage(context,preview);
        image.setEnabled(!busy);
        LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(-1,0,1);
        picture.topMargin = dp(16); root.addView(image,picture);

        progressRow = new LinearLayout(context); progressRow.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar spinner = new ProgressBar(context,null,android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(BODY));
        progressRow.addView(spinner,new LinearLayout.LayoutParams(dp(18),dp(18)));
        LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(0,-2,1);
        statusSize.leftMargin = dp(10);
        progressRow.addView(text("Finding selected outfit…",14,BODY),statusSize);
        LinearLayout.LayoutParams progressSize = spaced(12); progressSize.bottomMargin = dp(4);
        // Reserve this short status row so the frozen image and tap marker do not jump when a pick starts.
        root.addView(progressRow,progressSize); progressRow.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);

        Button cancel = new Button(context);
        cancel.setText("Cancel"); cancel.setContentDescription("Cancel outfit selection"); cancel.setAllCaps(false);
        cancel.setTypeface(font); cancel.setTextSize(14); cancel.setTextColor(TEXT); cancel.setLetterSpacing(-.01f);
        cancel.setIncludeFontPadding(false); cancel.setPadding(dp(16),dp(12),dp(16),dp(12));
        cancel.setMinHeight(dp(48)); cancel.setMinimumHeight(dp(48)); cancel.setMinWidth(0); cancel.setMinimumWidth(0);
        cancel.setStateListAnimator(null); cancel.setElevation(0);
        cancel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22777777),shape(0xff2c2c2c,999),null));
        cancel.setOnClickListener(v -> requestCancel()); root.addView(cancel,spaced(16));

        params = new WindowManager.LayoutParams(1,1,windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.windowAnimations = 0;
        params.setTitle("Taku Outfit Selection");
        configureBounds();
        root.setOnApplyWindowInsetsListener((view,insets) -> { updateBounds(); return insets; });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> refreshWindowId());
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { attached = true; view.post(() -> refreshWindowId()); }
            @Override public void onViewDetachedFromWindow(View view) {
                attached = false; windowId = -1;
                if (!closed) requestCancel();
            }
        });
        try { manager.addView(root,params); }
        catch (RuntimeException unavailable) { close(); throw unavailable; }
        root.post(() -> refreshWindowId());
    }

    void setBusy(boolean busy) {
        requireMain();
        if (closed) return;
        this.busy = busy;
        if (image != null) { image.setEnabled(!busy); image.resetGesture(); }
        if (progressRow != null) progressRow.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
    }

    /** Plain display text only. Call setBusy(false) as well when a new selection is allowed. */
    void setMessage(String value) {
        requireMain();
        if (closed) return;
        messageText = value == null || value.isBlank() ? DEFAULT_MESSAGE : value;
        if (message != null) message.setText(messageText);
    }

    int accessibilityWindowId() { return windowId; }
    boolean isShowing() { return !closed && attached; }

    private void selected(OutfitPickGeometry.Point point) {
        if (closed || busy || cancelled || point == null) return;
        setBusy(true);
        callback.onPick(point.x(),point.y());
    }

    private void requestCancel() {
        if (closed || cancelled) return;
        cancelled = true;
        try { callback.onCancel(); }
        finally { close(); }
    }

    @Override public void close() {
        requireMain();
        if (closed) return;
        closed = true; windowId = -1;
        try {
            if (root != null) {
                root.animate().cancel(); root.setOnApplyWindowInsetsListener(null);
                if (attached || root.isAttachedToWindow()) {
                    try { manager.removeViewImmediate(root); }
                    catch (IllegalArgumentException alreadyRemoved) { /* A system detach can win the race. */ }
                }
            }
        } finally {
            attached = false;
            if (image != null) image.releasePreview();
            preview = null; image = null; message = null; progressRow = null; root = null; params = null;
        }
    }

    private void refreshWindowId() {
        if (closed || root == null || !root.isAttachedToWindow()) return;
        AccessibilityNodeInfo node = root.createAccessibilityNodeInfo();
        if (node != null) try { windowId = node.getWindowId(); } finally { node.recycle(); }
    }

    private void configureBounds() {
        Rect bounds = manager.getCurrentWindowMetrics().getBounds();
        WindowInsets insets = manager.getCurrentWindowMetrics().getWindowInsets();
        Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int bottomInset = Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);
        int left = bounds.left + bars.left + dp(8), right = bounds.right - bars.right - dp(8);
        int top = bounds.top + bars.top + dp(8), bottom = bounds.bottom - bottomInset - dp(8);
        int availableWidth = Math.max(1,right-left), availableHeight = Math.max(1,bottom-top);
        params.width = Math.min(dp(396),availableWidth);
        params.height = Math.min(availableHeight,Math.max(dp(320),Math.round(availableHeight*.82f)));
        params.x = left + (availableWidth-params.width)/2; params.y = bottom-params.height;
    }

    private void updateBounds() {
        if (closed || root == null || params == null || !root.isAttachedToWindow()) return;
        int x = params.x, y = params.y, width = params.width, height = params.height;
        configureBounds();
        if (x != params.x || y != params.y || width != params.width || height != params.height)
            manager.updateViewLayout(root,params);
    }

    private int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }
    private TextView text(String value,int size,int color) {
        TextView result = new TextView(context); result.setText(value); result.setTextSize(size); result.setTextColor(color);
        result.setTypeface(font); result.setIncludeFontPadding(false); result.setLetterSpacing(-.01f);
        result.setFontFeatureSettings("kern"); result.setLineSpacing(0,1.55f); return result;
    }
    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1,-2); result.topMargin = dp(top); return result;
    }
    private static Typeface loadFont(Context context) {
        try {
            Context resources = context.createPackageContext("org.taku.tryon",Context.CONTEXT_RESTRICTED);
            return Typeface.createFromAsset(resources.getAssets(),"fonts/hanken-grotesk.ttf");
        } catch (PackageManager.NameNotFoundException | RuntimeException missing) {
            return Typeface.create("sans-serif",Typeface.NORMAL);
        }
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Outfit picker requires the main thread");
    }

    private final class PickImage extends View {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int touchSlop;
        private Bitmap bitmap;
        private OutfitPickGeometry.Point marker;
        private float downX, downY;
        private boolean gesture, moved;

        PickImage(Context context,Bitmap bitmap) {
            super(context); this.bitmap = bitmap;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setBackground(shape(0xff202020,24)); setClipToOutline(true);
            setContentDescription("Outfit photo. Tap the top you want to try on.");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        private OutfitPickGeometry.Fit fit() {
            return bitmap == null || bitmap.isRecycled() ? null : OutfitPickGeometry.fit(
                    bitmap.getWidth(),bitmap.getHeight(),getWidth(),getHeight(),dp(2));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            OutfitPickGeometry.Fit fit = fit();
            if (fit == null) return;
            canvas.drawBitmap(bitmap,null,new RectF((float)fit.left(),(float)fit.top(),(float)fit.right(),(float)fit.bottom()),bitmapPaint);
            if (marker != null) {
                float x = (float)(fit.left()+fit.width()*marker.x()/1000), y = (float)(fit.top()+fit.height()*marker.y()/1000);
                markerPaint.setColor(0x99000000); markerPaint.setStyle(Paint.Style.FILL); canvas.drawCircle(x,y,dp(15),markerPaint);
                markerPaint.setColor(TEXT); markerPaint.setStyle(Paint.Style.STROKE); markerPaint.setStrokeWidth(dp(2));
                canvas.drawCircle(x,y,dp(12),markerPaint);
                markerPaint.setStyle(Paint.Style.FILL); canvas.drawCircle(x,y,dp(3),markerPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || busy || !isEnabled()) { resetGesture(); return false; }
            if (event.getPointerCount() != 1) { resetGesture(); return true; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                gesture = OutfitPickGeometry.pick(fit(),event.getX(),event.getY()) != null;
                moved = false; downX = event.getX(); downY = event.getY(); return gesture;
            }
            if (!gesture) return false;
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getX()-downX) > touchSlop || Math.abs(event.getY()-downY) > touchSlop) moved = true;
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                OutfitPickGeometry.Point point = moved ? null : OutfitPickGeometry.pick(fit(),event.getX(),event.getY());
                resetGesture();
                if (point != null) { marker = point; invalidate(); performClick(); selected(point); }
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) { resetGesture(); return true; }
            return true;
        }
        @Override public boolean performClick() { return super.performClick(); }
        void resetGesture() { gesture = moved = false; }
        void releasePreview() { resetGesture(); animate().cancel(); bitmap = null; marker = null; invalidate(); }
    }
}
