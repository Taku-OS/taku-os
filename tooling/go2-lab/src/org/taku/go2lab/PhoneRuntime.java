package org.taku.go2lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** User-initiated Termux task; the bridge and all transports run on this phone. */
final class PhoneRuntime {
  private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
  private final Activity activity;
  private final Runnable reload;
  private final Button button;
  private volatile boolean busy;
  boolean isBusy(){return busy;}

  PhoneRuntime(Activity activity, Runnable reload) {
    this.activity = activity; this.reload = reload;
    button = new Button(activity); button.setText(text(R.string.phone_settings));
    button.setOnClickListener(v -> show());
  }
  Button button() { return button; }
  private String text(int id) { return AppLocale.context(activity).getString(id); }

  private static JSONObject request(String path, boolean post) throws Exception {
    return request(path, post ? "{}" : null);
  }
  private static JSONObject request(String path, String body) throws Exception {
    HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:18845/api/" + path).openConnection(Proxy.NO_PROXY);
    c.setConnectTimeout(1200); c.setReadTimeout(4500); c.setInstanceFollowRedirects(false);
    try {
      if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
      }
      if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
      try (InputStream in = c.getInputStream()) {
        byte[] data = in.readNBytes(8193);
        if (data.length > 8192) throw new IOException("Response too large");
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
      }
    } finally { c.disconnect(); }
  }

  void stopMotion() {
    new Thread(() -> { try { request("motion/stop", true); } catch (Exception ignored) { } }, "Go2Pause").start();
  }

  private static boolean lanIp(String ip) {
    String[] parts = ip.split("\\.", -1);
    if (parts.length != 4) return false;
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
      octets[i] = Integer.parseInt(parts[i]); if (octets[i] > 255) return false;
    }
    return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
      || (octets[0] == 192 && octets[1] == 168);
  }

  private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
  private void styleField(EditText field){
    android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xffffffff);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),0xffdbe5ed);field.setBackground(bg);field.setTextColor(0xff203747);field.setTextSize(14);field.setPadding(dp(14),dp(10),dp(14),dp(10));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.topMargin=dp(12);field.setLayoutParams(params);
  }
  void show() {
    if (busy) return;
    stopMotion();
    LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(22), dp(8), dp(22), dp(18));
    TextView note = new TextView(activity); note.setText(text(R.string.phone_note));note.setTextSize(13);note.setTextColor(0xff657988);note.setLineSpacing(dp(3),1);panel.addView(note);
    RadioGroup modes = new RadioGroup(activity);
    RadioButton simulation = new RadioButton(activity); simulation.setId(1); simulation.setText(text(R.string.phone_simulation));
    RadioButton real = new RadioButton(activity); real.setId(2); real.setText(text(R.string.phone_real));
    modes.addView(real);modes.addView(simulation);modes.setPadding(0,dp(12),0,dp(4));
    for(RadioButton option:new RadioButton[]{real,simulation}){option.setTextSize(13);option.setMinHeight(dp(46));option.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff29485f));}
    panel.addView(modes);
    EditText ip = new EditText(activity); ip.setSingleLine(true); ip.setHint(text(R.string.phone_ip));
    ip.setInputType(InputType.TYPE_CLASS_PHONE);styleField(ip);panel.addView(ip);
    EditText key = new EditText(activity); key.setSingleLine(true); key.setHint(text(R.string.phone_key));
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    styleField(key);key.setVisibility(android.view.View.GONE);
    Button advanced=new Button(activity);advanced.setAllCaps(false);advanced.setText(text(R.string.phone_advanced));advanced.setTextSize(12);advanced.setTextColor(0xff48647a);advanced.setBackgroundColor(android.graphics.Color.TRANSPARENT);advanced.setOnClickListener(v->key.setVisibility(key.getVisibility()==android.view.View.GONE?android.view.View.VISIBLE:android.view.View.GONE));panel.addView(advanced);panel.addView(key);
    android.content.SharedPreferences prefs = activity.getPreferences(Activity.MODE_PRIVATE);
    ip.setText(prefs.getString("robotIp", ""));
    modes.check(prefs.getBoolean("real", false) ? 2 : 1); ip.setEnabled(modes.getCheckedRadioButtonId() == 2);
    key.setEnabled(modes.getCheckedRadioButtonId() == 2);
    modes.setOnCheckedChangeListener((g, id) -> { ip.setEnabled(id == 2); key.setEnabled(id == 2); });
    ScrollView scroll = new ScrollView(activity); scroll.addView(panel);
    AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(text(R.string.phone_settings)).setView(scroll)
      .setPositiveButton(text(R.string.phone_start), null)
      .setNeutralButton(text(R.string.phone_stop), (d, which) -> change(null, "", ""))
      .setNegativeButton(text(android.R.string.cancel), null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      boolean isReal = modes.getCheckedRadioButtonId() == 2;
      String address = ip.getText().toString().trim();
      String deviceKey = key.getText().toString().trim();
      if (isReal && !lanIp(address)) { ip.setError(text(R.string.phone_invalid_ip)); return; }
      if (isReal && !deviceKey.isEmpty() && !deviceKey.matches("[a-fA-F0-9]{32}")) {
        key.setError(text(R.string.phone_invalid_key)); return;
      }
      if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(new String[]{PERMISSION}, 62);
        Toast.makeText(activity, text(R.string.phone_permission), Toast.LENGTH_LONG).show(); return;
      }
      prefs.edit().putBoolean("real", isReal).putString("robotIp", address).apply();
      key.setText(""); dialog.dismiss(); change(isReal ? "real" : "simulation", address, isReal ? deviceKey : "");
    }));
    dialog.show();
    if(dialog.getWindow()!=null){android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xffedf3f7);bg.setCornerRadius(dp(28));dialog.getWindow().setBackgroundDrawable(bg);}
    for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button b=dialog.getButton(which);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(0xff29485f);}

  }

  private String connectionError(String code) {
    switch (code) {
      case "LocalSignalingPortError": return text(R.string.phone_unreachable);
      case "RobotBusyError": return text(R.string.phone_robot_busy);
      case "AesKeyRequiredError": return text(R.string.phone_key_required);
      case "AesKeyRejectedError": return text(R.string.phone_key_rejected);
      case "DataChannelTimeoutError": case "TimeoutError": return text(R.string.phone_channel_timeout);
      case "ConnectionError": return text(R.string.phone_disconnected);
      case "NoSdpAnswerError": return text(R.string.phone_bad_response);
      default: return text(R.string.phone_connect_failed) + " (" + code + ")";
    }
  }

  private void change(String mode, String ip, String deviceKey) {
    busy = true; button.setEnabled(false); button.setText(text(R.string.phone_starting));
    new Thread(() -> {
      String error = null;
      boolean runtimeReady = false;
      try {
        JSONObject previous = null;
        try { previous = request("phone", false); } catch (Exception ignored) { }
        if (previous != null) {
          if (!"pixel-local".equals(previous.optString("runtime"))) throw new IOException("UNKNOWN_RUNTIME");
          request("phone/shutdown", true);
          boolean stopped = false;
          for (int i = 0; i < 30; i++) {
            Thread.sleep(500);
            try { request("phone", false); } catch (Exception unavailable) { stopped = true; break; }
          }
          if (!stopped) throw new IOException("STOP_TIMEOUT");
        }
        if (mode != null) {
          Intent intent = new Intent("com.termux.RUN_COMMAND");
          intent.setClassName("com.termux", "com.termux.app.RunCommandService");
          intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
          String script = "/data/data/com.termux/files/home/taku-go2/run.sh";
          intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", "real".equals(mode)
            ? new String[]{script, mode, "--robot-ip", ip} : new String[]{script, mode});
          intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
          intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", text(R.string.phone_service));
          if (activity.startService(intent) == null) throw new IOException("TERMUX_NOT_INSTALLED");
          boolean ready = false;
          for (int i = 0; i < 60; i++) {
            Thread.sleep(500);
            try {
              JSONObject state = request("phone", false);
              ready = "pixel-local".equals(state.optString("runtime")) && mode.equals(state.optString("mode"))
                && (!"real".equals(mode) || ip.equals(state.optString("robotIp")));
              if (ready) break;
            } catch (Exception unavailable) { }
          }
          if (!ready) throw new IOException("START_TIMEOUT");
          runtimeReady = true;
          if ("real".equals(mode)) {
            if (!deviceKey.isEmpty()) request("phone/key", new JSONObject().put("key", deviceKey).toString());
            activity.runOnUiThread(() -> { if (!activity.isDestroyed()) { button.setText(text(R.string.phone_connecting)); reload.run(); } });
            if (!request("phone/network", false).optBoolean("reachable"))
              throw new IOException(text(R.string.phone_unreachable));
            request("connect", true);
            boolean connected = false;
            long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
              Thread.sleep(500);
              JSONObject state;
              // Signaling uses bounded synchronous HTTP in the upstream driver.
              // A delayed status response during that handshake is not a failure.
              try { state = request("state", false); }
              catch (IOException unavailable) { continue; }
              if ("error".equals(state.optString("phase"))) throw new IOException(connectionError(state.optString("error")));
              JSONObject samples = state.optJSONObject("telemetry");
              JSONObject battery = samples == null ? null : samples.optJSONObject("rt/lf/lowstate");
              JSONObject pose = samples == null ? null : samples.optJSONObject("rt/lf/sportmodestate");
              connected = state.optBoolean("connected") && battery != null && battery.optBoolean("fresh")
                && pose != null && pose.optBoolean("fresh");
              if (connected) break;
            }
            if (!connected) {
              request("disconnect", true);
              throw new IOException(text(R.string.phone_no_telemetry));
            }
          }
        }
      } catch (Exception e) {
        error = (runtimeReady ? "" : text(R.string.phone_failure_help) + "\n") + e.getMessage();
      }
      String result = error;
      activity.runOnUiThread(() -> {
        busy = false;
        if (activity.isDestroyed()) return;
        button.setEnabled(true); button.setText(text(R.string.phone_settings));
        if (result != null) new AlertDialog.Builder(activity).setTitle(text(R.string.phone_failed))
          .setMessage(result).setPositiveButton(text(android.R.string.ok), null).show();
        else reload.run();
      });
    }, "Go2Runtime").start();
  }
}
