package org.agentos.tests;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.*;
import java.io.File;

public final class DesktopEntryTest extends UiAutomatorTestCase {
 static final class NativeField extends UiObject {
  NativeField(UiSelector selector){super(selector);}
  boolean replaceText(String text){android.view.accessibility.AccessibilityNodeInfo node=findAccessibilityNodeInfo(5000);if(node==null)return false;android.os.Bundle args=new android.os.Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args);}
 }
 public void testDraftVisible() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  getUiDevice().pressHome();
  NativeField edit=new NativeField(new UiSelector().packageName("org.agentos.assistant").description("Taku 桌面输入框"));
  assertTrue(edit.waitForExists(5000));assertTrue(edit.click());
  String draft="帮我记一条：下午喝水";
  assertTrue(edit.replaceText(draft));assertEquals(draft,edit.getText());
  getUiDevice().waitForIdle(1000);sleep(1200);
  getUiDevice().dumpWindowHierarchy("codex-inline-draft.xml");
  assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/codex-inline-draft.png")));
  assertTrue(edit.replaceText(""));getUiDevice().pressBack();
 }
 public void testSubmitFromDesktop() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  getUiDevice().pressHome();
  NativeField edit=new NativeField(new UiSelector().packageName("org.agentos.assistant").description("Taku 桌面输入框"));
  assertTrue("Real editor exists directly on Home",edit.waitForExists(6000));
  assertTrue("Actual editor focused",edit.click());
  String encoded=getParams().getString("prompt_base64");
  assertNotNull("Task prompt supplied",encoded);
  String prompt=new String(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);
  assertTrue("Task entered through native UI text action",edit.replaceText(prompt));
  assertEquals("Actual editor contains exact task",prompt,edit.getText());
  getUiDevice().dumpWindowHierarchy("codex-desktop-typed.xml");
  assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/codex-desktop-typed.png")));
  UiObject submit=new UiObject(new UiSelector().packageName("org.agentos.assistant").description("提交 Taku 指令"));
  assertTrue("Actual submit button visible",submit.exists());
  assertTrue("Actual task submission clicked",submit.click());
  assertTrue("Desktop remains visible after submission",edit.waitForExists(5000));
  UiObject app=new UiObject(new UiSelector().description("接口任务看板"));
  assertTrue("Installed app available on desktop",app.waitForExists(5000));
  assertTrue("Actual app icon opens",app.click());
  assertTrue("Installed app started",new UiObject(new UiSelector().packageName("org.agentos.apiboard").className("android.webkit.WebView")).waitForExists(5000));
  getUiDevice().pressHome();
  assertTrue("Home returns to inline editor",edit.waitForExists(5000));
 }
 public void testRedesignedNavigation() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  getUiDevice().pressHome();
  UiObject edit=new UiObject(new UiSelector().description("Taku 桌面输入框"));
  assertTrue(edit.waitForExists(5000));
  assertTrue(new UiObject(new UiSelector().description("提交 Taku 指令")).exists());
  UiObject all=new UiObject(new UiSelector().description("全部应用"));
  assertTrue(all.click());
  assertTrue(new UiObject(new UiSelector().description("返回桌面")).waitForExists(5000));
  assertTrue(new UiObject(new UiSelector().description("Clock")).exists());
  getUiDevice().waitForIdle(1000);sleep(1200);
  assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/codex-redesign-drawer.png")));
  getUiDevice().pressBack();assertTrue(all.waitForExists(5000));
  getUiDevice().waitForIdle(1000);sleep(1200);
  UiObject app=new UiObject(new UiSelector().description("接口任务看板"));
  assertTrue(app.waitForExists(5000));
  // A window switch can make the legacy injector return false after a real launch.
  app.click();
  assertTrue(new UiObject(new UiSelector().packageName("org.agentos.apiboard").className("android.webkit.WebView")).waitForExists(5000));
  getUiDevice().pressHome();assertTrue(edit.waitForExists(5000));
 }
 public void testDismissResult() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  getUiDevice().pressHome();
  UiObject close=new UiObject(new UiSelector().description("收起任务结果"));
  assertTrue(close.waitForExists(5000));assertTrue(close.click());
  sleep(1800);assertFalse(close.exists());
  new UiObject(new UiSelector().description("接口任务看板")).click();
  assertTrue(new UiObject(new UiSelector().packageName("org.agentos.apiboard")).waitForExists(5000));
  getUiDevice().pressHome();
  assertTrue(new UiObject(new UiSelector().description("Taku 桌面输入框")).waitForExists(5000));
  sleep(1000);assertFalse(close.exists());
 }
}
