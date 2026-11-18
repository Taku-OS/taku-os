package org.taku.scenarios;
import android.os.Bundle;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.nio.charset.StandardCharsets;
/** Test harness submits to the existing native TaskRequests/Broker; no new queue. */
public final class SubmitScenario extends UiAutomatorTestCase {
 static final class Input extends UiObject {
  Input(){super(new UiSelector().packageName("org.agentos.assistant").description("Taku 桌面输入框"));}
  boolean set(String text){AccessibilityNodeInfo n=findAccessibilityNodeInfo(5000);if(n==null)return false;Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
 }
 UiObject described(String d){return new UiObject(new UiSelector().packageName("org.agentos.assistant").description(d));}
 public void testSubmit() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(100).setWaitForSelectorTimeout(5000);
  String b64=getParams().getString("prompt_b64");assertNotNull(b64);
  String prompt=new String(Base64.decode(b64,Base64.DEFAULT),StandardCharsets.UTF_8).replace('\n',' ').replace('\r',' ');
  getUiDevice().pressHome();sleep(1000);Input field=new Input();
  if(!field.exists()){
   assertTrue(described("全部应用").click());
   assertTrue(new UiObject(new UiSelector().packageName("org.agentos.assistant").text("桌面小组件")).click());
   assertTrue(new UiObject(new UiSelector().packageName("org.agentos.assistant").text("添加到首页")).click());
   assertTrue(field.waitForExists(5000));
  }
  assertTrue(field.set(prompt));assertEquals(prompt,field.getText());
  assertTrue(described("提交 Taku 指令").click());sleep(1200);
  System.out.println("SUBMITTED_REAL_NATIVE_TASK");
 }
}
