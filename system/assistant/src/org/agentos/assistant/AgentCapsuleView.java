package org.agentos.assistant;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** App-content entry only. It never claims to be a notch, mic, or SystemUI. */
final class AgentCapsuleView extends View {
 private final DesignTokens t;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private String state="idle",label="";
 AgentCapsuleView(Context context){super(context);t=new DesignTokens(context);setClickable(true);setFocusable(true);setMinimumHeight(t.pixels(R.dimen.ag_size_touch_min));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);update("idle");}
 void update(String value){String next=value.equals("running")?"Running":value.equals("queued")?"Queued":value.equals("completed")?"Task complete":value.equals("failed")?"Action needed":value.equals("needs_user")?"More information needed":value.equals("offline")?"Service offline":value.equals("unknown")?"Status unavailable":"";if(!value.equals(state)||!next.equals(label)){state=value;label=next;invalidate();}setContentDescription("Taku "+(next.isEmpty()?"Ready":next)+". Tap to enter a request");}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float wanted=t.px(label.isEmpty()?R.dimen.ag_size_capsule_width:R.dimen.ag_size_capsule_working_width),width=Math.min(getWidth(),wanted),height=t.px(R.dimen.ag_size_capsule_height);float x=(getWidth()-width)/2,y=(getHeight()-height)/2;paint.setColor(t.color(R.color.ag_color_charcoal));canvas.drawRoundRect(x,y,x+width,y+height,height/2,height/2,paint);
  float ew=t.px(R.dimen.ag_size_eye_width),eh=t.px(R.dimen.ag_size_eye_height),eg=t.px(R.dimen.ag_size_eye_gap);paint.setColor(t.color(R.color.ag_color_text_on_dark));float start=label.isEmpty()?getWidth()/2-(2*ew+eg)/2:x+t.px(R.dimen.ag_space_16);float ey=(getHeight()-eh)/2;canvas.drawRoundRect(start,ey,start+ew,ey+eh,ew/2,ew/2,paint);canvas.drawRoundRect(start+ew+eg,ey,start+2*ew+eg,ey+eh,ew/2,ew/2,paint);
  if(!label.isEmpty()){paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(t.px(R.dimen.ag_type_caption_size));float tx=start+2*ew+eg+t.px(R.dimen.ag_space_12);Paint.FontMetrics fm=paint.getFontMetrics();String shown=label;while(shown.length()>1&&paint.measureText(shown)>x+width-t.px(R.dimen.ag_space_12)-tx)shown=shown.substring(0,shown.length()-1);if(!shown.equals(label))return;canvas.drawText(shown,tx,(getHeight()-fm.ascent-fm.descent)/2,paint);}
 }
}
