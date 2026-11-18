package org.agentos.tests;

import android.os.Bundle;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import java.io.*;

/** Kills only the assistant app process; preserves the broker, account and user history. */
public final class DrawerRecoveryTest extends UiAutomatorTestCase {
    static final String PKG="org.agentos.assistant";
    static final String ROOT="/data/user/0/"+PKG+"/files/runtime/";
    UiAutomation automation;
    void refreshAccessibility() throws Exception {
        if (automation == null) {
            java.lang.reflect.Method method=getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
            method.setAccessible(true);Object bridge=method.invoke(getUiDevice());
            for(Class<?> type=bridge.getClass();type!=null;type=type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field=type.getDeclaredField("mUiAutomation");
                    field.setAccessible(true);automation=(UiAutomation)field.get(bridge);break;
                } catch(NoSuchFieldException ignored) {}
            }
            assertNotNull("Use the existing UiAutomator connection",automation);
            AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            try { automation.waitForIdle(100,1500); } catch(java.util.concurrent.TimeoutException ignored) {}
        }
        UiAutomation.class.getMethod("clearCache").invoke(automation);
    }
    class Node extends UiObject {
        final String expected;final boolean text;
        Node(String description){this(description,false);}
        Node(String expected,boolean text){super(new UiSelector().packageName(PKG));this.expected=expected;this.text=text;}
        AccessibilityNodeInfo matching(AccessibilityNodeInfo node,int depth,int[] budget) {
            if(node==null||depth>50||--budget[0]<0)return null;
            CharSequence actual=text?node.getText():node.getContentDescription();
            if(node.isVisibleToUser()&&PKG.contentEquals(node.getPackageName()==null?"":node.getPackageName())
                    &&expected.contentEquals(actual==null?"":actual))return node;
            for(int i=0;i<node.getChildCount();i++) {
                AccessibilityNodeInfo found=matching(node.getChild(i),depth+1,budget);
                if(found!=null)return found;
            }
            return null;
        }
        @Override protected AccessibilityNodeInfo findAccessibilityNodeInfo(long timeout) {
            long until=System.nanoTime()+timeout*1_000_000L;
            try {
                do {
                    refreshAccessibility();
                    // Background display 2 may own the active window; recovery operates only display 0.
                    List<AccessibilityWindowInfo> windows=automation.getWindowsOnAllDisplays().get(0);
                    if(windows!=null)for(AccessibilityWindowInfo window:windows) {
                        AccessibilityNodeInfo found=matching(window.getRoot(),0,new int[]{4096});
                        if(found!=null)return found;
                    }
                    if(System.nanoTime()<until)sleep(100);
                } while(System.nanoTime()<until);
                return null;
            } catch(Exception failure){throw new IllegalStateException(failure);}
        }
        boolean replace(String value){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
        String value(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);if(n==null)throw new AssertionError("Composer node missing");return n.isShowingHintText()||n.getText()==null?"":n.getText().toString();}
        boolean act(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    }
    String command(String... args)throws Exception{Process p=Runtime.getRuntime().exec(args);String value=new String(p.getInputStream().readAllBytes(),"UTF-8").trim();assertEquals("Command failed",0,p.waitFor());return value;}
    String read(String path)throws Exception{try(InputStream in=new FileInputStream(path)){return new String(in.readAllBytes(),"UTF-8");}}
    void open(String thread)throws Exception{command("/system/bin/am","start","-n",PKG+"/.PanelActivity","--es","conversationId",thread);assertTrue(new Node("Taku 对话输入框").waitForExists(25000));sleep(900);}
    void expectDraft(String expected,String reason)throws Exception{
        long until=System.nanoTime()+12_000_000_000L;String actual="";
        while(System.nanoTime()<until){actual=new Node("Taku 对话输入框").value();if(expected.equals(actual))return;sleep(150);}
        assertEquals(reason,expected,actual);
    }
    void killApp()throws Exception{String pid=command("/system/bin/pidof",PKG);assertTrue("Expected exactly one app process",pid.matches("[0-9]+"));command("/system/bin/kill","-9",pid);sleep(500);}
    void capture(String name)throws Exception{command("/system/bin/screencap","-p","/data/local/tmp/recovery-"+name+".png");}
    public void testDraftAndActualTaskSurviveAppDeath()throws Exception{
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2500);
        String phase=getParams().getString("phase","");
        if(phase.equals("prepareReboot")||phase.equals("verifyReboot")){
            JSONObject accepted=new JSONObject(read("/data/local/tmp/recovery-acceptance.json"));
            String recoveredThread=accepted.getString("threadId"),saved="重启验收草稿：车票与酒店待确认。";
            open(recoveredThread);
            if(phase.equals("prepareReboot")){
                assertTrue(new Node("Taku 对话输入框").replace(saved));sleep(1000);capture("reboot-before");return;
            }
            expectDraft(saved,"Draft must survive an Android reboot");
            assertTrue("Real conversation history must survive reboot",new Node("RECOVERY_TASK_OK",true).waitForExists(25000));
            JSONObject completed=new JSONObject(read(ROOT+"tasks/"+accepted.getString("taskId")+"/state.json"));assertEquals("completed",completed.optString("state"));
            capture("reboot-after");assertTrue(new Node("Taku 对话输入框").replace(""));
            accepted.put("draftSurvivesAndroidReboot",true).put("actualReplyHistorySurvivesAndroidReboot",true);
            try(FileOutputStream out=new FileOutputStream("/data/local/tmp/recovery-acceptance.json")){out.write(accepted.toString().getBytes("UTF-8"));}return;
        }
        assertTrue("Unknown recovery-test phase",phase.isEmpty());
        String thread=NativeSocketProbe.rpc("thread/start",new JSONObject().put("developerInstructions","这是静音恢复验收，只执行本轮用户明确指定的命令，不调用手机写操作，不发通知，不播放声音。")).getJSONObject("thread").getString("id");
        open(thread);String draft="旅行草稿：明天先整理行李，再确认车票。";assertTrue(new Node("Taku 对话输入框").replace(draft));sleep(1000);capture("draft-before");
        killApp();open(thread);capture("draft-after");expectDraft(draft,"Draft must survive an abrupt app-process death");
        getUiDevice().pressHome();command("/system/bin/am","start","-n","com.android.settings/.Settings");sleep(500);open(thread);expectDraft(draft,"Draft must survive leaving the drawer");
        String previous=read(ROOT+"latest.id").trim();assertTrue(new Node("Taku 对话输入框").replace("请真实执行 /system/bin/sleep 8，完成后仅回复 RECOVERY_TASK_OK。"));assertTrue(new Node("发送消息").act());
        String task="";long until=System.nanoTime()+40_000_000_000L;
        while(System.nanoTime()<until){String id=read(ROOT+"latest.id").trim();File runtime=new File(ROOT+"tasks/"+id+"/runtime-info.json");if(!id.equals(previous)&&runtime.isFile()&&new JSONObject(read(runtime.getPath())).optBoolean("turnStarted")){task=id;break;}sleep(200);}
        assertFalse("Real upstream turn did not start",task.isEmpty());assertFalse("Task completed before the process-death fixture",new File(ROOT+"tasks/"+task+"/done").exists());killApp();open(thread);JSONObject state=null;until=System.nanoTime()+100_000_000_000L;
        while(System.nanoTime()<until){state=new JSONObject(read(ROOT+"tasks/"+task+"/state.json"));if(new File(ROOT+"tasks/"+task+"/done").exists())break;sleep(250);}
        assertNotNull(state);assertEquals(state.optString("message"),"completed",state.optString("state"));String reply=read(ROOT+"tasks/"+task+"/result.txt");assertTrue(reply,reply.contains("RECOVERY_TASK_OK"));
        UiObject visible=new Node("RECOVERY_TASK_OK",true);assertTrue("Recovered UI must show the real reply",visible.waitForExists(25000));assertEquals("",new Node("Taku 对话输入框").value());capture("completed");
        JSONObject result=new JSONObject().put("threadId",thread).put("taskId",task).put("draftSurvivesAbruptAppDeath",true).put("draftSurvivesBackground",true).put("actualModelTaskSurvivesAppDeath",true).put("actualReplyVisibleAfterRecovery",true).put("modelResponsesSimulated",false).put("reply",reply);
        try(FileOutputStream out=new FileOutputStream("/data/local/tmp/recovery-acceptance.json")){out.write(result.toString().getBytes("UTF-8"));}
    }
}
