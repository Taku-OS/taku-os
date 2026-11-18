package org.agentos.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import java.util.Random;

/** One small companion, rendered from real pose assets inside its reserved safe slot.
 * No task dispatch, audio listening or synthetic progress lives in this component.
 */
public final class DesktopPetView extends View {
    private static final int[] WAVE_FRAMES = {8, 9, 10, 9, 11, 0};
    private static Bitmap sharedAtlas, sharedStill;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final Rect visible = new Rect();
    private final RectF destination = new RectF();
    private final Random random = new Random();
    private final DesignTokens tokens;
    private Bitmap atlas, still;
    private boolean lifecycleActive, dragging, pressed, initialized, observing, systemMotionReduced;
    private Boolean reduceMotionOverride;
    private int frame, step;
    private String motion = "idle", state = "idle", taskId = "";
    private long motionStarted, nextIdleAt;
    private float centerX = Float.NaN, centerY = Float.NaN, downX, downY, startX, startY;
    private Runnable onPetTap;

    public DesktopPetView(Context context) {
        super(context);
        tokens = new DesignTokens(context);
        loadAssets();
        systemMotionReduced = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0;
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumWidth(tokens.pixels(R.dimen.ag_size_companion_min));
        setMinimumHeight(tokens.pixels(R.dimen.ag_size_companion_min));
        initialized = true;
        updateDescription();
    }

