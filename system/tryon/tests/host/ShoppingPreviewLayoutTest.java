// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Video-reference sizing, saved floating placement, and contain on real/small displays. */
public final class ShoppingPreviewLayoutTest {
    private static int checks;
    public static void main(String[] args) {
        var pixel = new ShoppingIslandGeometry.Display(1080, 2424, 0, 173, 0, 64, 497, 44, 582, 129);
        float density = 2.625f;
        var compact = layout(pixel, density, ShoppingIslandGeometry.Mode.COMPACT, 0, false, 0, 0);
        check(compact.window().y()+compact.window().height()/2 == 86, "system island input is centered on the physical lens");
        check(compact.holeWidth() == Math.round(24*density), "system host reserves the real camera node");
        check(compact.window().right() <= pixel.width()-Math.round(128*density), "system island leaves VPN, Wi-Fi and battery lane visible");
        check(compact.window().x() >= Math.round(112*density),"system island leaves clock/notification lane visible");
        check(compact.rightLobe()>=Math.round(32*density),"right lobe retains a readable state node");
        var preview = layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, 0, false, 0, 0);
        check(preview.window().width() == Math.round(pixel.width() * .45f), "default viewport uses 45 percent of the LIVE screen");
        check(preview.window().x()+preview.window().width()/2 == 539, "first preview grows around the physical lens center");
        check(preview.window().y() == 86-Math.round(16*density), "preview top stays at the camera node top");
        check(preview.video().y()+preview.window().y() >= pixel.insetTop()+Math.round(8*density), "black header keeps video below camera and status row");
        check(Math.abs(preview.video().height()-preview.window().width()*1.2f)<2, "floating video follows portrait reference");
        var full = layout(pixel, density, ShoppingIslandGeometry.Mode.FULLSCREEN, 0, false, 0, 0);
        check(full.window().y()==0 && full.window().height()==2360, "full backdrop clears bottom navigation");
        check(full.video().y()>=pixel.insetTop(), "full media starts below system status bar");
        check(full.video().x()==Math.round(12*density), "full media has reference side margins");
        check(full.video().right()<=full.window().width()-Math.round(12*density), "right safe margin matches left");
        var fullControls=ShoppingPreviewLayout.shoppingControls(full,density);
        check(fullControls.y()>=pixel.insetTop(),"fullscreen controls clear the camera/status safe area");
        check(fullControls.bottom()+Math.round(12*density)<=full.video().y(),"fullscreen controls and video have a full 12dp gap");
        check(fullControls.right()==full.window().width()-Math.round(12*density),"fullscreen controls align to the window right margin");
        check(fullControls.height()==Math.round(44*density),"fullscreen controls retain 44dp touch targets");
        for(float ratio:new float[]{9f/16f,3f/4f,1f,16f/9f}){
            float fittedHeight=Math.min(full.video().height(),full.video().width()/ratio);
            float imageTop=full.video().y()+(full.video().height()-fittedHeight)/2;
            check(fullControls.bottom()<imageTop,"controls remain above fitted image for ratio "+ratio);
        }
        var miniControls=ShoppingPreviewLayout.shoppingControls(preview,density);
        check(miniControls.y()==preview.video().y()+Math.round(12*density),"small-window controls sit wholly inside the media with 12dp inset");
        check(miniControls.right()==preview.video().right()-Math.round(12*density),"small-window right inset matches top");
        check(full.video().bottom()<=full.window().height(), "portrait result stays inside viewport");
        var tiny = layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, 1, false, 0, 0);
        check(tiny.window().width() == Math.round(144 * density), "pinch retains an accessible corner target");
        var huge = layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, Integer.MAX_VALUE, false, 0, 0);
        check(huge.window().right() <= pixel.width() - Math.round(8 * density), "oversized gesture remains within screen margin");
        var moved = layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, 800, true, 200, 600);
        check(moved.window().y() == 600, "video dragging remains available");
        check(moved.video().y()==0 && moved.headerHeight()==0,"dragged window sheds the fixed camera safety reserve");
        var restored = layout(pixel, density, ShoppingIslandGeometry.Mode.COMPACT, 800, true, 200, 600);
        check(restored.window().equals(compact.window()), "moved window collapses back to reachable top anchor");
        var floating = new ShoppingPreviewLayout.FloatingFrame();
        floating.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW);
        floating.resize(600); floating.move(380, 880);
        var saved = floating.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW);
        floating.layout(pixel, density, ShoppingIslandGeometry.Mode.FULLSCREEN);
        check(floating.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW).equals(saved), "Window returns to the dragged size and position");
        floating.layout(pixel, density, ShoppingIslandGeometry.Mode.COMPACT);
        check(floating.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW).equals(saved), "island restore keeps the floating frame");
        var cosplay = ShoppingPreviewLayout.applicationOverlayLayout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, 0, false, 0, 0, true);
        check(cosplay.equals(preview), "cosplay uses the same camera-anchored black portrait window");
        var cosplayCompact = ShoppingPreviewLayout.applicationOverlayLayout(pixel, density, ShoppingIslandGeometry.Mode.COMPACT, 0, false, 0, 0, true);
        check(cosplayCompact.equals(compact), "cosplay preparing island reserves the same physical lens and status lanes");
        var cosplayControls=ShoppingPreviewLayout.shoppingControls(cosplay,density);
        check(cosplayControls.equals(miniControls), "cosplay controls overlay the media without a white header or footer");
        var cosplayFrame = new ShoppingPreviewLayout.FloatingFrame();
        cosplayFrame.resize(580); cosplayFrame.move(390, 1150);
        var cosplaySaved = cosplayFrame.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, true);
        var cosplayFull = cosplayFrame.layout(pixel, density, ShoppingIslandGeometry.Mode.FULLSCREEN, true);
        check(ShoppingPreviewLayout.shoppingControls(cosplayFull,density).bottom() < cosplayFull.video().y(),
                "full cosplay keeps controls above the contained video");
        check(cosplayFrame.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, true).equals(cosplaySaved),
                "cosplay returns from fullscreen to the same dragged and resized card");
        cosplayFrame.layout(pixel, density, ShoppingIslandGeometry.Mode.COMPACT, true);
        check(cosplayFrame.layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, true).equals(cosplaySaved),
                "cosplay restore preserves its floating placement");
        var bottomDrag = layout(pixel, density, ShoppingIslandGeometry.Mode.PREVIEW, 600, true, 100, 10000);
        check(bottomDrag.window().bottom() <= pixel.height() - pixel.insetBottom(), "dragging down keeps the whole card visible");
        for (int width : new int[]{1, 30, 180, 280, 320, 412, 600, 1080})
            for (int height : new int[]{1, 80, 240, 360, 917, 2424})
                for (var mode : ShoppingIslandGeometry.Mode.values()) {
                    var d = new ShoppingIslandGeometry.Display(width, height, 0, Math.min(64, height / 6), 0, Math.min(24, height / 8), 0, 0, 0, 0);
                    var result = layout(d, 1, mode, 0, true, -200, height + 999);
                    var w = result.window(); var v = result.video();
                    check(w.width() > 0 && w.height() > 0 && w.x() >= 0 && w.y() >= 0 && w.right() <= width && w.bottom() <= height,
                            "all overlay windows stay in small/rotated/transient bounds: " + width + "x" + height + " " + mode);
                    check(v.width() > 0 && v.height() > 0 && v.x() >= 0 && v.y() >= 0 && v.right() <= w.width() && v.bottom() <= w.height(),
                            "camera subtree gets positive contained dimensions: " + width + "x" + height + " " + mode);
                    if(mode!=ShoppingIslandGeometry.Mode.COMPACT){
                        var controls=ShoppingPreviewLayout.shoppingControls(result,1);
                        check(controls.x()>=0&&controls.y()>=0&&controls.right()<=w.width()&&controls.bottom()<=w.height(),
                            "controls remain inside resized/rotated window: "+width+"x"+height+" "+mode);
                    }
                    var cosplayResult = ShoppingPreviewLayout.applicationOverlayLayout(d, 1, mode, 0, true, -200, height + 999, true);
                    var cw = cosplayResult.window(); var cv = cosplayResult.video();
                    check(cw.width() > 0 && cw.height() > 0 && cw.x() >= 0 && cw.y() >= 0 && cw.right() <= width && cw.bottom() <= height,
                            "cosplay card remains reachable after rotation or transient sizing: " + width + "x" + height + " " + mode);
                    check(cv.width() > 0 && cv.height() > 0 && cv.x() >= 0 && cv.y() >= 0 && cv.right() <= cw.width() && cv.bottom() <= cw.height(),
                            "cosplay camera subtree remains positive and contained: " + width + "x" + height + " " + mode);
                }
        check(ShoppingPreviewLayout.scaledWidth(350, 1.2f) == 420, "two-finger outward gesture grows only presentation width");
        check(ShoppingPreviewLayout.scaledWidth(350, .8f) == 280, "two-finger inward gesture shrinks width");
        check(ShoppingPreviewLayout.scaledWidth(350, Float.NaN) == 350, "invalid span never corrupts width");
        check(ShoppingPreviewLayout.scaledWidth(350, Float.POSITIVE_INFINITY) == 350, "infinite span is ignored");
        check(ShoppingPreviewLayout.scaledWidth(350, 0) == 350, "zero span is ignored");
        System.out.println("ShoppingPreviewLayoutTest: " + checks + " checks passed");
    }
    private static ShoppingIslandGeometry.Layout layout(ShoppingIslandGeometry.Display d, float density,
            ShoppingIslandGeometry.Mode mode, int width, boolean moved, int x, int y) {
        return ShoppingPreviewLayout.applicationOverlayLayout(d, density, mode, width, moved, x, y);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
