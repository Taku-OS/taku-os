package org.agentos.tests;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.*;
import android.view.accessibility.AccessibilityNodeInfo;
/** Native layout/access regression. Does not submit a model request. */
public final class DockHomeTest extends UiAutomatorTestCase {
 static final class Node extends UiObject {
  Node(UiSelector s){super(s.packageName("org.agentos.assistant"));}
  boolean action(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(1500);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
 }
 Node desc(String value){return new Node(new UiSelector().description(value));}
 Node text(String value){return new Node(new UiSelector().text(value));}
 void click(Node node)throws Exception{assertTrue(node.action());sleep(500);}
 void capture(String state)throws Exception{sleep(800);Process p=Runtime.getRuntime().exec(new String[]{"/system/bin/screencap","-p","/data/local/tmp/taku-layout-"+state+".png"});assertEquals(0,p.waitFor());getUiDevice().dumpWindowHierarchy("taku-layout-"+state+".xml");}
 void home()throws Exception{getUiDevice().pressHome();sleep(600);}
 public void testDockAndEntrypoints()throws Exception {
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(1500).setActionAcknowledgmentTimeout(1000);home();
  if(desc("Taku 桌面输入框").exists()){click(desc("全部应用"));click(text("桌面小组件"));click(text("移除首页小组件"));click(desc("返回桌面"));}
  assertTrue(text("信息").exists());assertTrue(text("相机").exists());assertTrue(text("相册").exists());assertTrue(text("浏览器").exists());
  assertFalse(desc("Taku 伙伴输入").exists());assertFalse(desc("生成与任务").exists());assertFalse(desc("系统设置").exists());
  assertFalse(desc("Taku 桌面输入框").exists());capture("home");
  String[] labels={"信息","相机","相册","浏览器"};String[] packages={"com.android.messaging","com.android.camera2","com.android.gallery3d","org.chromium.webview_shell"};
  for(int i=0;i<labels.length;i++){UiObject label=text(labels[i]);assertTrue(label.exists());getUiDevice().click(label.getBounds().centerX(),label.getBounds().centerY());sleep(1000);assertTrue("Expected opened package "+packages[i],new UiObject(new UiSelector().packageName(packages[i])).exists());capture("app-"+i);home();}
  click(desc("查看真实任务"));assertTrue(text("生成").exists());assertFalse(text("信息").exists());assertTrue(desc("Taku 桌宠，任务已完成，点按打招呼，长按在预留区域拖动").exists()||new Node(new UiSelector().descriptionStartsWith("Taku desktop pet, ")).exists());capture("make");home();
  click(desc("打开独立镜像屏"));assertTrue(text("后台屏幕").exists());capture("mirror");home();
  click(desc("全部应用"));assertTrue(text("桌面小组件").exists());capture("drawer");click(text("桌面小组件"));assertTrue(text("添加到首页").exists());click(text("添加到首页"));assertTrue(desc("Taku 桌面输入框").exists());capture("widget-added");home();click(desc("全部应用"));click(text("桌面小组件"));click(text("移除首页小组件"));click(desc("返回桌面"));assertFalse(desc("Taku 桌面输入框").exists());capture("widget-removed");
 }
}
