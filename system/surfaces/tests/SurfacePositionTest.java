package org.agentos.surfaces;

/** Geometry regressions independent of Android and of the message contract. */
public final class SurfacePositionTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Position check "+assertions); }
    public static void main(String[] args) {
        var screen=new SurfacePosition.Area(16,40,984,1960);
        var keyboard=new SurfacePosition.Area(16,40,984,1100);
        var initial=SurfacePosition.place(null,screen,screen,292,56,true);
        check(initial.equals(new SurfacePosition.Point(354,1904)));
        check(SurfacePosition.place(null,screen,keyboard,292,56,true).y()==1044);
        check(SurfacePosition.place(null,screen,screen,292,56,false).y()==40);
        var moved=new SurfacePosition.Point(90,640);
        var saved=SurfacePosition.remember(moved,screen,292,56);
        check(SurfacePosition.place(saved,screen,screen,292,56,true).equals(moved));
        check(SurfacePosition.place(saved,screen,screen,292,56,false).equals(moved));
        var card=SurfacePosition.place(saved,screen,screen,360,400,true);
        check(card.x()+180==moved.x()+146 && card.y()+200==moved.y()+28);
        check(SurfacePosition.clamp(new SurfacePosition.Point(-500,9999),screen,360,400)
                .equals(new SurfacePosition.Point(16,1560)));
        var low=SurfacePosition.remember(initial,screen,292,56);
        check(SurfacePosition.place(low,screen,keyboard,360,400,true).y()==700);
        check(SurfacePosition.place(low,screen,screen,292,56,true).equals(initial)); // IME never rewrites the anchor.
        var landscape=new SurfacePosition.Area(40,16,1960,984);
        var rotated=SurfacePosition.place(saved,landscape,landscape,360,400,true);
        check(rotated.x()>=40 && rotated.x()+360<=1960 && rotated.y()>=16 && rotated.y()+400<=984);
        check(SurfacePosition.place(saved,screen,screen,292,56,true).equals(moved)); // Rotation does not round-trip pixels.
        var tiny=new SurfacePosition.Area(10,20,10,20);
        check(SurfacePosition.remember(new SurfacePosition.Point(10,20),tiny,0,0).equals(new SurfacePosition.Anchor(.5f,.5f)));
        check(SurfacePosition.clamp(new SurfacePosition.Point(-10,-20),tiny,360,400).equals(new SurfacePosition.Point(10,20)));
        for (float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.1f,1.1f}) {
            try { new SurfacePosition.Anchor(invalid,.5f); throw new AssertionError("Invalid position accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        System.out.println(assertions+" surface position assertions passed");
    }
}