    private void loadAssets() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        int atlasId = getResources().getIdentifier("taku_pet_atlas", "drawable", getContext().getPackageName());
        int stillId = getResources().getIdentifier("taku_pet", "drawable", getContext().getPackageName());
        if (sharedAtlas == null && atlasId != 0) sharedAtlas = BitmapFactory.decodeResource(getResources(), atlasId, options);
        if (sharedStill == null && stillId != 0) sharedStill = BitmapFactory.decodeResource(getResources(), stillId, options);
        atlas = sharedAtlas;
        still = sharedStill;
    }

    /** Host forwards the existing TaskBroker state; repeated polls do not restart animation. */
    public void setTaskState(String value, String id) {
        String next = value == null || value.isEmpty() ? "idle" : value;
        String nextId = id == null ? "" : id;
        if (next.equals(state) && nextId.equals(taskId)) return;
        boolean newlyCompleted = next.equals("completed") && nextId.equals(taskId)
                && (state.equals("running") || state.equals("queued"));
        state = next;
        taskId = nextId;
        stopMotion();
        frame = restingFrame();
        updateDescription();
        if (newlyCompleted && canAnimate()) begin("ready");
        else scheduleRest();
        invalidate();
    }

    public void setOnPetTap(Runnable callback) { onPetTap = callback; }

    /** Host should resume only the pet on the currently visible page. */
    public void resume() {
        if (lifecycleActive) {
            if (motion.equals("idle") && !handler.hasCallbacks(tick)) scheduleRest();
            return;
        }
        lifecycleActive = true;
        frame = restingFrame();
        scheduleRest();
        invalidate();
    }

    public void pause() {
        if (!lifecycleActive) return;
        lifecycleActive = false;
        pressed = dragging = false;
        handler.removeCallbacks(longPress);
        stopMotion();
        frame = restingFrame();
        invalidate();
    }

    /** Explicit accessibility setting wins over system animator scale when supplied. */
    public void setReducedMotion(boolean reduced) {
        reduceMotionOverride = reduced;
        refreshMotionSetting();
    }

    public void useSystemMotionSetting() {
        reduceMotionOverride = null;
        refreshMotionSetting();
    }

    private boolean reduced() {
        return reduceMotionOverride != null ? reduceMotionOverride : systemMotionReduced || !ValueAnimator.areAnimatorsEnabled();
    }

    private boolean canAnimate() {
        return atlas != null && lifecycleActive && isShown() && hasWindowFocus()
                && getLocalVisibleRect(visible) && !reduced();
    }

    private int restingFrame() {
        if (state.equals("running") || state.equals("queued")) return 12;
        if (state.equals("completed")) return 3;
        if (state.equals("failed") || state.equals("needs_user") || state.equals("offline")) return 13;
        return 0;
    }

    private void updateDescription() {
        String status = state.equals("running") ? "Task running" : state.equals("queued") ? "Task queued"
                : state.equals("completed") ? "Task completed" : state.equals("failed") ? "Task failed. See task details"
                : state.equals("needs_user") ? "Task needs more information" : state.equals("offline") ? "Background service is not running" : "Ready";
        setContentDescription("Taku desktop pet, " + status + ". Tap to greet; long-press to drag within the reserved area");
    }

    private void stopMotion() {
        handler.removeCallbacks(tick);
        motion = "idle";
        step = 0;
    }

    private void scheduleRest() {
        handler.removeCallbacks(tick);
        if (!canAnimate() || pressed) return;
        // Failures stay still; the visible task message explains what went wrong.
        if (state.equals("failed") || state.equals("needs_user") || state.equals("offline")) return;
        if (state.equals("running") || state.equals("queued")) {
            nextIdleAt = SystemClock.uptimeMillis() + 2200;
        } else {
            int min = tokens.integer(R.integer.ag_motion_blink_interval_min);
            int max = tokens.integer(R.integer.ag_motion_blink_interval_max);
            nextIdleAt = SystemClock.uptimeMillis() + min + random.nextInt(Math.max(1, max - min + 1));
        }
        handler.postDelayed(tick, Math.max(1, nextIdleAt - SystemClock.uptimeMillis()));
    }

    private void begin(String nextMotion) {
        stopMotion();
        motion = nextMotion;
        motionStarted = SystemClock.uptimeMillis();
        step = 0;
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!canAnimate() || pressed) { stopMotion(); return; }
            if (motion.equals("idle")) {
                if (state.equals("running") || state.equals("queued")) begin("thinking");
                else begin(random.nextInt(3) == 0 && travelRange() > tokens.rawDp(4) ? "walk" : "blink");
                return;
            }
            long age = SystemClock.uptimeMillis() - motionStarted;
            int duration;
            int delay;
            switch (motion) {
                case "blink": frame = step == 0 ? 1 : 0; duration = 180; delay = 90; break;
                case "walk": frame = 4 + (step % 4); duration = 960; delay = 120; break;
                case "wave": frame = WAVE_FRAMES[Math.min(step, 5)]; duration = 720; delay = 120; break;
                case "thinking": frame = step % 2 == 0 ? 12 : 13; duration = 720; delay = 360; break;
                case "ready": frame = 14; duration = tokens.integer(R.integer.ag_motion_ready_max); delay = 40; break;
                default: frame = restingFrame(); duration = 0; delay = 120;
            }
            if (age >= duration) {
                if (motion.equals("walk")) centerX = clamp(centerX + walkDirection() * Math.min(tokens.rawDp(8), travelRange() / 2), minCenterX(), maxCenterX());
                stopMotion();
                frame = restingFrame();
                scheduleRest();
            } else { step++; handler.postDelayed(this, delay); }
            invalidate();
        }
    };

    private int walkDirection() { return centerX >= getWidth() / 2f ? -1 : 1; }
    private float spriteSize() { return Math.min(tokens.px(R.dimen.ag_size_companion_preferred), Math.min(getWidth(), getHeight())); }
    private float minCenterX() { return spriteSize() / 2; }
    private float maxCenterX() { return getWidth() - spriteSize() / 2; }
    private float travelRange() { return Math.max(0, maxCenterX() - minCenterX()); }
    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (Float.isNaN(centerX)) centerX = width / 2f;
        if (Float.isNaN(centerY)) centerY = height / 2f;
        centerX = clamp(centerX, minCenterX(), maxCenterX());
        centerY = clamp(centerY, spriteSize() / 2, height - spriteSize() / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap bitmap = atlas != null ? atlas : still;
        if (bitmap == null) return;
        float size = spriteSize(), x = centerX, y = centerY;
        long age = SystemClock.uptimeMillis() - motionStarted;
        if (!reduced() && motion.equals("walk")) x += walkDirection() * Math.min(tokens.rawDp(8), travelRange() / 2) * Math.min(1f, age / 960f);
        if (!reduced() && motion.equals("ready")) y -= tokens.rawDp(4) * (float)Math.sin(Math.PI * Math.min(1f, age / Math.max(1, tokens.integer(R.integer.ag_motion_ready_max))));
        x = clamp(x, size / 2, getWidth() - size / 2);
        y = clamp(y, size / 2, getHeight() - size / 2);
        destination.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
        if (atlas != null) {
            int cellWidth = bitmap.getWidth() / 4, cellHeight = bitmap.getHeight() / 4;
            int column = frame % 4, row = frame / 4;
            source.set(column * cellWidth, row * cellHeight, (column + 1) * cellWidth, (row + 1) * cellHeight);
        } else source.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        canvas.drawBitmap(bitmap, source, destination, paint);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (canAnimate()) begin("wave");
        else { frame = 8; invalidate(); }
        if (onPetTap != null) onPetTap.run();
        return true;
    }

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (!pressed || !canAnimate() && !lifecycleActive) return;
            dragging = true;
            stopMotion();
            frame = 13;
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
    };

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true; dragging = false;
                downX = event.getX(); downY = event.getY(); startX = centerX; startY = centerY;
                stopMotion();
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (dragging) {
                    centerX = clamp(startX + dx, minCenterX(), maxCenterX());
                    centerY = clamp(startY + dy, spriteSize() / 2, getHeight() - spriteSize() / 2);
                    frame = 4 + ((int)(Math.abs(dx) / Math.max(1, tokens.rawDp(3))) % 4);
                    invalidate();
                } else if (Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) handler.removeCallbacks(longPress);
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                boolean wasDragging = dragging;
                pressed = dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (wasDragging) { frame = restingFrame(); scheduleRest(); invalidate(); }
                else performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                pressed = dragging = false;
                frame = restingFrame(); scheduleRest(); invalidate();
                return true;
            default: return super.onTouchEvent(event);
        }
    }

    private void refreshMotionSetting() {
        systemMotionReduced = Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0;
        stopMotion(); frame = restingFrame(); scheduleRest(); invalidate();
    }
    private final ContentObserver motionObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { refreshMotionSetting(); }
    };
    private final ViewTreeObserver.OnScrollChangedListener scrollObserver = () -> {
        if (!initialized || !lifecycleActive) return;
        if (!getLocalVisibleRect(visible)) {
            handler.removeCallbacks(longPress);
            pressed = dragging = false;
            stopMotion();
        } else if (motion.equals("idle") && !handler.hasCallbacks(tick)) scheduleRest();
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!observing) {
            getContext().getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
            observing = true;
        }
        getViewTreeObserver().addOnScrollChangedListener(scrollObserver);
        scheduleRest();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(longPress); stopMotion();
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnScrollChangedListener(scrollObserver);
        if (observing) { getContext().getContentResolver().unregisterContentObserver(motionObserver); observing = false; }
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!initialized) return;
        if (!isShown()) { handler.removeCallbacks(longPress); pressed = dragging = false; stopMotion(); }
        else scheduleRest();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!initialized) return;
        if (focused) scheduleRest(); else { handler.removeCallbacks(longPress); pressed = dragging = false; stopMotion(); }
    }
}
