package org.agentos.surfaces;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import java.util.function.IntSupplier;

/** Measures actions first; the body scrolls independently above a stable native footer. */
final class SurfaceCard extends LinearLayout {
    private final IntSupplier displayHeight,safeHeight;
    private final int minimumBodyHeight;
    SurfaceCard(Context context,IntSupplier displayHeight,IntSupplier safeHeight,int minimumBodyHeight) {
        super(context); setOrientation(VERTICAL);
        this.displayHeight=displayHeight; this.safeHeight=safeHeight; this.minimumBodyHeight=minimumBodyHeight;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec),contentWidth=Math.max(1,width-getPaddingLeft()-getPaddingRight());
        int childWidth=MeasureSpec.makeMeasureSpec(contentWidth,MeasureSpec.EXACTLY),natural=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
        View header=getChildAt(0),body=getChildAt(1),footer=getChildAt(2);
        header.measure(childWidth,natural); footer.measure(childWidth,natural);
        int safe=safeHeight.getAsInt();
        if (MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED) safe=Math.min(safe,MeasureSpec.getSize(heightSpec));
        int padding=getPaddingTop()+getPaddingBottom();
        SurfaceLayout.Panels panels=SurfaceLayout.panels(displayHeight.getAsInt(),safe,header.getMeasuredHeight(),padding,footer.getMeasuredHeight(),minimumBodyHeight);
        body.measure(childWidth,MeasureSpec.makeMeasureSpec(panels.bodyHeight(),MeasureSpec.AT_MOST));
        footer.measure(childWidth,MeasureSpec.makeMeasureSpec(panels.footerHeight(),MeasureSpec.AT_MOST));
        setMeasuredDimension(width,resolveSize(padding+header.getMeasuredHeight()+body.getMeasuredHeight()+footer.getMeasuredHeight(),heightSpec));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        int top=getPaddingTop(),left=getPaddingLeft(),right=getWidth()-getPaddingRight();
        for (int i=0;i<getChildCount();i++) {
            View child=getChildAt(i); child.layout(left,top,right,top+child.getMeasuredHeight()); top+=child.getMeasuredHeight();
        }
    }
}
