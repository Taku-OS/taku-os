package org.agentos.tests;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import java.io.*;

/** Real upstream turns, real dynamic phone tool and real native request/steer UI. No response fixtures. */
public final class NativeRuntimeTest extends UiAutomatorTestCase {
 static class Node extends UiObject {Node(String d){super(new UiSelector().packageName("org.agentos.assistant").description(d));}boolean text(String s){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,s);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}boolean action(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}}
 String read(String path)throws Exception {try(InputStream in=new FileInputStream(path)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
 void cmd(String...args)throws Exception{assertEquals(0,Runtime.getRuntime().exec(args).waitFor());}
 JSONObject rpc(String method,JSONObject p)throws Exception{return NativeSocketProbe.rpc(method,p);}
 JSONObject object(String key,Object value)throws Exception{return new JSONObject().put(key,value);}
 JSONObject start(String thread,String prompt)throws Exception{return rpc("turn/start",object("threadId",thread).put("input",new JSONArray().put(object("type","text").put("text",prompt))));}
 String reply(String thread,String turn)throws Exception {
  long end=System.nanoTime()+120_000_000_000L;
  while(System.nanoTime()<end){JSONObject view=new JSONObject(read("/data/user/0/org.agentos.assistant/files/runtime/agent-view/"+thread+".json"));JSONArray turns=view.optJSONArray("turns");if(turns!=null)for(int i=0;i<turns.length();i++){JSONObject t=turns.getJSONObject(i);if(t.optString("id").equals(turn)&&!t.optString("status").equals("inProgress")){assertEquals("Native turn failed","completed",t.optString("status"));String answer="";JSONArray items=t.optJSONArray("items");if(items!=null)for(int j=0;j<items.length();j++)if(items.getJSONObject(j).optString("type").equals("agentMessage"))answer=items.getJSONObject(j).optString("text");if(!answer.isEmpty())return answer;}}sleep(300);}
  fail("Real upstream turn timed out");return "";
 }
 void open(String thread)throws Exception {cmd("/system/bin/am","start","-n","org.agentos.assistant/.PanelActivity","--es","conversationId",thread);assertTrue(new Node("向下拖动收起助手").waitForExists(10000));sleep(1000);}
 public void testNativeToolsSteerAndQuestions()throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
  JSONObject started=rpc("thread/start",object("developerInstructions","你是 Taku，用中文回复。仅执行用户明确提出的手机只读测试；用户要求特定工具时必须使用它。"));String thread=started.getJSONObject("thread").getString("id");
  JSONObject first=start(thread,"请真实调用 taku_phone.device_info，告诉我实际 Android 版本和手机型号。必须用这个原生工具，不要运行 shell 命令。");String turn=first.getJSONObject("turn").getString("id");String answer=reply(thread,turn);assertTrue(answer.contains("16"));assertTrue(answer.contains("Pixel 9"));
  JSONObject view=new JSONObject(read("/data/user/0/org.agentos.assistant/files/runtime/agent-view/"+thread+".json"));boolean dynamic=false;JSONArray turns=view.getJSONArray("turns");for(int i=0;i<turns.length();i++){JSONArray items=turns.getJSONObject(i).optJSONArray("items");if(items!=null)for(int j=0;j<items.length();j++){JSONObject item=items.getJSONObject(j);if(item.optString("type").equals("dynamicToolCall")&&item.optString("status").equals("completed"))dynamic=true;}}assertTrue("Real dynamic tool lifecycle missing",dynamic);
  open(thread);assertTrue(new UiObject(new UiSelector().packageName("org.agentos.assistant").textContains("Pixel 9")).waitForExists(8000));cmd("/system/bin/screencap","-p","/data/local/tmp/native-dynamic-tool.png");
  JSONObject second=start(thread,"先真实执行 /system/bin/sleep 8，等待后只回复「原来的输出」。");String secondTurn=second.getJSONObject("turn").getString("id");assertTrue(new Node("追加指令").waitForExists(5000));assertTrue(new Node("Taku 对话输入框").text("最终输出改为「Taku 已追加」，不要回复原来的输出。"));assertTrue(new Node("追加指令").action());assertTrue(reply(thread,secondTurn).contains("Taku 已追加"));cmd("/system/bin/screencap","-p","/data/local/tmp/native-steer.png");
  JSONObject plan=object("mode","plan").put("settings",object("model",started.getString("model")).put("reasoning_effort","low").put("developer_instructions",JSONObject.NULL));
  JSONObject third=rpc("turn/start",object("threadId",thread).put("collaborationMode",plan).put("input",new JSONArray().put(object("type","text").put("text","请立即调用 request_user_input 问我：这次测试选择咖啡还是茶？必须给出咖啡和茶两个选项，不要执行任何其他动作。收到选择后只回复选择的原文。"))));String thirdTurn=third.getJSONObject("turn").getString("id");
  long end=System.nanoTime()+40_000_000_000L;JSONObject question=null;
  while(System.nanoTime()<end){JSONObject state=NativeSocketProbe.exchange(object("op","state").put("threadId",thread));JSONArray q=state.optJSONArray("requests");if(q!=null)for(int i=0;i<q.length();i++)if(q.getJSONObject(i).optString("method").equals("item/tool/requestUserInput"))question=q.getJSONObject(i);if(question!=null)break;sleep(300);}
  assertNotNull("Model must emit a real native question",question);UiObject option=new UiObject(new UiSelector().packageName("org.agentos.assistant").textContains("咖啡").className("android.widget.Button"));if(!option.waitForExists(8000))new UiScrollable(new UiSelector().packageName("org.agentos.assistant").scrollable(true)).scrollIntoView(option.getSelector());assertTrue(option.exists());option.click();UiObject submit=new UiObject(new UiSelector().packageName("org.agentos.assistant").text("Submit answer"));if(!submit.exists())new UiScrollable(new UiSelector().packageName("org.agentos.assistant").scrollable(true)).scrollIntoView(submit.getSelector());cmd("/system/bin/screencap","-p","/data/local/tmp/native-question.png");submit.click();assertTrue(reply(thread,thirdTurn).contains("咖啡"));
  JSONObject result=new JSONObject().put("threadId",thread).put("realDynamicPhoneTool",true).put("realSteerSameTurn",true).put("realQuestionRenderedAndAnswered",true).put("modelResponsesSimulated",false);
  try(FileOutputStream out=new FileOutputStream("/data/local/tmp/native-runtime-acceptance.json")){out.write(result.toString().getBytes("UTF-8"));}
 }
}
