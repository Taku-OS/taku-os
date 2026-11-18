package org.agentos.assistant;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** One task contract shared by the Home editor and the system assistant. */
final class TaskRequests {
    interface BeforePublish { void persist(String taskId) throws Exception; }
    static void write(File file, String value) throws IOException {
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temporary.renameTo(file)) throw new IOException("Save failed");
    }

    static String submit(String prompt, String foreground, Bitmap screen) throws Exception {
        return submit(prompt, foreground, screen, null);
    }

    static String submit(String prompt, String foreground, Bitmap screen, String conversation)
            throws Exception {
        return submit(prompt, foreground, screen, conversation, null);
    }

    static String submit(
            String prompt,
            String foreground,
            Bitmap screen,
            String conversation,
            JSONObject options)
            throws Exception {
        return submit(prompt, foreground, screen, conversation, options, null);
    }

    static String submit(String prompt, String foreground, Bitmap screen, String conversation,
            JSONObject options, BeforePublish beforePublish) throws Exception {
        if (prompt.trim().isEmpty() || prompt.length() > 16000)
            throw new IOException("Enter a request of 1 to 16000 characters");
        if (conversation != null && !conversation.matches("[a-f0-9-]{36}"))
            throw new IOException("Invalid conversation identifier");
        AssistantSession.TASKS.mkdirs();
        File directory = null;
        String id = "";
        long now = System.currentTimeMillis();
        for (int attempt = 0; attempt < 100; attempt++) {
            id = Long.toString(now + attempt);
            File candidate = new File(AssistantSession.TASKS, id);
            if (candidate.mkdir()) {
                directory = candidate;
                break;
            }
        }
        if (directory == null) throw new IOException("Task directory unavailable");
        String screenshot = "";
        if (screen != null) {
            File image = new File(directory, "context.png");
            try (FileOutputStream out = new FileOutputStream(image)) {
                screen.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.getFD().sync();
            }
            screenshot = image.getAbsolutePath();
        }
        JSONObject request =
                new JSONObject()
                        .put("id", id)
                        .put("prompt", prompt)
                        .put("foreground", foreground)
                        .put("screenshot", screenshot)
                        .put("createdAt", now);
        if (conversation != null) {
            request.put("conversationId", conversation);
            if (options != null) {
                request.put("includeScreenshot", options.optBoolean("includeScreenshot", false));
                String style = options.optString("responseStyle", "concise");
                if (!style.equals("concise") && !style.equals("detailed"))
                    throw new IOException("Invalid response style");
                request.put("responseStyle", style);
                for (String key : new String[]{"model", "effort", "multiAgentMode", "permissionMode", "serviceTier"})
                    if (options.has(key)) request.put(key, options.opt(key));
                if (options.has("attachments")) request.put("attachments", options.getJSONArray("attachments"));
                if (options.has("panelUpdate")) {
                    JSONObject target = options.getJSONObject("panelUpdate");
                    PanelProtocol.exact(target, java.util.Set.of("panelId", "originThreadId", "matterId", "sourceVersion", "requestId"));
                    PanelProtocol.uuid(target, "panelId");
                    PanelProtocol.uuid(target, "originThreadId");
                    PanelProtocol.uuid(target, "requestId");
                    AgentGatewayProtocol.matterIdentity(target.getString("matterId"));
                    PanelProtocol.INSTANCE.number(target, "sourceVersion");
                    if (!conversation.equals(target.getString("originThreadId")) || !target.getString("matterId").startsWith(conversation + "/"))
                        throw new IOException("INVALID_ARGUMENT");
                    request.put("panelUpdate", new JSONObject(target.toString()));
                }
            }
        }
        write(new File(directory, "request.json"), request.toString());
        write(
                new File(directory, "state.json"),
                new JSONObject()
                        .put("id", id)
                        .put("state", "queued")
                        .put("prompt", prompt)
                        .put("message", "Queued for background execution")
                        .toString());
        write(new File(AssistantSession.ROOT, "latest.id"), id);
        if (beforePublish != null) beforePublish.persist(id);
        write(new File(directory, "ready"), "1");
        return id;
    }

    static JSONObject control(String method, JSONObject params) throws Exception {
        AssistantSession.TASKS.mkdirs();
        File directory = null;
        String id = "";
        long now = System.currentTimeMillis();
        for (int attempt = 0; attempt < 100; attempt++) {
            id = Long.toString(now + attempt);
            File candidate = new File(AssistantSession.TASKS, id);
            if (candidate.mkdir()) {
                directory = candidate;
                break;
            }
        }
        if (directory == null) throw new IOException("Conversation service unavailable");
        write(
                new File(directory, "request.json"),
                new JSONObject()
                        .put("id", id)
                        .put("kind", "agent_control")
                        .put("prompt", "")
                        .put("method", method)
                        .put("params", params)
                        .toString());
        write(
                new File(directory, "state.json"),
                new JSONObject()
                        .put("id", id)
                        .put("state", "queued")
                        .put("prompt", "")
                        .put("message", "")
                        .toString());
        write(new File(directory, "ready"), "1");
        File response = new File(directory, "response.json");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(method.startsWith("skills/community/")?120:40);
        while (!response.exists()) {
            if (System.nanoTime() > deadline) throw new IOException("Conversation service connection timed out");
            Thread.sleep(60);
        }
        try (FileInputStream input = new FileInputStream(response)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, n);
                if (bytes.size() > 2097152)
                    throw new IOException("Conversation service response exceeds the safety limit; native conversation data was not changed");
            }
            JSONObject answer = new JSONObject(bytes.toString("UTF-8"));
            if (!answer.optBoolean("ok"))
                throw new IOException(answer.optString("message", "Conversation service unavailable"));
            return answer.getJSONObject("result");
        }
    }
}
