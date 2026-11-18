package org.agentos.assistant;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** A synthetic completed reply rendered by the production view; never creates/sends an Agent turn. */
public final class ReadingUiProbe extends Instrumentation {
 private Activity activity;private android.view.ViewGroup content;private final List<View> previous=new ArrayList<>();private Button button;private NativeTurnView fixture;private boolean streaming;
 @Override public void onCreate(Bundle args){super.onCreate(args);streaming=args!=null&&"true".equals(args.getString("streaming"));start();}
 private Activity foreground()throws Exception {
  Object thread=Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread").invoke(null);
  Field field=thread.getClass().getDeclaredField("mActivities");field.setAccessible(true);
  for(Object record:((Map<?,?>)field.get(thread)).values()){
   Field member=record.getClass().getDeclaredField("activity");member.setAccessible(true);Activity a=(Activity)member.get(record);
   if(a!=null&&!a.isFinishing())return a;
  }
  return null;
 }
 private Button find(View view){if(view instanceof Button&&"朗读回复".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (Button)view;if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button found=find(group.getChildAt(i));if(found!=null)return found;}return null;}
 private void bindStream(boolean complete){
  NativeTurnPresentation.Block b=new NativeTurnPresentation.Block("audio-reading-fixture",complete?"completed":"inProgress",null);b.active=!complete;
  String text="你好，这是边生成边朗读测试。\n\n```kotlin\nthrow Error(\"do not read code\")\n```\n\n"+(complete?"现在回复已完成。":"现在");
  try{b.answers.add(new AgentConversation.Entry(AgentConversation.Kind.ASSISTANT,new JSONObject().put("id","reading-fixture").put("type","agentMessage"),"fixture",text));}catch(JSONException impossible){throw new RuntimeException(impossible);}
  fixture.bind(b,new JSONObject());
 }
 @Override public void onStart(){
  JSONObject report=new JSONObject();boolean success=false;
  try {
   runOnMainSync(()->{try{activity=foreground();}catch(Exception e){throw new RuntimeException(e);}});
   if(activity==null)activity=startActivitySync(new Intent().setClassName(getTargetContext(),"org.agentos.assistant.CodexHomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   runOnMainSync(()->{
    content=activity.findViewById(android.R.id.content);for(int i=0;i<content.getChildCount();i++)previous.add(content.getChildAt(i));content.removeAllViews();
    fixture=new NativeTurnView(activity,new DrawerTheme(false),new ConversationExpansion(getTargetContext().getSharedPreferences("audio-reading-fixture",0),"fixture"));
    NativeTurnPresentation.Block block=new NativeTurnPresentation.Block("audio-reading-fixture",streaming?"inProgress":"completed",null);block.active=streaming;
    try{block.answers.add(new AgentConversation.Entry(AgentConversation.Kind.ASSISTANT,new JSONObject().put("id","reading-fixture").put("type","agentMessage"),"fixture","# 测试\n\n你好，这是本地朗读测试。\n\n```kotlin\nthrow Error(\"do not read code\")\n```"));}catch(JSONException impossible){throw new RuntimeException(impossible);}
    if(streaming){block.answers.clear();try{block.answers.add(new AgentConversation.Entry(AgentConversation.Kind.ASSISTANT,new JSONObject().put("id","reading-fixture").put("type","agentMessage"),"fixture","你好，"));}catch(JSONException impossible){throw new RuntimeException(impossible);}}
    fixture.bind(block,new JSONObject());content.addView(fixture);button=find(fixture);
    if(button==null)throw new IllegalStateException("Production reading button missing");
    String plain=NativeMarkdown.speechText("你好\n\n```kotlin\nthrow Error()\n```");if(plain.contains("throw")||plain.contains("```")||!plain.contains("你好"))throw new IllegalStateException("Speech markdown filtering failed");
    button.performClick();
   });
   if(streaming)runOnMainSync(()->bindStream(false));
   long deadline=SystemClock.elapsedRealtime()+45000;boolean completed=false;boolean playedBeforeCompletion=false;
   while(SystemClock.elapsedRealtime()<deadline){String[] state={""};runOnMainSync(()->state[0]=String.valueOf(button.getStateDescription()));if(streaming&&!playedBeforeCompletion&&state[0].equals("Reading…")){playedBeforeCompletion=true;runOnMainSync(()->bindStream(true));}if(state[0].equals("Reading complete")){completed=true;break;}if(state[0].startsWith("朗读已停止")||state[0].contains("未完成")||state[0].contains("不可用"))throw new IllegalStateException("Native reading failed");SystemClock.sleep(100);}
   if(streaming&&!playedBeforeCompletion)throw new IllegalStateException("Reading did not start before reply completion");
   if(!completed)throw new IllegalStateException("Native reading completion not observed");
   report.put("streamedReply",streaming).put("playedBeforeReplyComplete",playedBeforeCompletion).put("nativeReplyReading",true).put("markdownCodeFiltered",true).put("syntheticReplyOnly",true).put("agentTurnCreated",false).put("physicalOutputVerified",false);success=true;
  }catch(Throwable error){try{report.put("error",error.getClass().getSimpleName());}catch(Exception ignored){}}
  finally{
   runOnMainSync(()->{NativeTurnView.stopReading(null);if(content!=null){content.removeAllViews();for(View old:previous)content.addView(old);}});
   try{report.put("passed",success);}catch(Exception ignored){}
   Bundle result=new Bundle();result.putString("report",report.toString());finish(success?-1:0,result);
  }
 }
}
