package org.agentos.tests;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import java.io.File;
/** Exercises the real native Home; never submits a model task. */
public final class VideoHomeTest extends UiAutomatorTestCase {
 static final class Field extends UiObject {
  Field(){super(new UiSelector().packageName("org.agentos.assistant").description("Taku 桌面输入框"));}
  boolean replace(String text){AccessibilityNodeInfo node=findAccessibilityNodeInfo(5000);if(node==null)return false;Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
 }
 UiObject control(String label){return new UiObject(new UiSelector().packageName("org.agentos.assistant").description(label));}
 void capture(String state)throws Exception{System.out.println("CAPTURE: "+state);sleep(1400);getUiDevice().dumpWindowHierarchy("taku-video-"+state+".xml");assertTrue(getUiDevice().takeScreenshot(new File("/data/local/tmp/taku-video-"+state+".png")));}
 public void testHomeFlow() throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(100).setWaitForSelectorTimeout(3000).setActionAcknowledgmentTimeout(1000);getUiDevice().pressHome();Field edit=new Field();assertTrue(edit.waitForExists(5000));capture("home");
  assertTrue(edit.click());String draft="做一个记录灵感的小工具";assertTrue(edit.replace(draft));assertEquals(draft,edit.getText());capture("input");assertTrue(edit.replace(""));getUiDevice().pressBack();sleep(900);
  assertTrue(control("全部应用").click());assertTrue(control("返回桌面").waitForExists(5000));capture("drawer");getUiDevice().pressBack();assertTrue(control("全部应用").waitForExists(5000));
  assertTrue(control("生成与任务").click());assertTrue(edit.waitForExists(5000));assertTrue(new UiObject(new UiSelector().packageName("org.agentos.assistant").text("生成")).exists());capture("make");assertTrue(control("返回桌面").click());
  if(!control("专注计时").getChild(new UiSelector().text("25 min")).exists())control("专注计时").click();assertTrue(control("专注计时").click());sleep(1400);assertTrue(control("专注计时").getChild(new UiSelector().textMatches("[0-9]{2}:[0-9]{2}")).exists());assertTrue(control("专注计时").click());
  control("接口任务看板").click();assertTrue(new UiObject(new UiSelector().packageName("org.agentos.apiboard")).waitForExists(5000));getUiDevice().pressHome();assertTrue(edit.waitForExists(5000));
 }
}
