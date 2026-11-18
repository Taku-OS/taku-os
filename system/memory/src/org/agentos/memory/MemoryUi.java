package org.agentos.memory;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.*;

/** Small native design primitives; no network assets or UI framework dependency. */
final class MemoryUi {
 static final int INK=0xff242825, MUTED=0xff7c807b, ORANGE=0xffff5a1f;
 static final int BG=0xfff7f8f5, LINE=0xffe9ece5, SOFT=0xffffeee5, GREEN=0xff51815d;
 final Context context; final float density;
 MemoryUi(Context c){context=c;density=c.getResources().getDisplayMetrics().density;}
 int dp(int n){return Math.round(n*density);}
 GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
 Drawable touch(int color,int radius,int stroke){return new RippleDrawable(ColorStateList.valueOf(0x146a705f),shape(color,radius,stroke),shape(Color.WHITE,radius,0));}
 LinearLayout col(){LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.VERTICAL);return v;}
 LinearLayout row(){LinearLayout v=new LinearLayout(context);v.setGravity(android.view.Gravity.CENTER_VERTICAL);return v;}
 TextView text(String value,int size,int color){TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);return t;}
 TextView bold(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
 TextView pill(String value,boolean selected){TextView t=text(value,13,selected?ORANGE:MUTED);t.setGravity(android.view.Gravity.CENTER);t.setPadding(dp(14),dp(10),dp(14),dp(10));t.setBackground(touch(selected?SOFT:Color.TRANSPARENT,20,0));return t;}
 TextView action(String value,boolean primary,Runnable click){TextView t=bold(value,14,primary?Color.WHITE:INK);t.setGravity(android.view.Gravity.CENTER);t.setPadding(dp(18),dp(13),dp(18),dp(13));t.setMinHeight(dp(48));t.setBackground(touch(primary?ORANGE:Color.WHITE,14,primary?0:LINE));t.setOnClickListener(v->click.run());return t;}
 View brand(int size,boolean wordmark){
  LinearLayout lockup=row();ImageView mark=new ImageView(context);mark.setImageResource(R.drawable.taku_brand_mark);lockup.addView(mark,new LinearLayout.LayoutParams(dp(size),dp(size)));
  if(wordmark){TextView name=bold("taku",18,INK);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(8);lockup.addView(name,p);name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
  mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);lockup.setContentDescription("Taku Logo");lockup.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);return lockup;
 }
 View icon(String kind,int color,int size){Icon v=new Icon(context,kind,color);v.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}
 View iconButton(String kind,String label,Runnable click){android.widget.FrameLayout f=new android.widget.FrameLayout(context);f.setBackground(touch(Color.TRANSPARENT,24,0));f.setContentDescription(label);f.setFocusable(true);View i=icon(kind,INK,22);android.widget.FrameLayout.LayoutParams p=new android.widget.FrameLayout.LayoutParams(dp(22),dp(22),android.view.Gravity.CENTER);f.addView(i,p);f.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));f.setOnClickListener(v->click.run());return f;}
 static final class Icon extends View {
  final String kind;final Paint p=new Paint(3);final int color;
  Icon(Context c,String k,int color){super(c);kind=k;this.color=color;}
  void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,p);}
  void path(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int n=2;n<points.length;n+=2)path.lineTo(points[n],points[n+1]);c.drawPath(path,p);}
  @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.65f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
   switch(kind){
    case "search":c.drawCircle(10.5f,10.5f,6.5f,p);line(c,15.5f,15.5f,21,21);break;
    case "memory":c.drawRoundRect(5,3,20,21,3,3,p);line(c,2,8,7,8);line(c,2,16,7,16);path(c,11,8,16,8,16,13);line(c,11,13,16,13);break;
    case "spark":path(c,12,2,15,9,22,12,15,15,12,22,9,15,2,12,9,9,12,2);break;
    case "settings":c.drawCircle(12,12,4,p);for(int n=0;n<8;n++){double a=n*Math.PI/4;line(c,12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10);}c.drawCircle(12,12,8,p);break;
    case "back":path(c,14,5,7,12,14,19);break;
    case "chevron":path(c,9,6,15,12,9,18);break;
    case "close":line(c,6,6,18,18);line(c,18,6,6,18);break;
    case "more":p.setStyle(Paint.Style.FILL);for(int n=6;n<=18;n+=6)c.drawCircle(12,n,1.6f,p);break;
    case "check":path(c,5,12,10,17,20,6);break;
    case "clock":c.drawCircle(12,12,9,p);path(c,12,6,12,12,16,14);break;
    case "pause":line(c,9,5,9,19);line(c,15,5,15,19);break;
    case "filter":line(c,4,7,20,7);line(c,7,12,17,12);line(c,10,17,14,17);break;
    case "shield":path(c,12,2,20,5,20,12,18,17,12,22,6,17,4,12,4,5,12,2);path(c,8,11,11,14,16,9);break;
    case "edit":path(c,4,16,16,4,20,8,8,20,4,20,4,16);line(c,13,7,17,11);break;
    default:c.drawRoundRect(5,3,19,21,2,2,p);line(c,8,9,16,9);line(c,8,13,16,13);line(c,8,17,13,17);
   }c.restore();
  }
 }
}
