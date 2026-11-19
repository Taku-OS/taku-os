package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.Configurator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONObject;

/** Requires a configured local Magpie provider. Does not submit model requests. */
public final class ModelConnectionUiTest extends AgentInterfaceTest {
    private static final String CONNECTION = "Main assistant connection";
    private static final String MANAGEMENT = "Manage models and providers";

    public void testModelConnection() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        connect();
        Files.createDirectories(new File(OUT).toPath());
        JSONObject connection = connection();
        assertTrue("Magpie service is running", connection.getBoolean("running"));
        assertEquals("magpie", connection.getString("mode"));
        JSONObject model = connection.getJSONArray("models").getJSONObject(0);
        String name = model.optString("name", model.getString("id"));
        String first = createThread(), second = createThread();
        String firstDraft = "模型管理返回验收，保留中文草稿。";
        String secondDraft = firstDraft + "第二次";
        try {
            editDraft(first, firstDraft);
            showConnection();
            scrollTo(name);
            scrollTo("Refresh");
            click("Refresh");
            scrollTo(MANAGEMENT);
            openManagement();
            returnFromManagement(first, firstDraft, name);

            showConnection();
            openManagement();
            editDraft(second, secondDraft);
            showConnection();
            openManagement();
            returnFromManagement(second, secondDraft, name);
            assertEquals(firstDraft, composerState(first).getString("text"));
            JSONObject after = connection();
            assertEquals(connection.getString("mode"), after.getString("mode"));
            assertEquals(connection.getString("model"), after.getString("model"));
            capture("model-management-draft");
            JSONObject report = new JSONObject().put("browserBackRestoresModelConnection", true)
                    .put("roundTrips", 2).put("reusedBrowserReturnsToLatestOrigin", true)
                    .put("sameConversationAndChineseDraft", true).put("modelMessagesSent", 0);
            Files.writeString(new File(OUT, "model-management-acceptance.json").toPath(), report.toString(2));
        } finally {
            getUiDevice().pressHome();
            for (String thread : new String[]{first, second}) {
                writeDraft(thread, "");
                try { NativeSocketProbe.rpc("thread/archive", new JSONObject().put("threadId", thread)); }
                catch (IOException absent) {
                    if (!absent.getMessage().contains("no rollout found")) throw absent;
                }
            }
        }
    }

    private String createThread() throws Exception {
        return access("composer/createThread", new JSONObject()).getJSONObject("thread").getString("id");
    }

    private JSONObject connection() throws Exception {
        return access("manageSystemAi", new JSONObject().put("operation", "readConnection"));
    }

    private void editDraft(String thread, String text) throws Exception {
        open(thread);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue(composer().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
        expectDraft(thread, text);
    }

    private void showConnection() throws Exception {
        AccessibilityNodeInfo history = findExpo("Conversations", "会话记录", 10000);
        assertNotNull("Expo conversation menu", history);
        click(history);
        String settings = "会话记录".contentEquals(history.getContentDescription()) ? "设置" : "Settings";
        scrollTo(settings);
        click(settings);
        node("Taku AI");
        scrollTo(CONNECTION);
        click(CONNECTION);
        node("ChatGPT subscription");
    }

    private void openManagement() throws Exception {
        scrollTo(MANAGEMENT);
        click(MANAGEMENT);
        node("Providers");
        node("Routing");
        assertNull("Browser must be in front of Expo settings", find("Close settings details", 500));
    }

    private void returnFromManagement(String thread, String draft, String model) throws Exception {
        getUiDevice().pressBack();
        node(CONNECTION);
        scrollTo(model);
        click("Done");
        click("Back");
        expectDraft(thread, draft);
    }
}
