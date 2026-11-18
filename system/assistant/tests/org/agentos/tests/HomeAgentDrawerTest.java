package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.*;
import org.json.*;

/** Real Home send, automatic assistant invocation, upstream reply and conversation continuity. */
public final class HomeAgentDrawerTest extends UiAutomatorTestCase {
    static final String ROOT = "/data/user/0/org.agentos.assistant/files/runtime/";
    static final String PKG = "org.agentos.assistant";
    static final class Node extends UiObject {
        Node(String description) { super(new UiSelector().packageName(PKG).description(description)); }
        boolean replace(String text) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(3000);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        boolean clickAction() {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(3000);
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }
    String read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n=in.read(buffer))!=-1) { out.write(buffer,0,n); if(out.size()>2097152)throw new IOException("Record too large"); }
            return out.toString("UTF-8");
        }
    }
    void command(String... args) throws Exception { assertEquals(0,Runtime.getRuntime().exec(args).waitFor()); }
    JSONObject waitTask(String previous) throws Exception {
        long deadline=System.nanoTime()+120_000_000_000L; String id=previous;
        while (System.nanoTime()<deadline) {
            File pointer=new File(ROOT,"latest.id"); if(pointer.isFile()) id=read(pointer).trim();
            if(!id.equals(previous) && id.matches("[0-9]+")) {
                File dir=new File(ROOT+"tasks/"+id);
                if(new File(dir,"done").isFile()) {
                    JSONObject state=new JSONObject(read(new File(dir,"state.json")));
                    assertEquals("Actual model turn failed: "+state.optString("message"),"completed",state.getString("state"));
                    JSONObject info=new JSONObject(read(new File(dir,"runtime-info.json")));
                    info.put("taskId",id).put("reply",read(new File(dir,"result.txt")));
                    assertTrue(info.optBoolean("turnStarted")); return info;
                }
            }
            sleep(250);
        }
        fail("Actual model reply timed out");return null;
    }
    public void testHomeHiOpensDrawerAndResponds() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        File pointer=new File(ROOT,"latest.id"); String previous=pointer.isFile()?read(pointer).trim():"";
        command("/system/bin/input","keyevent","224");sleep(600);command("/system/bin/input","keyevent","82");
        command("/system/bin/am","start","-S","-W","-n",PKG+"/.CodexHomeActivity");
        Node home=new Node("Agent 输入框"); assertTrue(home.waitForExists(7000));assertTrue(home.replace("Hi"));
        assertTrue(new Node("提交 Taku 指令").clickAction());
        assertTrue("Home send must invoke native drawer",new Node("向下拖动收起助手").waitForExists(12000));
        JSONObject first=waitTask(previous);String reply=first.getString("reply");assertTrue(reply.trim().length()>1);
        assertTrue("Real reply must be visible",new UiObject(new UiSelector().packageName(PKG).text(reply)).waitForExists(7000));
        command("/system/bin/screencap","-p","/data/local/tmp/pixel-home-hi.png");
        assertTrue(new Node("Taku 对话输入框").replace("我刚才发了哪句问候？请只回复原文，不调用工具。"));
        assertTrue(new Node("发送消息").clickAction());JSONObject second=waitTask(first.getString("taskId"));
        assertEquals("Follow-up must use the same native conversation",first.getString("threadId"),second.getString("threadId"));
        assertTrue("Model must remember Hi",second.getString("reply").toLowerCase().contains("hi"));
        command("/system/bin/screencap","-p","/data/local/tmp/pixel-home-followup.png");
        JSONObject result=new JSONObject().put("homeOpensDrawer",true).put("realModelReply",true)
                .put("sameConversationFollowup",true).put("first",first).put("second",second);
        try(FileOutputStream out=new FileOutputStream("/data/local/tmp/pixel-home-acceptance.json")){out.write(result.toString().getBytes("UTF-8"));}
    }
}
