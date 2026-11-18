package org.agentos.assistant;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Set;

/** A bounded suggestion, never a screen transcript or an executable tool call. */
final class ScreenSuggestionJson {
    final boolean actionable;
    final String title, suggestion, task;
    private ScreenSuggestionJson(boolean actionable, String title, String suggestion, String task) {
        this.actionable=actionable; this.title=title; this.suggestion=suggestion; this.task=task;
    }
    static ScreenSuggestionJson parse(String raw) throws Exception {
        if (raw==null || raw.length()>8000) throw new IllegalArgumentException("Invalid suggestion format");
        JSONTokener tokener=new JSONTokener(raw.trim());
        Object value=tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean()!=0) throw new IllegalArgumentException("Invalid suggestion format");
        JSONObject json=(JSONObject)value;
        if (json.length()!=4) throw new IllegalArgumentException("Invalid suggestion field");
        for (java.util.Iterator<String> it=json.keys();it.hasNext();)
            if (!Set.of("actionable","title","suggestion","task").contains(it.next())) throw new IllegalArgumentException("Invalid suggestion field");
        if (!(json.get("actionable") instanceof Boolean)) throw new IllegalArgumentException("Invalid suggestion state");
        boolean actionable=json.getBoolean("actionable");
        String title=text(json,"title",60), suggestion=text(json,"suggestion",160), task=text(json,"task",1600);
        if (actionable && (title.isBlank() || suggestion.isBlank() || task.isBlank())) throw new IllegalArgumentException("Suggestion content is incomplete");
        return new ScreenSuggestionJson(actionable,title,suggestion,task);
    }
    private static String text(JSONObject json,String key,int limit) throws Exception {
        Object value=json.get(key);
        if (!(value instanceof String) || ((String)value).length()>limit || ((String)value).indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid suggestion text");
        return ((String)value).trim();
    }
}
