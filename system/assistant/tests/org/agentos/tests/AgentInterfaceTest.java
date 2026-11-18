package org.agentos.tests;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.*;
import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Real display-0 navigation, draft, IME and native-event rendering acceptance. */
public class AgentInterfaceTest extends UiAutomatorTestCase {
    static final String PKG="org.agentos.assistant";
    String OUT="/data/local/tmp/taku-agent-interface/";
    UiAutomation automation;

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
        info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
    }
    AccessibilityNodeInfo match(AccessibilityNodeInfo node,String value,int depth){
        if(node==null||depth>50)return null;
        if(node.isVisibleToUser()&&(value.contentEquals(node.getText()==null?"":node.getText())
                ||value.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=match(node.getChild(i),value,depth+1);if(found!=null)return found;}
        return null;
    }
    AccessibilityNodeInfo find(String value,long timeout)throws Exception {
        long until=System.currentTimeMillis()+timeout;
        do{
            UiAutomation.class.getMethod("clearCache").invoke(automation);
            List<AccessibilityWindowInfo> windows=automation.getWindowsOnAllDisplays().get(0);
            if(windows!=null)for(AccessibilityWindowInfo w:windows){AccessibilityNodeInfo n=match(w.getRoot(),value,0);if(n!=null)return n;}
            sleep(100);
        }while(System.currentTimeMillis()<until);
        return null;
    }
    AccessibilityNodeInfo node(String value)throws Exception{AccessibilityNodeInfo n=find(value,10000);assertNotNull(value,n);return n;}
    void click(String value)throws Exception {
        AccessibilityNodeInfo n=node(value);
        while(n!=null){if(n.isClickable()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){sleep(350);return;}n=n.getParent();}
        fail("Cannot click "+value);
    }
    String preference(String key)throws Exception {
        org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();
        try(FileInputStream in=new FileInputStream("/data/user/0/"+PKG+"/shared_prefs/agent-settings.xml")){
            parser.setInput(in,"UTF-8");
            while(parser.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
                if(parser.getEventType()==org.xmlpull.v1.XmlPullParser.START_TAG&&key.equals(parser.getAttributeValue(null,"name")))return parser.nextText();
        }
        return "";
    }
    void open(String thread)throws Exception {
        command("/system/bin/am","start","--display","0","-n",PKG+"/.PanelActivity","--es","conversationId",thread);
        node("Taku 对话输入框"); sleep(500);
    }
    void capture(String name)throws Exception{command("/system/bin/screencap","-p",OUT+name+".png");}
    void scrollTo(String text)throws Exception{
        for(int i=0;i<5&&find(text,100)==null;i++)command("/system/bin/input","-d","0","swipe","540","1750","540","700","300");
        node(text);
    }
    void option(String value)throws Exception{click("更多选项");click(value);}
    void theme(String value)throws Exception{option("助手设置");scrollTo("Appearance");click("Appearance");click(value.equals("light")?"Light":"Dark");}
    String draft()throws Exception{return String.valueOf(node("Taku 对话输入框").getText());}

    public void testNavigationAndDraft()throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);connect();new File(OUT).mkdirs();
        String original=preference("conversation"),originalTheme=preference("theme"),owned="";
        JSONObject result=new JSONObject();
        String text="改版验收：保留中文草稿，不发送。";
        try{
            command("/system/bin/am","start","--display","0","-a","android.settings.SETTINGS");open(original);
            click("New conversation");
            for(int i=0;i<100;i++){owned=preference("conversation");if(!owned.equals(original)&&!owned.isEmpty())break;sleep(100);}
            assertFalse(original.equals(owned));sleep(800);capture("empty-dark");
            Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
            assertTrue(node("Taku 对话输入框").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));sleep(300);
            option("选择模型");node("Runtime default");command("/system/bin/input","-d","0","keyevent","KEYCODE_BACK");
            option("Reasoning effort");sleep(500);command("/system/bin/input","-d","0","keyevent","KEYCODE_BACK");
            assertEquals(text,draft());result.put("modelAndEffortMenusPreserveDraft",true);
            option("Capabilities");node("Models and responses");click("返回上一级");assertEquals(text,draft());
            click("切换会话");node("New conversation");click("返回对话");assertEquals(text,draft());
            result.put("capabilitiesAndSessionsReturnToChat",true);
            option("助手设置");capture("settings-dark");click("Model and reasoning");node("Models and responses");
            click("返回上一级");node("Your Taku");click("返回对话");assertEquals(text,draft());
            result.put("directSettingsRouteReturnsToSettings",true);
            theme("light");capture("settings-light");click("返回对话");assertEquals(text,draft());capture("draft-light");
            click("Taku 对话输入框");sleep(800);Rect input=new Rect();node("Taku 对话输入框").getBoundsInScreen(input);
            assertTrue("Composer stays above IME",input.bottom<getUiDevice().getDisplayHeight()*.8);capture("keyboard-light");
            command("/system/bin/input","-d","0","keyevent","KEYCODE_BACK");
            result.put("themeAndKeyboardPreserveDraft",true);
            theme("dark");click("返回对话");
            option("后台屏");assertTrue(command("/system/bin/dumpsys","activity","activities").contains("MirrorActivity"));
            command("/system/bin/input","-d","0","keyevent","KEYCODE_BACK");open(owned);assertEquals(text,draft());
            result.put("backgroundScreenReturnPreservesDraft",true);
            command("/system/bin/input","-d","0","keyevent","KEYCODE_BACK");sleep(300);
            assertNull("System assistant dismisses",find("Taku 对话输入框",500));
            result.put("systemDismiss",true).put("modelMessagesSent",0).put("testThread",owned);
        } finally {
            open(original);
            if(!preference("theme").equals(originalTheme)){theme(originalTheme);click("返回对话");}
            if(!owned.isEmpty()&&!owned.equals(original))NativeSocketProbe.rpc("thread/archive",new JSONObject().put("threadId",owned));
        }
        try(FileOutputStream out=new FileOutputStream(OUT+"acceptance.json")){out.write(result.toString(2).getBytes("UTF-8"));}
    }
    public void testNativeItems() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0); connect(); new File(OUT).mkdirs();
        String original = preference("conversation"), originalTheme = preference("theme");
        String thread = "", turnId = "";
        JSONObject result = new JSONObject();
        try {
            thread = NativeSocketProbe.rpc("thread/start", new JSONObject()).getJSONObject("thread").getString("id");
            NativeSocketProbe.rpc("thread/name/set", new JSONObject().put("threadId", thread).put("name", "设备连接检查"));
            open(thread); theme("light"); click("返回对话");
            String prompt = "请只执行一次命令：/system/bin/printf 'Taku 已连接\\n'; /system/bin/sleep 12; /system/bin/printf '检查完成\\n'。完成后用中文简短汇报，分为‘连接状态’和‘执行结果’两个小标题。";
            JSONObject started = NativeSocketProbe.rpc("turn/start", new JSONObject().put("threadId", thread)
                    .put("input", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", prompt))));
            turnId = started.getJSONObject("turn").getString("id");
            node("展开执行过程");
            assertNull("Tools are collapsed by default", find("展开工具：运行命令 · 进行中", 300));
            capture("native-collapsed-running-light");
            click("展开执行过程");
            node("展开工具：运行命令 · 进行中");
            click("展开工具：运行命令 · 进行中");
            capture("native-running-light");
            JSONObject turn = null;
            long until = System.currentTimeMillis() + 90000;
            do {
                org.json.JSONArray turns = NativeSocketProbe.rpc("thread/read", new JSONObject().put("threadId", thread).put("includeTurns", true))
                        .getJSONObject("thread").getJSONArray("turns");
                for (int i = 0; i < turns.length(); i++) if (turnId.equals(turns.getJSONObject(i).optString("id"))) turn = turns.getJSONObject(i);
                if (turn != null && !turn.optString("status").equals("inProgress")) break;
                sleep(300);
            } while (System.currentTimeMillis() < until);
            assertNotNull("native turn", turn);
            assertEquals("completed", turn.optString("status"));
            node("收起工具：运行命令 · 已结束");
            click("收起工具：运行命令 · 已结束");
            node("复制回复");
            node("收起执行过程");
            theme("dark"); click("返回对话"); node("收起执行过程");
            capture("native-expanded-dark");
            click("收起执行过程"); capture("native-completed-dark");
            theme("light"); click("返回对话"); node("展开执行过程"); capture("native-completed-light");
            result.put("threadId", thread).put("turnId", turnId).put("modelResponsesSimulated", false)
                    .put("workLogCollapsedByDefault", true).put("expansionSurvivesThemeAndUpdates", true)
                    .put("expandedToolSurvivesStream", true).put("nativeCompletionRendered", true)
                    .put("lightAndDark", true).put("source", "Codex app-server");
        } finally {
            if (!thread.isEmpty() && !turnId.isEmpty()) try {
                NativeSocketProbe.rpc("turn/interrupt", new JSONObject().put("threadId", thread).put("turnId", turnId));
            } catch (Exception ignored) {}
            open(original);
            if (!preference("theme").equals(originalTheme)) { theme(originalTheme); click("返回对话"); }
            if (!thread.isEmpty()) NativeSocketProbe.rpc("thread/archive", new JSONObject().put("threadId", thread));
        }
        try (FileOutputStream out = new FileOutputStream(OUT + "native-acceptance.json")) {
            out.write(result.toString(2).getBytes("UTF-8"));
        }
    }

    public void testNativeChineseStream() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0); connect(); new File(OUT).mkdirs();
        String original = preference("conversation"), thread = "", turnId = "";
        JSONObject result = new JSONObject();
        try {
            thread = NativeSocketProbe.rpc("thread/start", new JSONObject()).getJSONObject("thread").getString("id");
            NativeSocketProbe.rpc("thread/name/set", new JSONObject().put("threadId", thread).put("name", "中文流式验收"));
            open(thread);
            String prompt = "不要调用工具。直接用中文写一篇约三百五十字的短文，讲述海边小城从清晨到夜晚的变化，分成三个自然段。第一段开头必须是‘清晨的海边’，文末必须是‘晚安，小城。’。不用标题、不用列表。";
            JSONObject started = NativeSocketProbe.rpc("turn/start", new JSONObject().put("threadId", thread)
                    .put("input", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", prompt))));
            turnId = started.getJSONObject("turn").getString("id");
            File projection = new File("/data/user/0/" + PKG + "/files/runtime/agent-view/" + thread + ".json");
            boolean partialVisible = false, completed = false;
            Set<Integer> lengths = new HashSet<>();
            long until = System.currentTimeMillis() + 240000;
            while (System.currentTimeMillis() < until) {
                if (projection.isFile()) {
                    JSONObject snapshot = new JSONObject(new String(java.nio.file.Files.readAllBytes(projection.toPath()), "UTF-8"));
                    org.json.JSONArray turns = snapshot.getJSONArray("turns");
                    for (int i = 0; i < turns.length(); i++) {
                        JSONObject turn = turns.getJSONObject(i);
                        if (!turnId.equals(turn.optString("id"))) continue;
                        boolean active = turn.optString("status").equals("inProgress");
                        org.json.JSONArray items = turn.getJSONArray("items");
                        for (int j = 0; j < items.length(); j++) {
                            JSONObject item = items.getJSONObject(j);
                            if (!item.optString("type").equals("agentMessage")) continue;
                            String body = item.optString("text");
                            if (active && body.startsWith("清晨的海边")) lengths.add(body.length());
                        }
                        if (active && !partialVisible && streamingTextVisible()) {
                            capture("native-chinese-streaming"); partialVisible = true;
                        }
                        completed = turn.optString("status").equals("completed");
                    }
                }
                if (completed) break;
                sleep(180);
            }
            assertTrue("Chinese text rendered before native completion", partialVisible);
            assertTrue("Several real Chinese deltas observed", lengths.size() > 1);
            assertTrue("Native stream completed", completed);
            node("复制回复"); capture("native-chinese-completed");
            result.put("threadId", thread).put("turnId", turnId).put("partialChineseVisibleWhileRunning", true)
                    .put("distinctNativeLengths", lengths.size()).put("nativeCompletionRendered", true)
                    .put("modelResponsesSimulated", false);
        } finally {
            if (!thread.isEmpty() && !turnId.isEmpty()) try {
                NativeSocketProbe.rpc("turn/interrupt", new JSONObject().put("threadId", thread).put("turnId", turnId));
            } catch (Exception ignored) {}
            open(original);
            if (!thread.isEmpty()) NativeSocketProbe.rpc("thread/archive", new JSONObject().put("threadId", thread));
        }
        try (FileOutputStream out = new FileOutputStream(OUT + "chinese-stream-acceptance.json")) {
            out.write(result.toString(2).getBytes("UTF-8"));
        }
    }

    public void testNativeMarkdown() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0); connect(); new File(OUT).mkdirs();
        String original = preference("conversation"), originalTheme = preference("theme"), thread = "", turnId = "";
        JSONObject result = new JSONObject();
        try {
            thread = NativeSocketProbe.rpc("thread/start", new JSONObject()).getJSONObject("thread").getString("id");
            NativeSocketProbe.rpc("thread/name/set", new JSONObject().put("threadId", thread).put("name", "Markdown 排版验收"));
            open(thread); theme("light"); click("返回对话");
            String prompt = "不要调用工具，直接输出下面这段 Markdown，不要把整篇包进代码块，不要添加前言或结语：\n"
                    + "## 排版验收\n\n中文**加粗**与*斜体*，以及 `inline_code`。\n\n"
                    + "> 引用中的中文保持连贯。\n\n- [x] 中文流式\n- [ ] 继续验证\n\n"
                    + "```javascript\nconst greeting = \"你好 **源码**\";\nconsole.log(greeting);\n```\n\n"
                    + "| 能力 | 状态 |\n| --- | --- |\n| 代码复制 | 通过 |\n| 中文输出 | 通过 |\n\n"
                    + "[项目文档](https://github.com/Taku-OS/taku-os)\n\nMarkdown 验证结束。";
            JSONObject started = NativeSocketProbe.rpc("turn/start", new JSONObject().put("threadId", thread)
                    .put("input", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", prompt))));
            turnId = started.getJSONObject("turn").getString("id");
            JSONObject turn = null;
            long until = System.currentTimeMillis() + 180000;
            do {
                org.json.JSONArray turns = NativeSocketProbe.rpc("thread/read", new JSONObject().put("threadId", thread).put("includeTurns", true))
                        .getJSONObject("thread").getJSONArray("turns");
                for (int i = 0; i < turns.length(); i++) if (turnId.equals(turns.getJSONObject(i).optString("id"))) turn = turns.getJSONObject(i);
                if (turn != null && !turn.optString("status").equals("inProgress")) break;
                sleep(300);
            } while (System.currentTimeMillis() < until);
            assertNotNull(turn); assertEquals("completed", turn.optString("status"));
            scrollTo("Markdown 验证结束。"); node("项目文档");
            capture("markdown-completed-light");
            for (int i = 0; i < 8 && find("排版验收", 100) == null; i++)
                command("/system/bin/input", "-d", "0", "swipe", "540", "650", "540", "1650", "300");
            node("排版验收"); node("中文加粗与斜体，以及 inline_code。");
            capture("markdown-top-light");
            scrollTo("复制代码"); node("javascript");
            node("const greeting = \"你好 **源码**\";\nconsole.log(greeting);\n");
            capture("markdown-code-light");
            theme("dark"); click("返回对话"); scrollTo("Table; scroll horizontally");
            capture("markdown-table-dark");
            result.put("threadId", thread).put("turnId", turnId).put("modelResponsesSimulated", false)
                    .put("formattedHeadingsAndInlineText", true).put("codeLiteralPreserved", true)
                    .put("tableAndLinkRendered", true).put("lightAndDark", true);
        } finally {
            if (!thread.isEmpty() && !turnId.isEmpty()) try {
                NativeSocketProbe.rpc("turn/interrupt", new JSONObject().put("threadId", thread).put("turnId", turnId));
            } catch (Exception ignored) {}
            open(original);
            if (!preference("theme").equals(originalTheme)) { theme(originalTheme); click("返回对话"); }
            if (!thread.isEmpty()) NativeSocketProbe.rpc("thread/archive", new JSONObject().put("threadId", thread));
        }
        try (FileOutputStream out = new FileOutputStream(OUT + "markdown-acceptance.json")) { out.write(result.toString(2).getBytes("UTF-8")); }
    }

    boolean streamingTextVisible() {
        List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(0);
        if (windows != null) for (AccessibilityWindowInfo window : windows)
            if (streamingText(window.getRoot(), 0)) return true;
        return false;
    }

    boolean streamingText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 50) return false;
        if (node.isVisibleToUser() && node.getText() != null && node.getText().toString().startsWith("清晨的海边")) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (streamingText(node.getChild(i), depth + 1)) return true;
        return false;
    }

}
