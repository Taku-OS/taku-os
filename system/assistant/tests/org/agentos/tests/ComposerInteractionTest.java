package org.agentos.tests;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import java.io.*;

/** Real upstream steer and a real dynamic-phone approval; no synthetic runtime events. */
public final class ComposerInteractionTest extends UiAutomatorTestCase {
 JSONObject obj(String key,Object value)throws Exception{return new JSONObject().put(key,value);}
 JSONObject rpc(String method,JSONObject params)throws Exception{return NativeSocketProbe.rpc(method,params);}
 void cmd(String...args)throws Exception{assertEquals(0,Runtime.getRuntime().exec(args).waitFor());}
 String read(String path)throws Exception{try(InputStream in=new FileInputStream(path)){return new String(in.readAllBytes(),"UTF-8");}}
 JSONObject turn(String thread,String id)throws Exception{JSONObject view=new JSONObject(read("/data/user/0/org.agentos.assistant/files/runtime/agent-view/"+thread+".json"));JSONArray turns=view.getJSONArray("turns");for(int i=0;i<turns.length();i++)if(turns.getJSONObject(i).optString("id").equals(id))return turns.getJSONObject(i);return new JSONObject();}
 String complete(String thread,String id)throws Exception{long until=System.nanoTime()+90_000_000_000L;while(System.nanoTime()<until){JSONObject t=turn(thread,id);if(t.optString("status").equals("completed")){String answer="";JSONArray items=t.getJSONArray("items");for(int i=0;i<items.length();i++)if(items.getJSONObject(i).optString("type").equals("agentMessage"))answer+=items.getJSONObject(i).optString("text");return answer;}sleep(250);}fail("Actual upstream timeout");return "";}
 void open(String thread)throws Exception{cmd("/system/bin/am","start","-n","org.agentos.assistant/.PanelActivity","--es","conversationId",thread);assertTrue(new ComposerAcceptanceTest.Node("Taku 对话输入框").waitForExists(8000));sleep(700);}
 public void testSteerAndPhoneApproval()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
  String thread=rpc("thread/start",obj("developerInstructions","这是静音开发验收。用户要求运行命令时真实执行，收到追加指令后用其指定的最终回复。")).getJSONObject("thread").getString("id");
  String id=rpc("turn/start",obj("threadId",thread).put("input",new JSONArray().put(obj("type","text").put("text","请实际执行 /system/bin/sleep 12，等待完成，然后只回复 ORIGINAL。")))).getJSONObject("turn").getString("id");
  open(thread);assertTrue(new ComposerAcceptanceTest.Node("停止当前执行").waitForExists(6000));
  assertTrue(new ComposerAcceptanceTest.Node("Taku 对话输入框").replace("最终回复改为 COMPOSER_STEER_OK，保持本轮，不要启动新任务。"));assertTrue(new ComposerAcceptanceTest.Node("追加指令").waitForExists(3000));assertTrue(new ComposerAcceptanceTest.Node("追加指令").act());
  String answer=complete(thread,id);assertTrue(answer,answer.contains("COMPOSER_STEER_OK"));cmd("/system/bin/screencap","-p","/data/local/tmp/composer-steer.png");
  String approval=rpc("thread/start",obj("developerInstructions","这是原生手机权限确认验收。必须真实调用一次 taku_phone.tap x=1,y=1 来等待用户选择。用户取消后只报告实际返回，不能重试或改用其他工具。")).getJSONObject("thread").getString("id");
  String approvalTurn=rpc("turn/start",obj("threadId",approval).put("approvalPolicy","on-request").put("sandboxPolicy",obj("type","workspaceWrite")).put("input",new JSONArray().put(obj("type","text").put("text","请调用一次原生 taku_phone.tap，x=1,y=1，测试请求审批；等待我取消，然后报告结果。")))).getJSONObject("turn").getString("id");
  open(approval);long until=System.nanoTime()+40_000_000_000L;JSONObject pending=null;
  while(System.nanoTime()<until){JSONArray requests=NativeSocketProbe.exchange(obj("op","state").put("threadId",approval)).getJSONArray("requests");for(int i=0;i<requests.length();i++)if(requests.getJSONObject(i).optString("method").equals("phone/action/requestApproval"))pending=requests.getJSONObject(i);if(pending!=null)break;sleep(250);}
  assertNotNull("Actual phone approval missing",pending);
  UiObject cancel=new UiObject(new UiSelector().packageName("org.agentos.assistant").text("Cancel"));assertTrue(cancel.waitForExists(8000));cmd("/system/bin/screencap","-p","/data/local/tmp/composer-approval.png");cancel.click();
  String cancelled=complete(approval,approvalTurn);assertTrue(cancelled,cancelled.contains("Cancel"));
  JSONObject result=obj("realSteerSameTurn",true).put("steerThread",thread).put("steerTurn",id).put("steerReply",answer).put("realPhoneApprovalRendered",true).put("cancelledPhoneAction",true).put("approvalThread",approval).put("approvalTurn",approvalTurn).put("approvalReply",cancelled).put("modelResponsesSimulated",false);
  try(FileOutputStream out=new FileOutputStream("/data/local/tmp/composer-interaction.json")){out.write(result.toString().getBytes("UTF-8"));}
 }
}
