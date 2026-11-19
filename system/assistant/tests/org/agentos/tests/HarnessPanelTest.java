package org.agentos.tests;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.Configurator;
import org.json.JSONObject;

public final class HarnessPanelTest extends AgentInterfaceTest {
    private String prepare() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(0);
        connect();
        String thread = getParams().getString("threadId");
        assertNotNull("Use an existing test conversation", thread);
        return thread;
    }

    private JSONObject manage(String operation, String thread) throws Exception {
        return access("manageAssistant", new JSONObject().put("operation", operation).put("threadId", thread));
    }

    public void testHarnessInspection() throws Exception {
        String thread = prepare();
        JSONObject result = manage("harness/read", thread);
        assertEquals(thread, result.getString("threadId"));
        JSONObject state = result.optJSONObject("state");
        String hash = state == null ? "" : state.optString("prefixHash");
        String version = "Version " + (state == null ? 0 : state.optInt("revision"))
                + (hash.isEmpty() ? "" : " · " + hash.substring(0, Math.min(hash.length(), 12)));
        openRoute("/assistant-management?section=harness&threadId=" + thread);
        node(version);
        click("View stable context");
        String expected = result.getString("background").lines().findFirst().orElseThrow();
        assertTrue("Harness displays the thread's stable context", new com.android.uiautomator.core.UiObject(
                new com.android.uiautomator.core.UiSelector().packageName(PKG).textContains(expected)).waitForExists(10000));
        capture("harness-background");
        scrollTo("Hide stable context");
        click("Hide stable context");
        scrollTo("Reload Harness");
        click("Reload Harness");
        scrollTo(version);
    }

    public void testSkillsSearch() throws Exception {
        String thread = prepare();
        JSONObject skill = manage("skills/read", thread).getJSONArray("skills").getJSONObject(0);
        String name = skill.getString("name");
        String label = skill.optString("displayName", name);
        if (label.isBlank()) label = name;
        label += skill.getBoolean("enabled") ? " · Enabled" : " · Disabled";
        openRoute("/assistant-management?section=skills&threadId=" + thread);
        replaceSearch("taku-no-such-skill-7b1e");
        node("No matching skills.");
        replaceSearch(name);
        click(label);
        if (skill.getBoolean("nativeRecognized")) node("Recognized by runtime");
        else node("Not yet recognized by runtime");
        capture("installed-skill");
    }

    private void replaceSearch(String text) throws Exception {
        AccessibilityNodeInfo input = findExpo("Search skills", "Search skills", 10000);
        assertNotNull("Skill search field", input);
        assertTrue("Skill search is editable", input.isEditable());
        Bundle replacement = new Bundle();
        replacement.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, replacement));
    }
}
