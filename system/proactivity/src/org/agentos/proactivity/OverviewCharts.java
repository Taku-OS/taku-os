package org.agentos.proactivity;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.IntConsumer;
import static org.agentos.proactivity.ProactivityUi.*;

/** Local native charts. Geometry represents retained records; empty series stay empty. */
final class OverviewCharts {
    private static final int LIGHT=0xffc0cbbb,DARK=0xff1c2820;
    static final int[] HEAT={0xff304035,0xff805336,0xffbb612d,0xffed7a2d,0xffffa34d};
    private abstract static class Chart extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final OverviewStats stats;final int height;
        final IntConsumer listener;int selected=23;
        Chart(Context c,OverviewStats stats,int height,IntConsumer listener){super(c);this.stats=stats;this.height=height;this.listener=listener;setFocusable(true);}
        float dp(float n){return n*getResources().getDisplayMetrics().density;}
        float sp(float n){return n*getResources().getDisplayMetrics().scaledDensity;}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize((int)dp(300),w),resolveSize((int)(dp(height)+Math.max(0,sp(12)-dp(12))*2),h));}
        void text(Canvas c,String value,float x,float y,int color,float size,Paint.Align align){paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(sp(size));paint.setColor(color);paint.setTextAlign(align);c.drawText(value,x,y,paint);}
        void line(Canvas c,float x,float y,float xx,float yy,int color,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(width));c.drawLine(x,y,xx,yy,paint);paint.setStyle(Paint.Style.FILL);}
        String hour(int index){return DateTimeFormatter.ofPattern("HH",Locale.ROOT).withZone(stats.zone).format(Instant.ofEpochMilli(stats.at(index)));}
        void select(int hour){selected=Math.max(0,Math.min(23,hour));invalidate();describe();}
        abstract void describe();
        abstract int hit(float x);
        @Override public boolean onTouchEvent(android.view.MotionEvent event){if(event.getAction()==MotionEvent.ACTION_DOWN)return true;if(event.getAction()==MotionEvent.ACTION_UP){listener.accept(hit(event.getX()));performClick();return true;}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onKeyDown(int key,android.view.KeyEvent event){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){listener.accept(Math.max(0,Math.min(23,selected+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1))));return true;}return super.onKeyDown(key,event);}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
        @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){listener.accept(Math.max(0,Math.min(23,selected+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1))));return true;}return super.performAccessibilityAction(action,args);}
    }
    static final class Heatmap extends Chart {
        float left,step;
        private static final String[] LABELS={"Policy","Hints","Viewed","Other"};
        Heatmap(Context c,OverviewStats stats,IntConsumer listener){super(c,stats,154,listener);describe();}
        private int color(int value){return HEAT[value==0?0:value==1?1:value<4?2:value<8?3:4];}
        @Override void describe(){setContentDescription("24-hour activity heatmap. Selected "+hour(selected)+": policy "+stats.hourly[0][selected]+", hints "+stats.hourly[1][selected]+", viewed "+stats.hourly[2][selected]+", other "+stats.hourly[3][selected]+". Move left or right to change hours.");}
        @Override int hit(float x){return Math.max(0,Math.min(23,(int)((x-left)/Math.max(1,step))));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setTextSize(sp(11));left=paint.measureText("Policy")+dp(12);step=(getWidth()-left-dp(2))/24f;
            float top=dp(9),cellHeight=dp(18),rowStep=dp(25),gap=Math.min(dp(3),step*.24f);
            for(int row=0;row<4;row++){
                text(c,LABELS[row],0,top+row*rowStep+cellHeight*.5f+sp(3.5f),LIGHT,11,Paint.Align.LEFT);
                for(int hour=0;hour<24;hour++){paint.setColor(color(stats.hourly[row][hour]));paint.setStyle(Paint.Style.FILL);float x=left+hour*step;c.drawRoundRect(x,top+row*rowStep,x+step-gap,top+row*rowStep+cellHeight,dp(2),dp(2),paint);}
            }
            paint.setColor(0xffffbe82);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.3f));c.drawRoundRect(left+selected*step-dp(1.3f),top-dp(4),left+(selected+1)*step-gap+dp(1.3f),top+3*rowStep+cellHeight+dp(4),dp(4),dp(4),paint);
            for(int index:new int[]{0,6,12,18,23})text(c,hour(index),left+index*step+(step-gap)*.5f,top+3*rowStep+cellHeight+dp(23),LIGHT,10,Paint.Align.CENTER);
            float y=getHeight()-dp(11),legend=getWidth()-dp(103);
            text(c,"No records",0,y,LIGHT,10,Paint.Align.LEFT);
            for(int i=0;i<5;i++){paint.setColor(HEAT[i]);c.drawRoundRect(legend+dp(i*13),y-dp(8),legend+dp(i*13+9),y+dp(1),dp(2),dp(2),paint);}
            text(c,"More",getWidth()-dp(2),y,LIGHT,10,Paint.Align.RIGHT);
        }
    }
    static final class Trend extends Chart {
        float left,step;
        final int[] offers=new int[12],views=new int[12];int max=1;
        Trend(Context c,OverviewStats stats,IntConsumer listener){super(c,stats,178,listener);for(int h=0;h<24;h++){offers[h/2]+=stats.hourly[1][h];views[h/2]+=stats.hourly[2][h];}for(int i=0;i<12;i++)max=Math.max(max,Math.max(offers[i],views[i]));describe();}
        @Override void describe(){int index=selected/2;setContentDescription("Hint trend, two hours per cell. Selected "+hour(index*2)+" onward: displayed "+offers[index]+", viewed "+views[index]+"。");}
        @Override int hit(float x){return Math.max(0,Math.min(11,(int)((x-left)/Math.max(1,step))))*2+1;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setTextSize(sp(10));left=Math.max(dp(23),paint.measureText(String.valueOf(max))+dp(10));step=(getWidth()-left-dp(3))/12f;float top=dp(12),bottom=getHeight()-dp(29),plot=bottom-top;
            paint.setColor(0xfffff2e9);c.drawRoundRect(left+(selected/2)*step,top,left+(selected/2+1)*step,bottom,dp(5),dp(5),paint);
            for(int i=0;i<3;i++){float y=bottom-plot*i/2;line(c,left,y,getWidth()-dp(3),y,LINE,1);text(c,i==0?"0":i==2?String.valueOf(max):String.format(Locale.ROOT,"%.1f",max/2f),left-dp(8),y+sp(3),MUTED,10,Paint.Align.RIGHT);}
            for(int index:new int[]{0,3,6,9,11})text(c,hour(index*2),left+(index+.5f)*step,getHeight()-dp(7),MUTED,10,Paint.Align.CENTER);
            if(stats.published==0&&stats.accepted==0){text(c,"No retained hint receipts",left+(getWidth()-left)/2,(top+bottom)/2,MUTED,12,Paint.Align.CENTER);return;}
            Path path=new Path();paint.setStyle(Paint.Style.FILL);
            for(int i=0;i<12;i++){float x=left+(i+.5f)*step,y=bottom-plot*offers[i]/max;paint.setColor(ORANGE);if(offers[i]>0)c.drawRoundRect(x-step*.22f,y,x+step*.22f,bottom,dp(3),dp(3),paint);float lineY=bottom-plot*views[i]/max;if(i==0)path.moveTo(x,lineY);else path.lineTo(x,lineY);}
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(GREEN);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            for(int i=0;i<12;i++){float x=left+(i+.5f)*step,y=bottom-plot*views[i]/max;paint.setColor(Color.WHITE);c.drawCircle(x,y,dp(3.5f),paint);paint.setColor(GREEN);c.drawCircle(x,y,dp(2),paint);}
        }
    }
    static final class Donut extends View {
        final OverviewStats stats;final Paint p=new Paint(3);final float density,scale;
        Donut(Context context,OverviewStats stats){super(context);this.stats=stats;density=getResources().getDisplayMetrics().density;scale=getResources().getDisplayMetrics().scaledDensity;setContentDescription("Observer status: enabled "+stats.active+", pending "+stats.pending+", disabled "+stats.disabled+"。");}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize((int)(136*density),w),resolveSize((int)(136*density),h));}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)-12*density;RectF oval=new RectF(x-r,y-r,x+r,y+r);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(12*density);p.setColor(LINE);c.drawArc(oval,0,360,false,p);int[] values={stats.active,stats.pending,stats.disabled},colors={GREEN,ORANGE,0xffc3ccbd};float angle=-90;
            if(stats.totalSources>0)for(int i=0;i<3;i++){float sweep=360f*values[i]/stats.totalSources;if(sweep>0){p.setColor(colors[i]);c.drawArc(oval,angle,sweep,false,p);angle+=sweep;}}
            p.setStyle(Paint.Style.FILL);p.setColor(INK);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(28*scale);c.drawText(String.valueOf(stats.totalSources),x,y+3*scale,p);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(10*scale);p.setColor(MUTED);c.drawText("Observers",x,y+23*scale,p);
        }
    }
}
