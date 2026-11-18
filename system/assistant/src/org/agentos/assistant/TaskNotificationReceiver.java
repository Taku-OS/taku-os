package org.agentos.assistant;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class TaskNotificationReceiver extends BroadcastReceiver {
    private static final java.util.concurrent.ExecutorService PANEL_COMPLETIONS = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context c, Intent in) {
        try {
            if ("org.agentos.assistant.WORKSPACE_REVOKED".equals(in.getAction())) {
                showRevocation(c, new JSONObject(in.getStringExtra("revocation")));
                return;
            }
            String id = in.getStringExtra("task_id");
            if (id == null || !id.matches("[0-9]+")) return;
            final PendingResult pending = goAsync();
            PANEL_COMPLETIONS.execute(() -> {
                try { MatterRuntime.get(c).taskChanged(id); }
                catch (Exception failure) { android.util.Log.w("PanelCompletion", "Durable completion will retry during recovery", failure); }
                finally { pending.finish(); }
            });
            if(BackgroundScreenTasks.ownsTask(c,id)) {
                BackgroundScreenTasks.restore(c);
                return; // Progress, questions and results are owned by the system surface.
            }
            File file = new File(c.getFilesDir(), "runtime/tasks/" + id + "/state.json");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (FileInputStream f = new FileInputStream(file)) {
                byte[] x = new byte[4096];
                int n;
                while ((n = f.read(x)) > 0) {
                    b.write(x, 0, n);
                    if (b.size() > 65536) break;
                }
            }
            JSONObject state = new JSONObject(b.toString("UTF-8"));
            boolean running = state.optString("state").equals("running");
            boolean failed = state.optString("state").equals("failed");
            NotificationManager nm = taskNotifications(c);
            Intent open =
                    new Intent(c, PanelActivity.class)
                            .putExtra("task_id", id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            File requestFile = new File(file.getParentFile(), "request.json");
            try (FileInputStream requestInput = new FileInputStream(requestFile)) {
                ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = requestInput.read(buffer)) != -1) {
                    requestBytes.write(buffer, 0, count);
                    if (requestBytes.size() > 65536) throw new IOException("request too large");
                }
                String conversation =
                        new JSONObject(requestBytes.toString("UTF-8")).optString("conversationId");
                if (conversation.matches("[a-f0-9-]{36}"))
                    open =
                            new Intent(c, PanelActivity.class)
                                    .putExtra("conversationId", conversation)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            PendingIntent pi =
                    PendingIntent.getActivity(
                            c,
                            0,
                            open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean needs = state.optString("state").equals("needs_user");
            String title =
                    state.optString("state").equals("cancelled")
                            ? "Taku stopped"
                            : needs
                                    ? "Taku needs your attention"
                                    : running ? "Taku is running in the background" : failed ? "Taku execution failed" : "Taku completed";
            String message = running ? state.optString("prompt") : state.optString("message");
            Notification n =
                    new Notification.Builder(c, "codex_tasks")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(message)
                            .setStyle(new Notification.BigTextStyle().bigText(message))
                            .setContentIntent(pi)
                            .setOnlyAlertOnce(true)
                            .setOngoing(running)
                            .setAutoCancel(!running)
                            .build();
            nm.notify(42, n);
        } catch (Exception e) {
            android.util.Log.e("AgentOSNotify", "Cannot update task notification", e);
        }
    }

    private static NotificationManager taskNotifications(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                "codex_tasks", "Taku phone task", NotificationManager.IMPORTANCE_DEFAULT));
        return manager;
    }

    private static void showRevocation(Context context, JSONObject receipt) throws Exception {
        int copy = switch (receipt.getString("status")) {
            case "released" -> R.string.workspace_revocation_released;
            case "retained" -> R.string.workspace_revocation_retained;
            case "failed" -> R.string.workspace_revocation_failed;
            default -> throw new IllegalArgumentException("Unexpected workspace revocation status");
        };
        List<String> labels = new ArrayList<>();
        JSONArray packages = "released".equals(receipt.getString("status")) && receipt.has("released")
                ? receipt.getJSONArray("released") : receipt.getJSONArray("packageNames");
        PackageManager manager = context.getPackageManager();
        for (int i = 0; i < packages.length(); i++) {
            String pkg = packages.getString(i);
            try {
                labels.add(manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString());
            } catch (PackageManager.NameNotFoundException missing) {
                labels.add(pkg);
            }
        }
        String task = receipt.optString("taskTitle");
        if (task.isBlank()) task = context.getString(R.string.workspace_task_fallback);
        String message = context.getString(copy, String.join(", ", labels), task);
        message += " " + context.getString(receipt.optBoolean("screenRetained")
                ? R.string.workspace_revocation_screen_retained : R.string.workspace_revocation_screen_released);
        Intent open = new Intent(context, BackgroundWorkspacesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent link = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, "codex_tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.workspace_revocation_title))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(link)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();
        String tag = "workspace-revocation-" + receipt.getString("screenKey")
                + "-" + receipt.getString("threadId");
        taskNotifications(context).notify(tag, 1, notice);
    }
}
