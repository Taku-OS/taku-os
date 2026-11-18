package org.agentos.proactivity;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Native primitives aligned with MemoryUi: Taku colors, spacing, typography and line icons. */
final class ProactivityUi {
    static final int INK=0xff242825, MUTED=0xff6d736c, ORANGE=0xffff5a1f;
    static final int BG=0xfff7f8f5, LINE=0xffe9ece5, SOFT=0xffffeee5, GREEN=0xff426c4c;
    final Context context;
    ProactivityUi(Context context){this.context=context;}
    int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    GradientDrawable shape(int color,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    Drawable touch(int color,int radius,int border){return new RippleDrawable(ColorStateList.valueOf(0x186a705f),shape(color,radius,border),shape(Color.WHITE,radius,0));}
    LinearLayout col(){LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(context);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String value,int size,int color){TextView v=new TextView(context);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);return v;}
    TextView bold(String value,int size,int color){TextView v=text(value,size,color);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    void space(LinearLayout parent,int height){parent.addView(new View(context),new LinearLayout.LayoutParams(1,dp(height)));}
    LinearLayout card(){LinearLayout v=col();v.setPadding(dp(18),dp(18),dp(18),dp(18));v.setBackground(shape(Color.WHITE,20,LINE));return v;}
    TextView action(String label,boolean primary,String key,Runnable click){TextView v=bold(label,15,primary?Color.WHITE:INK);v.setGravity(Gravity.CENTER);v.setPadding(dp(16),dp(14),dp(16),dp(14));v.setMinHeight(dp(48));v.setBackground(touch(primary?ORANGE:Color.WHITE,14,primary?0:LINE));v.setTag(key);v.setContentDescription(label);v.setFocusable(true);v.setOnClickListener(w->click.run());return v;}
    TextView badge(String label,int color,int background){TextView v=bold(label,12,color);v.setPadding(dp(9),dp(5),dp(9),dp(5));v.setBackground(shape(background,8,0));return v;}
    View icon(String kind,int color,int size){View v=new Icon(context,kind,color);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return v;}
    View iconButton(String kind,String label,Runnable click){FrameLayout v=new FrameLayout(context);v.setBackground(touch(Color.TRANSPARENT,24,0));v.setTag(kind);v.setContentDescription(label);v.setFocusable(true);v.setOnClickListener(w->click.run());v.addView(icon(kind,INK,22),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));v.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return v;}
    View brand(){LinearLayout v=row();ImageView mark=new ImageView(context);mark.setImageResource(R.drawable.taku_brand_mark);v.addView(mark,new LinearLayout.LayoutParams(dp(27),dp(27)));TextView name=bold("taku",18,INK);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(7);v.addView(name,p);v.setContentDescription("Taku");v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);return v;}
    void divider(LinearLayout parent,int top){View line=new View(context);line.setBackgroundColor(LINE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.topMargin=dp(top);parent.addView(line,p);}
    static final class Icon extends View {
        final String kind;final int color;final Paint p=new Paint(3);
        Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;}
        void path(Canvas c,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        @Override public void onDraw(Canvas c){c.save();c.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.65f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(kind){
                case "spark":path(c,12,2,15,9,22,12,15,15,12,22,9,15,2,12,9,9,12,2);break;
                case "observers":c.drawRoundRect(3,4,13,14,2,2,p);c.drawRoundRect(11,10,21,20,2,2,p);break;
                case "clock":c.drawCircle(12,12,9,p);path(c,12,6,12,12,16,14);break;
                case "back":path(c,14,5,7,12,14,19);break;
                case "chevron":path(c,9,6,15,12,9,18);break;
                case "check":path(c,5,12,10,17,20,6);break;
                case "pause":path(c,9,5,9,19);path(c,15,5,15,19);break;
                case "shield":path(c,12,2,20,5,20,12,18,17,12,22,6,17,4,12,4,5,12,2);path(c,8,11,11,14,16,9);break;
                case "code":path(c,8,6,2,12,8,18);path(c,16,6,22,12,16,18);path(c,14,3,10,21);break;
                default:c.drawRoundRect(5,3,19,21,2,2,p);path(c,8,9,16,9);path(c,8,14,16,14);
            }c.restore();
        }
    }
}
