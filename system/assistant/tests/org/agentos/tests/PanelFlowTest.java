package org.agentos.tests;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
public final class PanelFlowTest extends UiAutomatorTestCase {
 public void testPanelResultVisible() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  assertTrue(new UiObject(new UiSelector().packageName("org.agentos.assistant").text("后台执行")).exists());
  assertTrue(new UiObject(new UiSelector().packageName("org.agentos.assistant").textContains("关于 Agent OS Lab")).exists());
  getUiDevice().dumpWindowHierarchy("assistant-final.xml");
 }
 public void testSubmitScreenQuestion() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0);
  UiObject edit=new UiObject(new UiSelector().packageName("org.agentos.assistant").className("android.widget.EditText"));
  assertTrue("Actual assistant EditText is visible",edit.exists());
  assertTrue(edit.setText("What page am I looking at on my screen? Do not operate any apps. Reply in Chinese."));
  UiObject submit=new UiObject(new UiSelector().packageName("org.agentos.assistant").text("后台执行"));
  assertTrue("Actual submit button is visible",submit.exists());
  assertTrue(submit.click());
 }
}
