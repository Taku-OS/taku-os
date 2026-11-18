package org.agentos.surfaces;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Content-measured native rows. Long labels wrap instead of shrinking or ellipsizing. */
final class SurfaceFlow extends ViewGroup {
    private final int gap,minimumWidth;
    private List<SurfaceLayout.Row> rows=List.of();
    SurfaceFlow(Context context,int gap,int minimumWidth) {
        super(context); this.gap=gap; this.minimumWidth=minimumWidth;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec);
        int available=Math.max(1,width-getPaddingLeft()-getPaddingRight());
        List<Integer> wanted=new ArrayList<>();
        for (int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            wanted.add(Math.max(minimumWidth,child.getMeasuredWidth()));
        }
        rows=SurfaceLayout.rows(wanted,available,gap);
        int height=getPaddingTop()+getPaddingBottom();
        for (SurfaceLayout.Row row:rows) {
            int tallest=0;
            for (int i=0;i<row.widths().size();i++) {
                View child=getChildAt(row.start()+i);
                child.measure(MeasureSpec.makeMeasureSpec(row.widths().get(i),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                tallest=Math.max(tallest,child.getMeasuredHeight());
            }
            height+=tallest;
        }
        height+=Math.max(0,rows.size()-1)*gap;
        setMeasuredDimension(width,resolveSize(height,heightSpec));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        int top=getPaddingTop(); boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
        for (SurfaceLayout.Row row:rows) {
            int left=rtl?getWidth()-getPaddingRight():getPaddingLeft(),tallest=0;
            for (int i=0;i<row.widths().size();i++) {
                View child=getChildAt(row.start()+i); int width=row.widths().get(i),height=child.getMeasuredHeight();
                child.layout(rtl?left-width:left,top,rtl?left:left+width,top+height);
                left+=rtl?-width-gap:width+gap; tallest=Math.max(tallest,height);
            }
            top+=tallest+gap;
        }
    }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT); }
}
