package org.agentos.surfaces;

/** Presentation-only geometry. The saved center uses the stable, keyboard-free safe area. */
final class SurfacePosition {
    record Area(int left,int top,int right,int bottom) {
        Area {
            if (right<left || bottom<top) throw new IllegalArgumentException("Invalid safe area");
        }
        int width() { return right-left; }
        int height() { return bottom-top; }
    }
    record Point(int x,int y) {}
    record Anchor(float x,float y) {
        Anchor {
            if (!Float.isFinite(x) || !Float.isFinite(y) || x<0 || x>1 || y<0 || y>1)
                throw new IllegalArgumentException("Invalid position");
        }
    }
    static Point place(Anchor anchor,Area stable,Area visible,int width,int height,boolean bottom) {
        int x=anchor==null?stable.left()+(stable.width()-width)/2:
                Math.round(stable.left()+anchor.x()*stable.width()-width/2f);
        int y=anchor==null?(bottom?visible.bottom()-height:visible.top()):
                Math.round(stable.top()+anchor.y()*stable.height()-height/2f);
        return clamp(new Point(x,y),visible,width,height);
    }
    static Point clamp(Point point,Area area,int width,int height) {
        return new Point(Math.max(area.left(),Math.min(point.x(),area.right()-width)),
                Math.max(area.top(),Math.min(point.y(),area.bottom()-height)));
    }
    static Anchor remember(Point point,Area stable,int width,int height) {
        return new Anchor(fraction(point.x()+width/2f-stable.left(),stable.width()),
                fraction(point.y()+height/2f-stable.top(),stable.height()));
    }
    private static float fraction(float value,int extent) {
        return extent==0?.5f:Math.max(0,Math.min(1,value/extent));
    }
    private SurfacePosition() {}
}
