// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class OutfitPickGeometryTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static void point(OutfitPickGeometry.Fit fit, double x, double y, int expectedX, int expectedY) {
        OutfitPickGeometry.Point point = OutfitPickGeometry.pick(fit,x,y);
        check(point != null && point.x() == expectedX && point.y() == expectedY, "Wrong normalized clothing point");
    }
    private static void ignored(OutfitPickGeometry.Fit fit, double x, double y) {
        check(OutfitPickGeometry.pick(fit,x,y) == null, "Letterbox or border selected clothing");
    }
    public static void main(String[] args) {
        var portrait = OutfitPickGeometry.fit(1080,1920,600,600,0);
        check(portrait != null && portrait.left() == 131.25 && portrait.top() == 0
            && portrait.right() == 468.75 && portrait.bottom() == 600, "Portrait letterbox changed");
        point(portrait,300,300,500,500);
        point(portrait,215.625,150,250,250);
        ignored(portrait,100,300); ignored(portrait,500,300);
        ignored(portrait,131.25,300); ignored(portrait,468.75,300);
        ignored(portrait,300,0); ignored(portrait,300,600);

        var landscape = OutfitPickGeometry.fit(1920,1080,600,600,0);
        check(landscape != null && landscape.top() == 131.25 && landscape.bottom() == 468.75,
            "Landscape letterbox changed");
        point(landscape,300,300,500,500);
        point(landscape,450,384.375,750,750);
        ignored(landscape,300,100); ignored(landscape,300,500);

        var inset = OutfitPickGeometry.fit(400,800,440,840,20);
        check(inset != null && inset.left() == 20 && inset.top() == 20
            && inset.right() == 420 && inset.bottom() == 820, "Preview inset changed");
        point(inset,220,420,500,500);
        ignored(inset,10,420); ignored(inset,220,10);
        ignored(inset,20,420); ignored(inset,420,420);
        ignored(inset,220,20); ignored(inset,220,820);
        ignored(inset,Double.NaN,20); ignored(inset,20,Double.POSITIVE_INFINITY);
        ignored(null,200,200);

        check(OutfitPickGeometry.fit(0,800,440,840,20) == null,"Empty bitmap accepted");
        check(OutfitPickGeometry.fit(400,16385,440,840,20) == null,"Unbounded bitmap accepted");
        check(OutfitPickGeometry.fit(400,800,40,840,20) == null,"Empty inset width accepted");
        check(OutfitPickGeometry.fit(400,800,440,40,20) == null,"Empty inset height accepted");
        check(OutfitPickGeometry.fit(400,800,440,840,-1) == null,"Negative inset accepted");
        var rounding = OutfitPickGeometry.fit(100,100,218,300,0);
        check(rounding != null && rounding.left() >= 0 && rounding.right() <= 218,
            "Floating-point fit escaped the image view");
        point(rounding,109,150,500,500);

        // Resizing changes only the drawn fit. The same normalized point still selects the same source pixels.
        for (int[] size : new int[][]{{1080,2424},{840,1280},{360,640},{1920,1080},{500,500}}) {
            var fit = OutfitPickGeometry.fit(1080,1730,size[0],size[1],12);
            check(fit != null,"Valid resized preview rejected");
            for (int x : new int[]{1,250,500,750,999}) for (int y : new int[]{1,250,500,750,999})
                point(fit,fit.left()+fit.width()*x/1000,fit.top()+fit.height()*y/1000,x,y);
            ignored(fit,fit.left()-1,fit.top()+fit.height()/2);
            ignored(fit,fit.right()+1,fit.top()+fit.height()/2);
        }
        System.out.println(assertions + " outfit picker geometry assertions passed");
    }
}
