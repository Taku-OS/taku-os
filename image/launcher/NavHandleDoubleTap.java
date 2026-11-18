/*
 * Copyright (C) 2026 Taku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep;

import static com.android.internal.app.AssistUtils.INVOCATION_TYPE_GESTURE;
import static com.android.internal.app.AssistUtils.INVOCATION_TYPE_KEY;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * Taku: double tap on the gesture navigation handle opens the assistant (the text composer).
 * It watches the input monitor's events passively and never consumes them. A single tap on
 * the handle still does nothing, and swipes cancel tracking before they become gestures.
 */
final class NavHandleDoubleTap {
    /** Lets the assistant tell this entry point apart from the other gestures. */
    static final String EXTRA_TRIGGER = "org.agentos.assistant.extra.TRIGGER";
    static final String TRIGGER = "nav_handle_double_tap";

    private boolean mTracking;
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private long mLastUpTime = -1;
    private float mLastUpX;
    private float mLastUpY;

    /** A new gesture starts; [eligible] is false outside the handle region or gesture nav. */
    void onDown(Context context, MotionEvent event, boolean eligible) {
        ViewConfiguration config = ViewConfiguration.get(context);
        if (mLastUpTime >= 0) {
            float slop = config.getScaledDoubleTapSlop();
            if (!eligible
                    || event.getEventTime() - mLastUpTime > ViewConfiguration.getDoubleTapTimeout()
                    || distanceSquared(event.getRawX() - mLastUpX, event.getRawY() - mLastUpY) > slop * slop) {
                mLastUpTime = -1;
            }
        }
        mTracking = eligible && !TakuNavHandleBridge.locked(context);
        mDownX = event.getRawX();
        mDownY = event.getRawY();
        mDownTime = event.getEventTime();
    }

    /** Feeds the rest of the gesture; true when this event completes a double tap. */
    boolean onEvent(Context context, MotionEvent event) {
        if (!mTracking) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float slop = ViewConfiguration.get(context).getScaledTouchSlop();
                if (distanceSquared(event.getRawX() - mDownX, event.getRawY() - mDownY) > slop * slop) reset();
                return false;
            }
            case MotionEvent.ACTION_UP: {
                mTracking = false;
                if (event.getEventTime() - mDownTime >= ViewConfiguration.getLongPressTimeout()) {
                    mLastUpTime = -1;
                    return false;
                }
                if (mLastUpTime >= 0) {
                    mLastUpTime = -1;
                    return true;
                }
                mLastUpTime = event.getEventTime();
                mLastUpX = event.getRawX();
                mLastUpY = event.getRawY();
                return false;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                reset();
                return false;
            default:
                return false;
        }
    }

    /** Arguments for SystemUiProxy#startAssistant. */
    static Bundle assistantArgs() {
        Bundle args = new Bundle();
        args.putInt(INVOCATION_TYPE_KEY, INVOCATION_TYPE_GESTURE);
        args.putString(EXTRA_TRIGGER, TRIGGER);
        return args;
    }

    private void reset() {
        mTracking = false;
        mLastUpTime = -1;
    }

    private static float distanceSquared(float dx, float dy) {
        return dx * dx + dy * dy;
    }
}
