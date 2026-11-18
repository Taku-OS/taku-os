package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import org.json.*;
import java.io.*;

/** Actual Android picker selection is prepared before this test; responses are real upstream. */
public final class ComposerAcceptanceTest extends UiAutomatorTestCase {
    static final String PKG="org.agentos.assistant",ROOT="/data/user/0/"+PKG+"/files/runtime/";
    static class Node extends UiObject {
        Node(String description){super(new UiSelector().packageName(PKG).description(description));}
        boolean replace(String value){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
        boolean act(){AccessibilityNodeInfo n=findAccessibilityNodeInfo(3000);return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    }
    String read(String path)throws Exception{try(InputStream in=new FileInputStream(path)){return new String(in.readAllBytes(),"UTF-8");}}
    JSONObject waitTask(String previous,boolean completed)throws Exception{long end=System.nanoTime()+100_000_000_000L;while(System.nanoTime()<end){String id=read(ROOT+"latest.id").trim();if(!id.equals(previous)){File done=new File(ROOT+"tasks/"+id+"/done");if(done.exists()){JSONObject state=new JSONObject(read(ROOT+"tasks/"+id+"/state.json"));if(completed)assertEquals(state.optString("message"),"completed",state.optString("state"));state.put("id",id);return state;}}sleep(250);}fail("Actual task timeout");return null;}
    public void testSelectedImageAndStop()throws Exception{
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        assertTrue(new Node("图片附件预览").waitForExists(6000));
        String previous=read(ROOT+"latest.id").trim();
        assertTrue(new Node("Taku 对话输入框").replace("只根据我附的截图，回复顶部文件夹名称和是否为空目录。不要调用手机工具，也不要修改文件。"));
        assertTrue(new Node("发送消息").act());
        JSONObject first=waitTask(previous,true);String id=first.getString("id"),reply=read(ROOT+"tasks/"+id+"/result.txt");assertTrue(reply,reply.contains("云南旅行"));assertTrue(reply,reply.contains("空"));
        JSONObject request=new JSONObject(read(ROOT+"tasks/"+id+"/request.json"));assertEquals(1,request.getJSONArray("attachments").length());assertFalse(new Node("图片附件预览").exists());
        assertTrue(new Node("Taku 对话输入框").replace("请实际执行 /system/bin/sleep 20，等结束后只回复结束。"));assertTrue(new Node("发送消息").act());
        String active="";long end=System.nanoTime()+30_000_000_000L;
        while(System.nanoTime()<end){String value=read(ROOT+"latest.id").trim();if(!value.equals(id)){File info=new File(ROOT+"tasks/"+value+"/runtime-info.json");if(info.exists()&&new JSONObject(read(info.getPath())).optBoolean("turnStarted")){active=value;break;}}sleep(250);}
        assertFalse("Real model turn did not start",active.isEmpty());assertTrue(new Node("停止当前执行").waitForExists(5000));assertFalse(new Node("发送消息").exists());
        assertTrue(new Node("Taku 对话输入框").replace("请保留当前执行。"));assertTrue(new Node("追加指令").waitForExists(3000));assertFalse(new Node("停止当前执行").exists());
        assertTrue(new Node("Taku 对话输入框").replace(""));assertTrue(new Node("停止当前执行").waitForExists(3000));
        Runtime.getRuntime().exec(new String[]{"/system/bin/screencap","-p","/data/local/tmp/composer-running.png"}).waitFor();
        assertTrue(new Node("停止当前执行").act());JSONObject stopped=waitTask(id,false);assertEquals("cancelled",stopped.optString("state"));assertTrue(new Node("发送消息").waitForExists(7000));
        JSONObject result=new JSONObject().put("actualAndroidPickerAttachment",true).put("realImageUnderstanding",true).put("attachmentCount",1).put("sendBecomesStop",true).put("actualTaskCancelled",true).put("imageReply",reply).put("imageTask",id).put("stoppedTask",stopped.getString("id"));
        try(FileOutputStream out=new FileOutputStream("/data/local/tmp/composer-acceptance.json")){out.write(result.toString().getBytes("UTF-8"));}
    }
}
