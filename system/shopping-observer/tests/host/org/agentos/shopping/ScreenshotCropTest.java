// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class ScreenshotCropTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static void absent(String message, int wl, int wt, int wr, int wb,
                               int il, int it, int ir, int ib, int sw, int sh) {
        check(ScreenshotCrop.map(wl, wt, wr, wb, il, it, ir, ib, sw, sh) == null, message);
    }
    public static void main(String[] args) {
        var pixel = ScreenshotCrop.map(0, 0, 1080, 2424, 0, 638, 1080, 2078, 1080, 2424);
        check(pixel != null && pixel.left() == 0 && pixel.top() == 638 && pixel.right() == 1080
            && pixel.bottom() == 2078, "Pixel detail image mapping changed");
        var scaled = ScreenshotCrop.map(10, 20, 1010, 2020, 110, 220, 610, 1220, 500, 1000);
        check(scaled != null && scaled.left() == 50 && scaled.top() == 100 && scaled.right() == 300
            && scaled.bottom() == 600, "Window origin/half scale mapping failed");
        var fractional = ScreenshotCrop.map(0, 0, 1000, 1000, 101, 101, 899, 899, 333, 333);
        check(fractional != null && fractional.left() == 33 && fractional.top() == 33
            && fractional.right() == 300 && fractional.bottom() == 300, "Crop must floor/ceil boundaries");
        absent("Image outside left was clamped", 0,0,1000,1000, -1,100,500,600,1000,1000);
        absent("Image outside bottom was clamped", 0,0,1000,1000, 100,100,500,1001,1000,1000);
        absent("Mismatched aspect scaling accepted", 0,0,1000,1000,100,100,800,800,1000,1040);
        absent("Too-small main image accepted", 0,0,1000,1000,100,100,227,300,1000,1000);
        absent("Bad window accepted", 0,0,0,1000,0,0,200,200,1000,1000);
        absent("Mapped right edge escaped shot", 0,0,1000,1000,800,100,1000,900,799,1000);
        System.out.println(assertions + " screenshot crop assertions passed");
    }
}
