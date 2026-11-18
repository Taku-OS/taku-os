package org.agentos.tests;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Opt-in live model test on the existing AVD. The foreground page is explicitly synthetic. */
public final class BackgroundScreenUiTest extends AgentInterfaceTest {
 static final File TASKS=new File("/data/user/0/org.agentos.assistant/files/runtime/tasks");
 JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));}
 Set<String> tasks()throws Exception{
  Set<String> ids=new HashSet<>();File[] files=TASKS.listFiles();if(files!=null)for(File file:files)
   if(new File(file,"ready").isFile())try{JSONObject r=read(new File(file,"request.json"));if(!r.has("method")&&!r.has("op")&&!r.optString("prompt").isEmpty())ids.add(file.getName());}catch(Exception old){}
  return ids;
 }
 public void testLiveBackgroundAskUser()throws Exception{
  assertEquals("true",getParams().getString("liveAuthorized"));
  assertEquals("AVD-only integration test","1",command("/system/bin/getprop","ro.kernel.qemu").trim());
  com.android.uiautomator.core.Configurator.getInstance().setWaitForIdleTimeout(0);connect();new File(OUT).mkdirs();
  String original=preference("conversation");
  NativeSocketProbe.exchange(new JSONObject().put("op","systemAi").put("action","configure")
   .put("patch",new JSONObject().put("apps",new JSONObject().put("org.agentos.assistant",true))));
  command("/system/bin/cmd","voiceinteraction","show");node("Taku 对话输入框");
  command("/system/bin/cmd","voiceinteraction","hide");sleep(300);original=preference("conversation");
  command("/system/bin/am","start","--display","0","-n","org.agentos.screenfixture/.ScreenFixtureActivity");
  node("合成约饭 · 后台交互验收");sleep(700);capture("00-synthetic-foreground");
  Set<String> baseline=tasks();
  command("/system/bin/am","broadcast","-a","org.agentos.assistant.MANUAL_SCREEN_SUGGESTION","-n",PKG+"/.JevTestTrigger");
  assertNotNull("Real image model must generate an offer",find("Accept screen suggestion and hand to Agent",40000));capture("01-real-image-model-hint");
  assertEquals("No Agent task before acceptance",baseline,tasks());assertNull(find("手动触发",100));
  click("Accept screen suggestion and hand to Agent");
  Set<String> added=new HashSet<>();long until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){added=tasks();added.removeAll(baseline);if(added.size()==1)break;sleep(100);}
  assertEquals("Exactly one background task",1,added.size());
  String task=added.iterator().next();File directory=new File(TASKS,task);JSONObject request=read(new File(directory,"request.json"));String thread=request.getString("conversationId");
  assertNotNull("Original foreground remains visible",find("合成约饭 · 后台交互验收",3000));
  assertNull("No main chat is opened",find("Taku 对话输入框",300));
  assertEquals("Current main conversation is preserved",original,preference("conversation"));
  capture("02-background-task-keeps-foreground");
  JSONObject question=null;until=System.currentTimeMillis()+90000;
  while(System.currentTimeMillis()<until){
   JSONObject state=NativeSocketProbe.exchange(new JSONObject().put("op","state").put("threadId",thread));
   JSONArray pending=state.optJSONArray("requests");
   if(pending!=null)for(int i=0;i<pending.length();i++){JSONObject q=pending.getJSONObject(i);if(q.optString("method").equals("phone/askUser")){question=q;break;}}
   if(question!=null)break;
   if(new File(directory,"done").isFile())break;
   sleep(300);
  }
  assertNotNull("Real Agent must request a necessary choice through ask_user",question);
  JSONObject args=question.getJSONObject("params").getJSONObject("arguments");String title=args.getString("question");String answer=args.getJSONArray("options").getString(0);
  node(title);node(answer);assertNull(find("Taku 对话输入框",100));capture("03-real-agent-bottom-question");
  String choiceText=(title+" "+answer).toLowerCase(java.util.Locale.ROOT);
  for(String forbidden:new String[]{"付款","支付","下单","Submit","发送消息","confirm booking","book now","purchase","payment","submit"})
   assertFalse("Integration test never authorizes external commitments: "+forbidden,choiceText.contains(forbidden));
  assertFalse("Task remains pending before answer",new File(directory,"done").isFile());
  click(answer);String key=question.getString("key");boolean resolved=false;until=System.currentTimeMillis()+20000;
  while(System.currentTimeMillis()<until){JSONArray pending=NativeSocketProbe.exchange(new JSONObject().put("op","state").put("threadId",thread)).getJSONArray("requests");resolved=true;
   for(int i=0;i<pending.length();i++)if(key.equals(pending.getJSONObject(i).optString("key")))resolved=false;
   if(resolved)break;sleep(150);
  }
  assertTrue("Actual user answer resolves the pending native tool call",resolved);
  boolean resumed=false;until=System.currentTimeMillis()+30000;
  while(System.currentTimeMillis()<until){
   JSONObject state=NativeSocketProbe.exchange(new JSONObject().put("op","state").put("threadId",thread));
   if(state.has("completedTurn")){resumed=true;break;}
   JSONArray pending=state.getJSONArray("requests");
   for(int i=0;i<pending.length();i++)if(!key.equals(pending.getJSONObject(i).optString("key")))resumed=true;
   if(resumed)break;sleep(200);
  }
  assertTrue("Real Agent must continue after receiving the choice",resumed);
  assertNotNull(find("合成约饭 · 后台交互验收",2000));assertNull(find("Taku 对话输入框",100));capture("04-answered-without-opening-chat");
  JSONObject report=new JSONObject().put("passed",true).put("target","emulator").put("foregroundPageSynthetic",true).put("modelResponsesSimulated",false)
   .put("taskId",task).put("threadId",thread).put("noTaskBeforeConsent",true).put("exactlyOneTask",true).put("foregroundPreserved",true)
   .put("mainChatNotOpened",true).put("realAskUserQuestion",title).put("actualOptionChosen",answer).put("nativeToolCallResolved",true).put("agentResumedAfterAnswer",true)
   .put("fullCrossAppCasePassed",false).put("physicalDevicePassed",false);
  Files.write(new File(OUT,"background-acceptance.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  Files.write(new File(directory,"cancel").toPath(),new byte[]{49});
 }
}
