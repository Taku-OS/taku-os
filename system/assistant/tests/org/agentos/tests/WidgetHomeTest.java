package org.agentos.tests;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import com.android.uiautomator.core.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
/** Exercises native widget selection/edit/remove. It never submits a model task. */
public final class WidgetHomeTest extends UiAutomatorTestCase {
 static final class Node extends UiObject {
  Node(UiSelector selector){super(selector.packageName("org.agentos.assistant"));}
  AccessibilityNodeInfo current(){return findAccessibilityNodeInfo(1500);}
  boolean action(int code){AccessibilityNodeInfo n=current();return n!=null&&n.performAction(code);}
  boolean replace(String value){AccessibilityNodeInfo n=current();if(n==null)return false;Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);}
 }
 Node desc(String value){return new Node(new UiSelector().description(value));}
 Node button(String value){return new Node(new UiSelector().className("android.widget.Button").text(value));}
 void click(Node node)throws Exception{assertTrue(node.action(AccessibilityNodeInfo.ACTION_CLICK));sleep(650);}
 void capture(String state)throws Exception{sleep(800);Process p=Runtime.getRuntime().exec(new String[]{"/system/bin/screencap","-p","/data/local/tmp/taku-widget-"+state+".png"});assertEquals(0,p.waitFor());System.out.println("CAPTURE: "+state);}
 void manager()throws Exception{click(desc("全部应用"));click(button("桌面小组件"));}
 public void testNativeWidget()throws Exception{
  Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(1500).setActionAcknowledgmentTimeout(1000);getUiDevice().pressHome();sleep(600);Node field=desc("Taku 桌面输入框");assertFalse("Default Home must have no fixed input",field.exists());capture("default");
  manager();assertTrue(button("添加到首页").exists());capture("manager");click(button("添加到首页"));assertTrue(field.exists());capture("added");
  click(field);assertTrue(field.replace("做一个记录灵感的小工具"));assertEquals("做一个记录灵感的小工具",field.current().getText().toString());capture("input");assertTrue(field.replace(""));getUiDevice().pressBack();sleep(600);getUiDevice().pressHome();sleep(600);
  manager();assertTrue(button("移除首页小组件").exists());click(button("移除首页小组件"));click(desc("返回桌面"));assertFalse(field.exists());capture("removed");
 }
}
