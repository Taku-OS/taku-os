package org.agentos.assistant;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Ephemeral, non-interactive system layer so the perimeter survives the IME. */
final class AgentEdgeWindow {
    private final WindowManager manager;
    private final View surface;
    private final AgentEdgeDrawable light;
    private boolean attached;

    AgentEdgeWindow(Context context) {
        Display display = context.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        Context visual = context.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY, null);
        manager = visual.getSystemService(WindowManager.class);
        light = new AgentEdgeDrawable(visual);
        surface = new View(visual);
        surface.setBackground(light);
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        surface.setFocusable(false);
        surface.setClickable(false);
    }

    void show() {
        if (attached) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Keep obscuring opacity below Android's default touch-pass-through limit.
        params.alpha = .75f;
        params.setTitle("Taku Agent perimeter");
        manager.addView(surface, params);
        attached = true;
        light.start();
    }

    void hide() {
        light.stop();
        if (attached) {
            manager.removeViewImmediate(surface);
            attached = false;
        }
    }
}
