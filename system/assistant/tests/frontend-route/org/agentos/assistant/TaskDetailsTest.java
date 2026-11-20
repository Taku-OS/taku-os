package org.agentos.assistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.json.JSONObject;

public final class TaskDetailsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("taku-task-details-");
        String id = "1771234567000";
        String thread = "f891f77d-019c-48e1-8935-75ee445126c1";
        Path task = Files.createDirectories(root.resolve("runtime/tasks/" + id));
        Path request = task.resolve("request.json");
        Path state = task.resolve("state.json");
        try {
            JSONObject original = new JSONObject().put("id", id).put("conversationId", thread);
            JSONObject receipt = new JSONObject().put("id", id).put("state", "needs_user")
                    .put("prompt", "Collect the order").put("message", "Choose a shop")
                    .put("debug", "not for the task page");
            Files.writeString(request, original.toString());
            Files.writeString(state, receipt.toString());
            if (!thread.equals(TaskDetails.notificationThread(root.toFile(), id)))
                throw new AssertionError("Notification lost its original thread");

            original.remove("conversationId");
            Files.writeString(request, original.toString());
            Files.writeString(task.resolve("runtime-info.json"), new JSONObject().put("threadId", thread).toString());
            if (TaskDetails.notificationThread(root.toFile(), id) != null)
                throw new AssertionError("A thread absent from the original request must not replace task details");
            JSONObject detail = TaskDetails.read(root.toFile(), id);
            if (detail.length() != 4 || !id.equals(detail.getString("taskId"))
                    || !"needs_user".equals(detail.getString("state"))
                    || !"Collect the order".equals(detail.getString("prompt"))
                    || !"Choose a shop".equals(detail.getString("message")))
                throw new AssertionError("Task detail must project the saved public fields");

            Files.writeString(request, original.put("conversationId", "f891f77d-019c48e1-8935-75ee445126c1-").toString());
            if (TaskDetails.notificationThread(root.toFile(), id) != null)
                throw new AssertionError("Invalid thread ID was accepted");
            Files.writeString(state, receipt.put("id", "1771234567001").toString());
            expectError(root, id, "TASK_UNAVAILABLE");
            Files.delete(request);
            expectError(root, id, "TASK_NOT_FOUND");
            expectError(root, "../" + id, "INVALID_TASK_ID");
        } finally {
            try (var entries = Files.walk(root)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
            }
        }
    }

    private static void expectError(Path root, String id, String expected) throws Exception {
        try {
            TaskDetails.read(root.toFile(), id);
            throw new AssertionError("Expected " + expected);
        } catch (IOException failure) {
            if (!expected.equals(failure.getMessage())) throw failure;
        }
    }
}
