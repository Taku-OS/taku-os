// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Overlay bounds only; these host assertions do not claim Android camera/gesture acceptance. */
public final class ShoppingPreviewGeometryTest {
    private static int checks;
    public static void main(String[] args) {
        var initial = size(412, 900, 196);
        check(initial.width() == 196 && initial.height() == 309, "initial small overlay size");
        var expanded = size(412, 900, 340);
        check(expanded.width() == 340 && expanded.height() == 501, "expanded overlay size");
        var between = size(412, 900, 257);
        check(between.width() == 257 && between.height() == 390, "continuous intermediate width retained");
        check(size(412, 900, 100).width() == 196, "four controls retain minimum touch width");
        check(size(412, 900, 9999).width() == 388, "window cannot grow past horizontal safe space");
        var landscape = size(900, 412, 900);
        check(landscape.height() <= 332 && landscape.width() <= 213, "height budget limits width during rotation");
        var tiny = size(100, 100, 340);
        check(tiny.width() > 0 && tiny.width() <= 76 && tiny.height() <= 20, "tiny surfaces stay bounded");
        var zero = size(0, 0, 340);
        check(zero.width() == 1 && zero.height() == 1, "invalid temporary metrics cannot create negative dimensions");
        check(ShoppingPreviewGeometry.resizedWidth(196, 37, 0) == 233, "horizontal drag continuously grows");
        check(ShoppingPreviewGeometry.resizedWidth(300, -43, 0) == 257, "horizontal drag continuously shrinks");
        check(ShoppingPreviewGeometry.resizedWidth(196, 0, 80) == 256, "vertical drag grows proportionally");
        check(ShoppingPreviewGeometry.resizedWidth(300, 0, -80) == 240, "vertical drag shrinks proportionally");
        check(ShoppingPreviewGeometry.resizedWidth(196, 60, 80) == 256, "diagonal movement applies one aspect-preserving width delta");
        check(ShoppingPreviewGeometry.resizedWidth(196, -9999, -9999) == 1, "extreme shrink cannot produce negative width");
        for (int width = 196; width <= 388; width++) {
            var result = size(412, 900, width);
            check(result.width() == width, "every intermediate width remains available");
            check(result.height() >= 48 && result.height() <= 820, "intermediate height bounded");
        }
        System.out.println("ShoppingPreviewGeometryTest: " + checks + " checks passed");
    }
    private static ShoppingPreviewGeometry.Size size(int screenWidth, int screenHeight, int width) {
        return ShoppingPreviewGeometry.size(screenWidth, screenHeight, width, 196, 24, 80, 48);
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
