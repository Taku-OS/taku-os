package org.agentos.tests;

import java.io.File;
import org.json.JSONObject;
import com.android.uiautomator.core.Configurator;

/** Requires a configured local provider; run separately from the basic assistant suite. */
public final class ModelConnectionUiTest extends AgentInterfaceTest {
    public void testModelConnection()throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);connect();new File(OUT).mkdirs();
        String original=preference("conversation"), owned="", other="";
        String draftText="模型管理返回验收，保留中文草稿。";
        JSONObject state=NativeSocketProbe.exchange(new JSONObject().put("op","modelConnection"));
        assertTrue(state.getBoolean("running"));
        try {
            open(original);option("助手设置");scrollTo("Model connection");click("Model connection");
            node("Model connection status");node("Manage models and routes");node("Refresh model service");
            org.json.JSONArray models=state.getJSONArray("models");assertTrue(models.length()>0);
            String name=models.getJSONObject(0).getString("name");node(name);
            capture("model-connection");
            click("Refresh model service");node(name);
            assertEquals("magpie",NativeSocketProbe.exchange(new JSONObject().put("op","modelConnection")).getString("mode"));
            assertEquals("magpie",preference("modelConnectionMode"));
            capture("model-connected");
            // This navigation test does not restart the runtime or submit a model request.
            owned=NativeSocketProbe.rpc("thread/start", new JSONObject()).getJSONObject("thread").getString("id");
            open(owned);
            android.os.Bundle input = new android.os.Bundle();
            input.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draftText);
            assertTrue(node("Taku 对话输入框").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, input));
            assertEquals(owned,preference("conversation"));
            option("助手设置");scrollTo("Model connection");click("Model connection");
            click("Manage models and routes");
            node("Providers");
            node("Routing");
            assertNull("Assistant overlay dismisses for model management", find("Model connection status", 500));
            // Never include an authentication query in a screenshot or test log.
            boolean privateQuery = false;
            for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows())
                privateQuery |= hasPrivateQuery(window.getRoot(), 0);
            if (!privateQuery) capture("model-management");
            command("/system/bin/input", "-d", "0", "keyevent", "KEYCODE_BACK");
            node("Model connection status");node(name);
            assertEquals(owned, preference("conversation"));
            capture("model-management-return");
            click("返回上一级");node("Your Taku");click("返回对话");
            assertEquals(draftText,draft());
            capture("model-management-draft");
            // Re-enter from another conversation while the management browser is still open.
            option("助手设置");scrollTo("Model connection");click("Model connection");
            click("Manage models and routes");node("Providers");
            other=NativeSocketProbe.rpc("thread/start",new JSONObject()).getJSONObject("thread").getString("id");
            open(other);
            input.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draftText+"第二次");
            assertTrue(node("Taku 对话输入框").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,input));
            assertEquals(other,preference("conversation"));
            option("助手设置");scrollTo("Model connection");click("Model connection");
            click("Manage models and routes");node("Providers");
            command("/system/bin/input", "-d", "0", "keyevent", "KEYCODE_BACK");
            node("Model connection status");
            assertEquals(other,preference("conversation"));
            click("返回上一级");click("返回对话");assertEquals(draftText+"第二次",draft());
            assertEquals(draftText,preference("draft-"+owned));
            JSONObject report=new JSONObject().put("browserBackRestoresModelConnection",true)
                    .put("roundTrips",2).put("reusedBrowserReturnsToLatestOrigin",true)
                    .put("sameConversationAndChineseDraft",true).put("modelMessagesSent",0);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(OUT+"model-management-acceptance.json")) {
                out.write(report.toString(2).getBytes("UTF-8"));
            }
        } finally {
            open(original);
            for(String thread:new String[]{owned,other})if(!thread.isEmpty())try {
                NativeSocketProbe.rpc("thread/archive",new JSONObject().put("threadId",thread));
            } catch(java.io.IOException absent) {
                // A thread with no submitted turn may have no rollout to archive.
                if(!absent.getMessage().contains("no rollout found"))throw absent;
            }
        }
    }

    private boolean hasPrivateQuery(android.view.accessibility.AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 50) return false;
        if (String.valueOf(node.getText()).contains("?k=")) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasPrivateQuery(node.getChild(i), depth + 1)) return true;
        return false;
    }

}
