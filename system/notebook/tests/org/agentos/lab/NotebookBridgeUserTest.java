package org.agentos.lab;

import java.io.IOException;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NotebookBridgeUserTest {
    public static void main(String[] args) throws Exception {
        TaskBroker.user = 10;
        expectUserChange(19);
        if (TaskBroker.lastContent != null) throw new AssertionError("Wrong user must not reach the provider");

        TaskBroker.user = TaskBroker.userAfterContent = 19;
        TaskBroker.contentReply = new JSONObject().put("items", new JSONArray());
        JSONObject reply = NotebookBridge.recall(new JSONObject());
        JSONObject receipt = new JSONObject(reply.getJSONArray("contentItems").getJSONObject(0).getString("text"));
        if (receipt.getInt("androidUserId") != 19) throw new AssertionError("Receipt must retain the initiating user");
        assertContentTarget("queryEntries");

        TaskBroker.lastContent = null;
        TaskBroker.userAfterContent = 24;
        expectUserChange(19);
        assertContentTarget("get");
        System.out.println("NotebookBridgeUserTest: content targeting and user-switch rejection passed");
    }

    private static void expectUserChange(int user) throws Exception {
        try {
            NotebookBridge.provider(user, "get", new JSONObject().put("id", "a".repeat(64)));
            throw new AssertionError("Expected ANDROID_USER_CHANGED");
        } catch (IOException changed) {
            if (!"ANDROID_USER_CHANGED".equals(changed.getMessage())) throw changed;
        }
    }

    private static void assertContentTarget(String method) {
        var command = Arrays.asList(TaskBroker.lastContent);
        if (!"19".equals(command.get(command.indexOf("--user") + 1))
                || !method.equals(command.get(command.indexOf("--method") + 1))) {
            throw new AssertionError("Content call must target the captured Android user and exact method");
        }
    }
}
