package org.agentos.surfaces;

import android.text.Layout;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Short key/value pairs align; long values get the full readable width. */
final class SurfaceField extends LinearLayout {
    private final SurfaceStyle style;
    private final TextView label,value;
    private final boolean forceStack;
    private boolean stacked=true;
    SurfaceField(SurfaceStyle style,String labelText,String valueText,boolean forceStack) {
        super(style.context); this.style=style; this.forceStack=forceStack;
        setOrientation(VERTICAL); setGravity(Gravity.TOP); setPadding(0,style.dp(8),0,style.dp(8));
        label=style.readableText(labelText,12,SurfaceStyle.MUTED,false);
        value=style.readableText(valueText,14,SurfaceStyle.TEXT,true);
        addView(label,new LayoutParams(-1,-2));
        LayoutParams valueParams=new LayoutParams(-1,-2); valueParams.topMargin=style.dp(4); addView(value,valueParams);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=Math.max(1,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        boolean multiline=label.getText().toString().contains("\n") || value.getText().toString().contains("\n");
        boolean next=forceStack || SurfaceLayout.stackField(Layout.getDesiredWidth(label.getText(),label.getPaint()),
                Layout.getDesiredWidth(value.getText(),value.getPaint()),width,style.dp(16),multiline,getResources().getConfiguration().fontScale);
        if (next!=stacked) {
            stacked=next; setOrientation(stacked?VERTICAL:HORIZONTAL);
            label.setLayoutParams(stacked?new LayoutParams(-1,-2):new LayoutParams(0,-2,1));
            LayoutParams params=stacked?new LayoutParams(-1,-2):new LayoutParams(0,-2,2);
            if (stacked) params.topMargin=style.dp(4); else params.setMarginStart(style.dp(16));
            value.setLayoutParams(params); value.setGravity(stacked?Gravity.START:Gravity.END);
        }
        super.onMeasure(widthSpec,heightSpec);
    }
}
