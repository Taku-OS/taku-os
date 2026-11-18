package org.agentos.assistant;

import android.view.MotionEvent;
import android.view.View;

/** Handle-only drag; message scrolling and text editing never compete with dismissal. */
final class DrawerDragBehavior implements View.OnTouchListener {
    private final View sheet;
    private final Runnable dismiss;
    private final float threshold;
    private float startY;

    DrawerDragBehavior(View sheet, float threshold, Runnable dismiss) {
        this.sheet = sheet;
        this.threshold = threshold;
        this.dismiss = dismiss;
    }

    @Override
    public boolean onTouch(View handle, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sheet.animate().cancel();
                startY = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                sheet.setTranslationY(Math.max(0, event.getRawY() - startY));
                return true;
            case MotionEvent.ACTION_UP:
                if (sheet.getTranslationY() > threshold) {
                    sheet.animate()
                            .translationY(sheet.getHeight())
                            .setDuration(180)
                            .withEndAction(dismiss)
                            .start();
                } else {
                    sheet.animate().translationY(0).setDuration(180).start();
                    handle.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                sheet.animate().translationY(0).setDuration(180).start();
                return true;
            default:
                return false;
        }
    }
}
