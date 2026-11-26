package org.agentos.notebook;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookAppearance {
    private static final String THEME = "theme";

    private NotebookAppearance() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("notebook-appearance", Context.MODE_PRIVATE);
    }

    static String effectiveTheme(Context context) {
        return effectiveTheme(context, preferences(context).getString(THEME, "system"));
    }

    private static String effectiveTheme(Context context, String theme) {
        if (!"system".equals(theme)) return theme;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
    }

    static JSONObject read(Context context) throws JSONException {
        String theme = preferences(context).getString(THEME, "system");
        return new JSONObject().put(THEME, theme).put("effectiveTheme", effectiveTheme(context, theme));
    }

    static void set(Context context, String theme) throws IOException {
        if (!List.of("light", "dark", "system").contains(theme)) {
            throw new IOException("INVALID_ARGUMENT");
        }
        if (!preferences(context).edit().putString(THEME, theme).commit()) {
            throw new IOException("NOTEBOOK_APPEARANCE_UNAVAILABLE");
        }
    }
}
