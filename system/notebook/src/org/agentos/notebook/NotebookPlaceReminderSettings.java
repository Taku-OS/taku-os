package org.agentos.notebook;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookPlaceReminderSettings {
    private final Context context;

    NotebookPlaceReminderSettings(Context context) {
        this.context = context;
    }

    void addStatus(JSONObject model) throws JSONException {
        var state = NotebookArrivals.state(context);
        model.put("remindersEnabled", state.getBoolean("enabled"))
                .put("notificationsEnabled", state.getBoolean("notificationsEnabled"))
                .put("locationEnabled", state.getBoolean("locationEnabled"))
                .put("arrivalHostStatus", state.getString("hostStatus"));
    }

    String[] requiredPermissions() {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? new String[0] : new String[]{Manifest.permission.POST_NOTIFICATIONS};
    }

    String setEnabled(boolean enabled) {
        try {
            NotebookArrivals.setEnabled(context, enabled);
            return null;
        } catch (IOException | JSONException unavailable) {
            if ("PLACE_NOTIFICATIONS_REQUIRED".equals(unavailable.getMessage())) {
                return label("Allow notifications to turn on reminders.", "请允许通知后再开启提醒。");
            }
            return enabled ? label("Couldn’t turn on reminders.", "暂时无法开启提醒。")
                    : label("Couldn’t turn off reminders.", "暂时无法关闭提醒。");
        }
    }

    private String label(String english, String chinese) {
        var locale = context.getResources().getConfiguration().getLocales().get(0);
        return "zh".equals(locale.getLanguage()) ? chinese : english;
    }
}
