package org.agentos.lab;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only fixtures for broker file operations and scoped Android content calls. */
final class TaskBroker {
    @FunctionalInterface
    interface ContentReply {
        JSONObject reply(String method, JSONObject args) throws Exception;
    }

    static int user;
    static String[] lastContent;
    static ContentReply contentReply;

    static String read(File file) throws Exception {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    static String command(String... command) throws Exception {
        if (Arrays.equals(command, new String[]{"/system/bin/am", "get-current-user"})) {
            return Integer.toString(user);
        }
        if (command.length > 3 && "/system/bin/content".equals(command[2]) && "call".equals(command[3])) {
            lastContent = command;
            var arguments = Arrays.asList(command);
            String method = arguments.get(arguments.indexOf("--method") + 1);
            JSONObject request = new JSONObject(arguments.get(arguments.indexOf("--arg") + 1));
            return "Result: Bundle[{result=" + contentReply.reply(method, request) + "}]";
        }
        throw new AssertionError("Unexpected command in Notebook host fixture");
    }
}

final class PhoneNativeTools {
    static JSONObject text(String value) {
        return new JSONObject().put("success", true).put("contentItems",
                new JSONArray().put(new JSONObject().put("type", "inputText").put("text", value)));
    }
}

final class WorkspacePolicy {
    static JSONObject snapshot() { throw new AssertionError("App discovery is outside this Notebook test"); }
    static boolean isAppAllowed(String ignored) { throw new AssertionError("App policy is outside this Notebook test"); }
}
