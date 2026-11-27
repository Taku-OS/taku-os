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

        TaskBroker.user = 19;
        TaskBroker.contentReply = (method, request) -> ok(new JSONObject().put("items", new JSONArray()));
        JSONObject receipt = recallReceipt();
        if (receipt.getInt("androidUserId") != 19) throw new AssertionError("Receipt must retain the initiating user");
        assertContentTarget("queryEntries");

        TaskBroker.lastContent = null;
        TaskBroker.contentReply = (method, request) -> {
            TaskBroker.user = 24;
            return ok(new JSONObject());
        };
        expectUserChange(19);
        assertContentTarget("get");

        recallKeepsEntriesWhenAGrantFails();
        recallRejectsUserSwitchDuringGrant();
        recallPropagatesGrantInterrupt();
        System.out.println("NotebookBridgeUserTest: content targeting, grant fallback, user switching, and interruption passed");
    }

    private static void recallKeepsEntriesWhenAGrantFails() throws Exception {
        String firstId = "a".repeat(64);
        String secondId = "b".repeat(64);
        JSONObject entries = new JSONObject().put("items", new JSONArray()
                .put(new JSONObject().put("id", firstId).put("title", "Original first entry"))
                .put(new JSONObject().put("id", secondId).put("title", "Original second entry")));
        TaskBroker.user = 19;
        TaskBroker.contentReply = (method, request) -> {
            if ("queryEntries".equals(method)) return ok(entries);
            if (!"reference".equals(method)) throw new AssertionError("Unexpected Notebook method: " + method);
            String id = request.getString("id");
            if (firstId.equals(id)) return grantFailure();
            if (secondId.equals(id)) return ok(new JSONObject().put("id", id).put("title", "Re-read second entry"));
            throw new AssertionError("Unexpected reference ID");
        };
        JSONArray items = recallReceipt().getJSONArray("items");
        if (items.length() != 2 || !"Original first entry".equals(items.getJSONObject(0).getString("title"))
                || !"Re-read second entry".equals(items.getJSONObject(1).getString("title"))) {
            throw new AssertionError("Grants must fall back per entry and preserve result order");
        }
        assertContentTarget("reference");
    }

    private static void recallRejectsUserSwitchDuringGrant() throws Exception {
        TaskBroker.user = 19;
        TaskBroker.contentReply = (method, request) -> {
            if ("queryEntries".equals(method)) return oneEntry();
            TaskBroker.user = 24;
            return grantFailure();
        };
        try {
            recallReceipt();
            throw new AssertionError("Recall must reject a user switch during a grant");
        } catch (IOException changed) {
            if (!"ANDROID_USER_CHANGED".equals(changed.getMessage())) throw changed;
        }
        assertContentTarget("reference");
    }

    private static void recallPropagatesGrantInterrupt() throws Exception {
        TaskBroker.user = 19;
        TaskBroker.contentReply = (method, request) -> {
            if ("queryEntries".equals(method)) return oneEntry();
            throw new InterruptedException("Interrupted during Notebook grant");
        };
        try {
            recallReceipt();
            throw new AssertionError("Recall must propagate a grant interruption");
        } catch (InterruptedException expected) {
            assertContentTarget("reference");
        }
    }

    private static JSONObject recallReceipt() throws Exception {
        JSONObject reply = NotebookBridge.recall(new JSONObject());
        return new JSONObject(reply.getJSONArray("contentItems").getJSONObject(0).getString("text"));
    }

    private static JSONObject oneEntry() {
        return ok(new JSONObject().put("items", new JSONArray().put(new JSONObject().put("id", "a".repeat(64)))));
    }

    private static JSONObject ok(JSONObject result) {
        return new JSONObject().put("ok", true).put("result", result);
    }

    private static JSONObject grantFailure() {
        return new JSONObject().put("ok", false).put("error", "ENTRY_NOT_FOUND");
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
