package org.agentos.surfaces;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/** Intercepts only a deliberate drag; ordinary child button taps keep their normal behavior. */
final class SurfaceDragHandle extends LinearLayout {
    interface Listener {
        void start();
        void move(float dx,float dy);
        void finish(boolean cancelled);
    }
    private final Listener listener;
    private final int slop;
    private float downX,downY;
    private boolean dragging,cancelled;
    SurfaceDragHandle(Context context,Listener listener) {
        super(context); this.listener=listener; slop=ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX=event.getRawX(); downY=event.getRawY(); dragging=false; cancelled=false;
            }
            case MotionEvent.ACTION_POINTER_DOWN -> cancelDrag();
            case MotionEvent.ACTION_MOVE -> {
                startIfNeeded(event);
                if (dragging) listener.move(event.getRawX()-downX,event.getRawY()-downY);
            }
            default -> { }
        }
        return dragging || cancelled || super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN) cancelDrag();
        // If the handle itself consumed DOWN, ViewGroup no longer calls onInterceptTouchEvent.
        if (event.getActionMasked()==MotionEvent.ACTION_MOVE) startIfNeeded(event);
        if (dragging) {
            if (event.getActionMasked()==MotionEvent.ACTION_MOVE || event.getActionMasked()==MotionEvent.ACTION_UP)
                listener.move(event.getRawX()-downX,event.getRawY()-downY);
            if (event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                listener.finish(event.getActionMasked()==MotionEvent.ACTION_CANCEL); dragging=false;
            }
            return true;
        }
        return cancelled || super.onTouchEvent(event);
    }
    private void startIfNeeded(MotionEvent event) {
        if (!cancelled && !dragging && Math.hypot(event.getRawX()-downX,event.getRawY()-downY)>slop) {
            dragging=true; cancelLongPress(); setPressed(false);
            performHapticFeedback(HapticFeedbackConstants.DRAG_START); listener.start();
        }
    }
    private void cancelDrag() {
        if (dragging) listener.finish(true);
        dragging=false; cancelled=true; cancelLongPress(); setPressed(false);
    }
}
