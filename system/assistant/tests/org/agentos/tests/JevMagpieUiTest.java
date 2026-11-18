package org.agentos.tests;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Actual Android UI, Broker, Magpie and Codex; only the model upstream is a labelled fixture. */
public class JevMagpieUiTest extends AgentInterfaceTest {
 static final File TASKS=new File("/data/user/0/org.agentos.assistant/files/runtime/tasks");
 Set<String> tasks() throws Exception {
  Set<String> ids=new HashSet<>();File[] files=TASKS.listFiles();if(files==null)return ids;
  for(File p:files)if(new File(p,"ready").isFile()&&new File(p,"request.json").isFile()){
   JSONObject q;
   try{q=new JSONObject(new String(Files.readAllBytes(new File(p,"request.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));}
   catch(JSONException oldRecord){if(new File(p,"done").isFile())continue;throw oldRecord;}
   if(!q.has("op")&&!q.has("method")&&!q.optString("prompt").isEmpty())ids.add(p.getName());
  }
  return ids;
 }
 JSONObject decisionModels()throws Exception{return NativeSocketProbe.exchange(new JSONObject().put("op","decision").put("action","models"));}
 void selectDecision(String id)throws Exception{
  click("会话工具");click("更多选项");click("设置");scrollTo("Jev decision model");click("Jev decision model");
  JSONArray models=decisionModels().getJSONArray("models");String name="";
  for(int i=0;i<models.length();i++)if(id.equals(models.getJSONObject(i).getString("id"))){
   JSONObject m=models.getJSONObject(i);name=m.getString("name")+(m.optBoolean("imageInput")?" · Text and images":m.optBoolean("inputKnown")?" · Text":" · Page text (image capability unknown)");
  }
  assertFalse(name.isEmpty());scrollTo(name);click(name);sleep(500);
  if(!"true".equals(command("/system/bin/sh","-c","cat /data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml | grep -o 'name=\"jevEnabled\" value=\"true\"' | sed 's/.*/true/'").trim())){
   click("Enable Jev decision model");sleep(600);
  }
  assertEquals(id,preference("jevModel"));
 }
 public void testJevMagpieChain()throws Exception{
  ConfiguratorSetup();connect();new File(OUT).mkdirs();
  String thread=preference("conversation"),draftText="Jev 迁移回归草稿：这段不要发送。";
  JSONObject before=NativeSocketProbe.exchange(new JSONObject().put("op","modelConnection").put("action","read"));
  JSONArray verified=new JSONArray();
  // Screen suggestions deliberately send the original image to Jev. A
  // text-only model is not a valid substitute for this path.
  String[] models={"fixture/jev-vision"};
  for(String model:models){
   open(thread);
   Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,draftText);
   assertTrue(node("对话输入框").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));sleep(300);
   selectDecision(model);open(thread);assertEquals(draftText,draft());
   command("/system/bin/input","keyevent","KEYCODE_BACK");sleep(350);
   command("/system/bin/am","start","-n","org.agentos.jevfixture/.ChatFixtureActivity");
   node("合成聊天 · Jev/Magpie 协议验收");sleep(1200);
   Set<String> baseline=tasks();
   command("/system/bin/am","broadcast","-n",PKG+"/.JevTestTrigger","-a",PKG+".TEST_JEV_SIMULATED_POWER");
   assertNotNull("Jev should offer a suggestion",find("Accept screen suggestion and hand to Agent",35000));capture(model.endsWith("text")?"text-hint":"image-hint");
   assertEquals("No task before consent",baseline,tasks());
   command("/system/bin/am","broadcast","-n",PKG+"/.JevTestTrigger","-a",PKG+".TEST_JEV_SIMULATED_POWER");sleep(300);
   assertEquals("Duplicate trigger creates no task",baseline,tasks());
   click("Accept screen suggestion and hand to Agent");
   Set<String> added=new HashSet<>();long until=System.currentTimeMillis()+45000;
   while(System.currentTimeMillis()<until){added=tasks();added.removeAll(baseline);if(added.size()==1)break;sleep(150);}
   assertEquals("One accepted suggestion creates one task",1,added.size());
   String id=added.iterator().next();File task=new File(TASKS,id);
   until=System.currentTimeMillis()+90000;
   while(!new File(task,"done").isFile()&&System.currentTimeMillis()<until)sleep(200);
   JSONObject state=new JSONObject(new String(Files.readAllBytes(new File(task,"state.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
   assertEquals(state.toString(),"completed",state.getString("state"));
   JSONObject request=new JSONObject(new String(Files.readAllBytes(new File(task,"request.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
   assertTrue("Original screenshot reaches Agent",new File(request.getString("screenshot")).length()>1000);
   assertTrue("Unsent draft attachments excluded",request.optJSONArray("attachments")==null||request.getJSONArray("attachments").length()==0);
   assertFalse("Unsent draft text excluded",request.getString("prompt").contains(draftText));
   open(thread);
   assertEquals("Unsent draft survives",draftText,draft());
   assertNotNull("Unsent attachment remains visible",find("keep-jev-fixture.txt",1000));
   capture(model.endsWith("text")?"text-completed":"image-completed");
   verified.put(new JSONObject().put("model",model).put("taskId",id).put("completed",true)
    .put("consentBeforeTask",true).put("duplicateTriggerSuppressed",true).put("draftAndAttachmentPreserved",true)
    .put("contextScreenshot",request.getString("screenshot")));
  }
  JSONObject after=NativeSocketProbe.exchange(new JSONObject().put("op","modelConnection").put("action","read"));
  assertEquals(before.getString("mode"),after.getString("mode"));assertEquals(before.getString("model"),after.getString("model"));
  JSONObject result=new JSONObject().put("passed",true).put("modelResponsesSimulated",true)
   .put("gatewayOnAndroid",true).put("nativeCodexOnAndroid",true).put("cases",verified).put("mainConnectionUnchanged",true);
  Files.write(new File(OUT,"jev-magpie-ui.json").toPath(),result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 @Override void scrollTo(String text)throws Exception{
  int w=getUiDevice().getDisplayWidth(),h=getUiDevice().getDisplayHeight();
  for(int i=0;i<7&&find(text,150)==null;i++)getUiDevice().swipe(w/2,h*4/5,w/2,h/3,20);
  node(text);
 }
 @Override void open(String thread)throws Exception{
  command("/system/bin/am","start","--display","0","-n",PKG+"/.PanelActivity","--es","conversationId",thread);
  node("对话输入框");sleep(500);
 }
 @Override String draft()throws Exception{return String.valueOf(node("对话输入框").getText());}
 void ConfiguratorSetup(){com.android.uiautomator.core.Configurator.getInstance().setWaitForIdleTimeout(0);}
}
