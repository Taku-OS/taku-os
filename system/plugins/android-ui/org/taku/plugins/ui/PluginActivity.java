package org.taku.plugins.ui;

import android.app.Activity;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class PluginActivity extends Activity {
    private LinearLayout content;
    private void message(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(28, 28, 28, 28);
        scroll.addView(content); setContentView(scroll); refresh();
    }
    private JSONObject request(String operation, String id) throws Exception {
        JSONObject data = new JSONObject().put("operation", operation);
        if (id != null) data.put("argument", id);
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-godmode", LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(45000);
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output.write(data.toString()); output.newLine(); output.flush();
            String line = input.readLine(); if (line == null) throw new IOException("service closed connection");
            JSONObject reply = new JSONObject(line);
            if (!reply.optBoolean("ok")) throw new IOException(reply.optJSONObject("error").toString());
            return reply;
        }
    }
    private void refresh() {
        new Thread(() -> {
            try { JSONArray records = request("list", null).getJSONArray("result"); runOnUiThread(() -> display(records)); }
            catch (Exception error) { message("Service unavailable: " + error.getMessage()); runOnUiThread(() -> display(new JSONArray())); }
        }).start();
    }
    private void action(String operation, String id) {
        new Thread(() -> { try { request(operation, id); refresh(); } catch (Exception error) { message(error.getMessage()); refresh(); } }).start();
    }
    private void button(LinearLayout row, String label, Runnable callback) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(view -> callback.run());
        row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
    private void display(JSONArray records) {
        content.removeAllViews();
        TextView title = new TextView(this); title.setText("God Mode Plugins"); title.setTextSize(24); content.addView(title);
        Button reload = new Button(this); reload.setText("Refresh"); reload.setOnClickListener(view -> refresh()); content.addView(reload);
        if (records.length() == 0) { TextView empty = new TextView(this); empty.setText("No plugins installed, or service unavailable."); content.addView(empty); }
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index); if (record == null) continue;
            String id = record.optString("id"), state = record.optString("state"), error = record.optString("error", "");
            TextView label = new TextView(this); label.setText(id + "\n" + state + (error.isEmpty() || error.equals("null") ? "" : "\n" + error)); label.setTextSize(16); content.addView(label);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); content.addView(row);
            boolean enabled = record.optBoolean("enabled"); button(row, enabled ? "Disable" : "Enable", () -> action(enabled ? "disable" : "enable", id));
            button(row, "Reload", () -> action("reload", id)); button(row, "Remove", () -> action("remove", id));
        }
    }
}
