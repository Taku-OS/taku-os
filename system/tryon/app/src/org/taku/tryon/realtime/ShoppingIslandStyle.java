// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

/** Figma mobile tokens. The physical camera occupies the reserved middle node, never text. */
final class ShoppingIslandStyle {
    static final int TEXT = 0xebffffff, MUTED = 0xccffffff, GLASS = 0xeb000000;
    private static Typeface font;
    private ShoppingIslandStyle() {}
    static Typeface font(Context context) {
        if (font == null) font = Typeface.create("sans-serif", Typeface.NORMAL);
        return font;
    }
    static void text(TextView view, int size) {
        view.setTypeface(Typeface.create(font(view.getContext()), 400, false));
        view.setTextSize(size); view.setTextColor(TEXT); view.setIncludeFontPadding(false);
        view.setLetterSpacing(-.01f); view.setLineSpacing(0, 1.3f);
    }
    static final class Capsule extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;
        private int left, right, hole;
        Capsule(float density) { this.density = density; paint.setColor(Color.BLACK); }
        void geometry(int left, int right, int hole) { this.left=left; this.right=right; this.hole=hole; invalidateSelf(); }
        @Override public void draw(Canvas canvas) {
            float h=getBounds().height(), cy=h/2f;
            if (hole <= 0) { canvas.drawRoundRect(0,0,getBounds().width(),h,h/2,h/2,paint); return; }
            // Same 32dp nodes and exact metaball neck as Home's DynamicIsland.
            float cx=52*density, r=16*density;
            canvas.drawCircle(cx-36*density,cy,r,paint);
            canvas.drawCircle(cx,cy,12*density,paint);
            canvas.drawRoundRect(cx+20*density,cy-r,getBounds().width(),cy+r,r,r,paint);
            neck(canvas,cx-24*density,cy,false);
            neck(canvas,cx+24*density,cy,true);
        }
        private void neck(Canvas canvas,float x,float cy,boolean mirror) {
            path.reset();path.moveTo(16.0743f,1.72239f);
            path.cubicTo(10.9102f,6.34134f,6.1586f,6.88552f,0,0);
            path.lineTo(0,21.3333f);
            path.cubicTo(6.1586f,14.4478f,10.9102f,14.992f,16.0743f,19.6109f);
            path.close();
            canvas.save();canvas.translate(x,cy-10.66665f*density);
            canvas.scale(mirror?-density:density,density);canvas.drawPath(path,paint);canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha);invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter);invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    static final class Dots extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Dots(Context context) { super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float d=getResources().getDisplayMetrics().density, step=6*d, radius=2*d;
            float x=getWidth()/2f-step,y=getHeight()/2f-step;
            float phase=(SystemClock.uptimeMillis()%100000)/1000f*2.4f;
            float[][] opacity={{.92f,.92f,.92f},{.48f,.8f,.48f},{.24f,.64f,.24f}};
            for(int row=0;row<3;row++)for(int col=0;col<3;col++) {
                float u=((col-phase)%3+3)%3;int base=(int)u;float fraction=u-base;
                int alpha=Math.round(255*(opacity[base][row]+(opacity[(base+1)%3][row]-opacity[base][row])*fraction));
                paint.setColor(Color.argb(alpha,255,255,255));canvas.drawCircle(x+col*step,y+row*step,radius,paint);
            }
            if(isAttachedToWindow()&&isShown())postInvalidateOnAnimation();
        }
    }
}
