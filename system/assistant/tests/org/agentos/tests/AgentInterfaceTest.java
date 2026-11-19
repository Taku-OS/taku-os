package org.agentos.tests;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.*;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Shared device automation for the Expo Assistant. */
public class AgentInterfaceTest extends UiAutomatorTestCase {
    static final String PKG="org.agentos.agent";
    private static final String OPEN_ACCESS="content://org.agentos.assistant.open-access";
    private static final Set<String> COMPOSER_LABELS=Set.of(
            "Ask Taku to do anything...","让 Taku 帮你做任何事…","Add a requirement","补充要求");
    String OUT="/data/local/tmp/taku-agent-interface/";
    UiAutomation automation;
    private Context context;

    String command(String... args)throws Exception {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); p.getInputStream().transferTo(out);
        if(p.waitFor()!=0)throw new IOException(out.toString("UTF-8"));
        return out.toString("UTF-8");
    }
    void connect()throws Exception {
        OUT = getParams().getString("output", OUT);
        java.lang.reflect.Method method=getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
        method.setAccessible(true); Object bridge=method.invoke(getUiDevice());
        for(Class<?> c=bridge.getClass();c!=null;c=c.getSuperclass())try{
            java.lang.reflect.Field f=c.getDeclaredField("mUiAutomation"); f.setAccessible(true);
            automation=(UiAutomation)f.get(bridge); break;
        }catch(NoSuchFieldException ignored){}
        assertNotNull(automation);
        AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
    }
    private AccessibilityNodeInfo match(AccessibilityNodeInfo node, java.util.function.Predicate<AccessibilityNodeInfo> selector, int depth){
        if(node==null||depth>50)return null;
        if(node.isVisibleToUser()&&selector.test(node))return node;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo found=match(node.getChild(i),selector,depth+1);
            if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo find(java.util.function.Predicate<AccessibilityNodeInfo> selector,long timeout)throws Exception {
        long until=SystemClock.uptimeMillis()+timeout;
        do{
            UiAutomation.class.getMethod("clearCache").invoke(automation);
            List<AccessibilityWindowInfo> windows=automation.getWindowsOnAllDisplays().get(0);
            if(windows!=null)for(AccessibilityWindowInfo w:windows){
                AccessibilityNodeInfo node=match(w.getRoot(),selector,0);
                if(node!=null)return node;
            }
            sleep(100);
        }while(SystemClock.uptimeMillis()<until);
        return null;
    }
    AccessibilityNodeInfo find(String value,long timeout)throws Exception {
        return find(n->value.contentEquals(n.getText()==null?"":n.getText())
                ||value.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()),timeout);
    }
    AccessibilityNodeInfo findExpo(String english,String chinese,long timeout)throws Exception {
        return find(n->PKG.equals(String.valueOf(n.getPackageName()))
                &&(english.equals(String.valueOf(n.getContentDescription()))
                   ||chinese.equals(String.valueOf(n.getContentDescription()))),timeout);
    }
    AccessibilityNodeInfo control(String id,long timeout)throws Exception {
        return find(n->{
            String resource=String.valueOf(n.getViewIdResourceName());
            return PKG.contentEquals(n.getPackageName())&&(resource.equals(id)||resource.endsWith(":id/"+id));
        },timeout);
    }
    AccessibilityNodeInfo node(String value)throws Exception{AccessibilityNodeInfo n=find(value,10000);assertNotNull(value,n);return n;}
    void click(String value)throws Exception {
        click(node(value));
    }
    void click(AccessibilityNodeInfo n)throws Exception {
        assertNotNull("Clickable accessibility node",n);
        String label=String.valueOf(n.getContentDescription());
        while(n!=null){if(n.isClickable()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){sleep(350);return;}n=n.getParent();}
        fail("Cannot click "+label);
    }
    void open(String thread)throws Exception {
        command("/system/bin/am","start","--display","0","-n",PKG+"/.MainActivity","--es","threadId",thread);
        composer();
    }
    void openRoute(String path)throws Exception {
        command("/system/bin/am","start","--display","0","-n",PKG+"/.MainActivity",
                "-a","android.intent.action.VIEW","-d","taku-agent://"+path);
    }
    void capture(String name)throws Exception{command("/system/bin/screencap","-p",OUT+name+".png");}
    void scrollTo(String text)throws Exception{
        int width=getUiDevice().getDisplayWidth(),height=getUiDevice().getDisplayHeight();
        for(int i=0;i<14&&find(text,150)==null;i++){
            int top=height/3,bottom=height*4/5;
            getUiDevice().swipe(width/2,i<7?bottom:top,width/2,i<7?top:bottom,20);
        }
        node(text);
    }
    AccessibilityNodeInfo composer()throws Exception {
        AccessibilityNodeInfo editor=findComposer(20000);
        assertNotNull("Expo chat composer",editor);
        return editor;
    }
    AccessibilityNodeInfo findComposer(long timeout)throws Exception {
        return find(n->{
            if(!PKG.equals(String.valueOf(n.getPackageName()))||!n.isEditable())return false;
            String id=String.valueOf(n.getViewIdResourceName());
            String label=String.valueOf(n.getContentDescription());
            return id.equals("message-input")||id.endsWith(":id/message-input")
                    ||COMPOSER_LABELS.contains(label);
        },timeout);
    }
    String draft()throws Exception {
        AccessibilityNodeInfo editor=composer();
        return editor.isShowingHintText()||editor.getText()==null?"":editor.getText().toString();
    }
    Context systemContext()throws Exception {
        if(context==null){
            Class<?> activityThread=Class.forName("android.app.ActivityThread");
            Object thread=activityThread.getMethod("currentActivityThread").invoke(null);
            if(thread==null)thread=activityThread.getMethod("systemMain").invoke(null);
            context=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        }
        return context;
    }
    JSONObject access(String method,JSONObject args)throws Exception {
        String reply=command("/system/bin/content","call","--uri",OPEN_ACCESS,"--method",method,"--arg",args.toString());
        String prefix="Bundle[{result=";
        int start=reply.indexOf(prefix),end=reply.lastIndexOf("}]");
        assertTrue("Missing Open Access response for "+method,start>=0&&end>start);
        JSONObject envelope=new JSONObject(reply.substring(start+prefix.length(),end));
        if(!envelope.getBoolean("ok"))throw new IOException(envelope.getString("code"));
        return envelope.getJSONObject("result");
    }
    JSONObject composerState(String thread)throws Exception {
        return access("composer/read",new JSONObject().put("threadId",thread));
    }
    JSONObject writeDraft(String thread,String text)throws Exception {
        return access("composer/write",new JSONObject().put("threadId",thread)
                .put("revision",composerState(thread).getString("revision")).put("text",text));
    }
    void expectDraft(String thread,String text)throws Exception {
        long until=SystemClock.uptimeMillis()+10000;
        do {
            if(text.equals(draft())&&text.equals(composerState(thread).getString("text")))return;
            sleep(150);
        }while(SystemClock.uptimeMillis()<until);
        assertEquals("Visible Expo draft",text,draft());
        assertEquals("Saved composer draft",text,composerState(thread).getString("text"));
    }
}
