package org.agentos.tests;

import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Explicit opt-in live upstream test. Uses a separate conversation and synthetic page only. */
public final class JevMagpieLiveUiTest extends JevMagpieUiTest {
 private String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
 public void testLiveJevMagpieChain()throws Exception{
  assertEquals("Live calls must be explicitly authorized", "true", getParams().getString("liveAuthorized"));
  ConfiguratorSetup();connect();new File(OUT).mkdirs();
  String model=getParams().getString("model"),thread=getParams().getString("thread");
  assertNotNull(model);assertNotNull(thread);
  String upstream=model.substring(model.indexOf('/')+1).toLowerCase(Locale.ROOT);
  assertTrue("Use a real catalog Jev model",upstream.startsWith("typesafe/jev")||upstream.startsWith("~typesafe/jev")||upstream.startsWith("jev"));
  JSONObject connection=NativeSocketProbe.exchange(new JSONObject().put("op","modelConnection").put("action","read"));
  open(thread);selectDecision(model);open(thread);
  String pending="真实 Jev 验收草稿：这段不要发送。";
  assertEquals(pending,draft());
  command("/system/bin/input","keyevent","KEYCODE_BACK");sleep(350);
  command("/system/bin/am","start","-n","org.agentos.jevfixture/.ChatFixtureActivity");
   node("合成聊天 · Jev/Magpie 协议验收");sleep(1200);
  capture("live-page-before-trigger");
  Set<String> baseline=tasks();long started=android.os.SystemClock.elapsedRealtime();
  command("/system/bin/am","broadcast","-n",PKG+"/.JevTestTrigger","-a",PKG+".TEST_JEV_SIMULATED_POWER");
  android.view.accessibility.AccessibilityNodeInfo hint=find("Accept screen suggestion and hand to Agent",35000);
  if(hint==null)capture("live-no-action");
  assertNotNull("Real Jev must offer a hint",hint);
  long decisionMs=android.os.SystemClock.elapsedRealtime()-started;
  capture("live-hint");
  assertEquals("No Agent task before consent",baseline,tasks());
  command("/system/bin/am","broadcast","-n",PKG+"/.JevTestTrigger","-a",PKG+".TEST_JEV_SIMULATED_POWER");sleep(350);
  assertEquals("Duplicate trigger creates no task",baseline,tasks());
  long accepted=android.os.SystemClock.elapsedRealtime();click("Accept screen suggestion and hand to Agent");
  Set<String> added=new HashSet<>();long until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){added=tasks();added.removeAll(baseline);if(added.size()==1)break;sleep(150);}
  assertEquals("Exactly one accepted task",1,added.size());
  String id=added.iterator().next();File task=new File(TASKS,id);
  until=System.currentTimeMillis()+180000;
  while(!new File(task,"done").isFile()&&System.currentTimeMillis()<until)sleep(250);
  JSONObject state=new JSONObject(read(new File(task,"state.json")));
  assertEquals(state.toString(),"completed",state.getString("state"));
  JSONObject request=new JSONObject(read(new File(task,"request.json")));
  assertFalse("The background task uses its own conversation",thread.equals(request.getString("conversationId")));
  assertEquals("full",request.getString("permissionMode"));
  assertTrue(new File(request.getString("screenshot")).length()>1000);
  assertTrue(request.optJSONArray("attachments")==null||request.getJSONArray("attachments").length()==0);
  assertFalse(request.getString("prompt").contains(pending));
  open(thread);assertEquals(pending,draft());assertNotNull(find("keep-live-jev-fixture.txt",2000));
  JSONObject history=NativeSocketProbe.rpc("thread/read",new JSONObject().put("threadId",request.getString("conversationId")).put("includeTurns",true)).getJSONObject("thread");
  JSONArray turns=history.getJSONArray("turns");JSONObject turn=turns.getJSONObject(turns.length()-1);
  assertEquals("completed",turn.getString("status"));StringBuilder reply=new StringBuilder();
  JSONArray items=turn.getJSONArray("items");
  for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if("agentMessage".equals(item.optString("type")))reply.append(item.optString("text")).append("\n");}
  assertTrue("Actual Agent reply must be nonempty",reply.toString().trim().length()>20);
  assertTrue("Reply must use the captured dining context",reply.toString().contains("西湖")||reply.toString().contains("周六")||reply.toString().contains("18:00"));
  JSONObject after=NativeSocketProbe.exchange(new JSONObject().put("op","modelConnection").put("action","read"));
  assertEquals(connection.getString("mode"),after.getString("mode"));assertEquals(connection.getString("model"),after.getString("model"));
  assertEquals(thread,preference("conversation"));capture("live-agent-completed");
  JSONObject report=new JSONObject().put("passed",true).put("modelResponsesSimulated",false)
   .put("pageSynthetic",true).put("gatewayOnAndroid",true).put("nativeCodexOnAndroid",true)
   .put("decisionModel",model).put("threadId",thread).put("taskId",id).put("turnId",turn.getString("id"))
   .put("mainConnectionMode",connection.getString("mode")).put("mainConnectionUnchanged",true)
   .put("decisionToHintMs",decisionMs).put("agentAfterConsentMs",android.os.SystemClock.elapsedRealtime()-accepted)
   .put("noTaskBeforeConsent",true).put("duplicateTriggerSuppressed",true).put("exactlyOneAgentTask",true)
   .put("draftAndAttachmentPreserved",true).put("originalScreenshotPreserved",true).put("backgroundPermissionMode",request.getString("permissionMode"))
   .put("agentReply",reply.toString().trim());
  Files.write(new File(OUT,"live-acceptance.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
