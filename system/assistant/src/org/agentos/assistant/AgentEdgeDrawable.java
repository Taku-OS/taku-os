package org.agentos.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/** A touch-free perimeter light, owned by the system assistant window. */
final class AgentEdgeDrawable extends Drawable implements Animatable {
    private static final int[] COLORS = {
        Color.rgb(255, 155, 67), Color.rgb(255, 218, 148),
        Color.rgb(196, 158, 255), Color.rgb(246, 170, 213), Color.rgb(255, 155, 67)
    };
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path perimeter = new Path(), segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private float length, phase;
    private int opacity = 255;

    AgentEdgeDrawable(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        animator.setDuration(4200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (Float) value.getAnimatedValue();
            invalidateSelf();
        });
    }

    @Override protected void onBoundsChange(Rect bounds) {
        perimeter.reset();
        float inset = 3.5f * density;
        RectF frame = new RectF(bounds);
        frame.inset(inset, inset);
        float radius = Math.min(36 * density, Math.min(frame.width(), frame.height()) / 2);
        perimeter.addRoundRect(frame, radius, radius, Path.Direction.CW);
        measure.setPath(perimeter, true);
        length = measure.getLength();
    }

    private int color(float position) {
        float value = (position - (float) Math.floor(position)) * (COLORS.length - 1);
        int index = Math.min(COLORS.length - 2, (int) value);
        float fraction = value - index;
        int a = COLORS[index], b = COLORS[index + 1];
        return Color.rgb(
            Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * fraction),
            Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * fraction),
            Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction));
    }

    private void stroke(Canvas canvas, float width, int alpha) {
        paint.setStrokeWidth(width * density);
        paint.setAlpha(alpha * opacity / 255);
        canvas.drawPath(segment, paint);
    }

    private void extract(float start, float end) {
        segment.reset();
        if (end <= length) measure.getSegment(start, end, segment, true);
        else {
            measure.getSegment(start, length, segment, true);
            measure.getSegment(0, end - length, segment, true);
        }
    }

    @Override public void draw(Canvas canvas) {
        if (length <= 0) return;
        paint.setColor(COLORS[0]);
        paint.setStrokeWidth(1.25f * density);
        paint.setAlpha(42 * opacity / 255);
        canvas.drawPath(perimeter, paint);
        // Two soft trails travel at constant speed along all four rounded edges.
        int steps = 42;
        float trail = length * .28f, part = trail / steps;
        for (int head = 0; head < 2; head++) {
            for (int i = 0; i < steps; i++) {
                float strength = (i + 1f) / steps;
                float start = ((phase + head * .5f) * length - trail + i * part) % length;
                if (start < 0) start += length;
                extract(start, start + part + .5f * density);
                paint.setColor(color(phase + head * .5f - .22f * (1 - strength)));
                stroke(canvas, 13, Math.round(28 * strength * strength));
                stroke(canvas, 8, Math.round(65 * strength * strength));
                stroke(canvas, 3.25f, Math.round(235 * strength));
            }
        }
    }

    @Override public void start() {
        if (animator.isStarted()) return;
        if (ValueAnimator.areAnimatorsEnabled()) animator.start();
        else invalidateSelf();
    }

    @Override public void stop() {
        animator.cancel();
    }

    @Override public boolean isRunning() { return animator.isRunning(); }
    @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
