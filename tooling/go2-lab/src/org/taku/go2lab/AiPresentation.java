package org.taku.go2lab;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Render verified action receipts separately from the model's proposed explanation. */
final class AiPresentation {
  static String action(Context c, Object value) {
    if(!(value instanceof JSONObject))return action(c,String.valueOf(value));
    JSONObject a=(JSONObject)value;
    switch(a.optString("action")) {
      case "map_search":return c.getString(R.string.ai_map_search);
      case "map_route":return c.getString(R.string.ai_map_route);
      case "move_distance":return c.getString(R.string.ai_distance_plan,c.getString("forward".equals(a.optString("direction"))?R.string.ai_direction_forward:R.string.ai_direction_backward),a.optDouble("distance_m"));
      case "turn_angle":return c.getString(R.string.ai_angle_plan,c.getString("left".equals(a.optString("direction"))?R.string.ai_direction_left:R.string.ai_direction_right),a.optDouble("angle_deg"));
      case "set_volume":return c.getString(R.string.ai_volume_plan,a.optInt("volume"));
      default:return c.getString(R.string.ai_invalid_plan);
    }
  }
  static String action(Context c, String name) {
    int id;
    switch (name) {
      case "inspect": id=R.string.ai_inspect; break;
      case "connect": id=R.string.ai_connect; break;
      case "disconnect": id=R.string.ai_disconnect; break;
      case "camera_on": id=R.string.ai_camera_on; break;
      case "camera_off": id=R.string.ai_camera_off; break;
      case "lidar_on": id=R.string.ai_lidar_on; break;
      case "lidar_off": id=R.string.ai_lidar_off; break;
      case "stop": id=R.string.ai_stop_action; break;
      case "forward": id=R.string.ai_forward; break;
      case "backward": id=R.string.ai_backward; break;
      case "left": id=R.string.ai_left; break;
      case "right": id=R.string.ai_right; break;
      case "audio_status": id=R.string.ai_audio_status; break;
      case "audio_play": id=R.string.ai_audio_play; break;
      case "audio_stop": id=R.string.ai_audio_stop; break;
      default: id=R.string.ai_page;
    }
    return c.getString(id);
  }
  static String error(Context c, String code) {
    if (code == null || code.equals("null")) return "";
    if(code.contains("Socket closed")||code.contains("connect timed out")||code.contains("Unable to resolve host"))return c.getString(R.string.ai_network_error);
    switch(code) {
      case "TASK_EXPIRED": case "APP_NOT_FOREGROUND": case "MANUAL_TAKEOVER": case "CANCELED": case "CONTROL_CHANGED": return c.getString(R.string.ai_canceled);
      case "INVALID_PLAN": case "INVALID_ACTION": return c.getString(R.string.ai_invalid_plan);
      case "NOT_CONNECTED": return c.getString(R.string.phone_disconnected);
      case "MODE_NOT_CONFIRMED": case "STOP_UNCONFIRMED": return c.getString(R.string.ai_unconfirmed);
      case "MANUAL_CONTROL": case "MANUAL_TAKEOVER_REQUIRED": return c.getString(R.string.ai_manual_hint);
      case "DISTANCE_POSE_UNAVAILABLE":case "DISTANCE_POSE_JUMP":return c.getString(R.string.ai_pose_problem);
      case "DISTANCE_PATH_DRIFT":case "DISTANCE_WRONG_DIRECTION":case "DISTANCE_OVERSHOOT":return c.getString(R.string.ai_path_problem);
      case "DISTANCE_NO_PROGRESS":return c.getString(R.string.ai_no_progress);
      case "DISTANCE_LIMIT":case "TimeoutError":return c.getString(R.string.ai_time_limit);
      case "NO_CLIP":return c.getString(R.string.ai_no_clip);
      default: return c.getString(R.string.ai_failure, code.length() > 180 ? code.substring(0,180) : code);
    }
  }
  static String result(Context c, JSONObject job) {
    JSONArray steps=job.optJSONArray("results"), actions=job.optJSONArray("actions");
    ArrayList<String> lines=new ArrayList<>();
    if (steps != null) for(int i=0;i<steps.length();i++) {
      JSONObject step=steps.optJSONObject(i); if(step==null) continue;
      Object raw=step.opt("action"); String name=raw instanceof String?(String)raw:""; JSONObject r=step.optJSONObject("result");
      if(r==null || name.startsWith("show_") || name.equals("follow_start")) continue;
      if(raw instanceof JSONObject) {
        if(r.has("traveledM"))lines.add(c.getString(R.string.ai_distance_done,action(c,raw),r.optDouble("traveledM")));
        else if(r.has("turnedDegrees"))lines.add(c.getString(R.string.ai_angle_done,action(c,raw),r.optDouble("turnedDegrees")));
        else lines.add(c.getString(R.string.ai_step_done,action(c,raw)));
      } else if(name.equals("audio_status")) {
        lines.add(c.getString(R.string.ai_volume_read,r.optInt("volume",-1)));
      } else if(name.equals("inspect")) {
        JSONObject telemetry=r.optJSONObject("telemetry");
        JSONObject battery=telemetry==null?null:telemetry.optJSONObject("rt/lf/lowstate");
        JSONObject values=battery==null?null:battery.optJSONObject("values");
        String source=c.getString("real".equals(r.optString("source"))?R.string.ai_real:R.string.ai_simulation);
        if(r.optBoolean("connected") && battery!=null && battery.optBoolean("fresh") && values!=null && values.has("batteryPercent"))
          lines.add(c.getString(R.string.ai_battery,source,values.optInt("batteryPercent")));
        else lines.add(c.getString(R.string.ai_no_fresh_status));
      } else if(Arrays.asList("forward","backward","left","right").contains(name)) {
        lines.add(c.getString(R.string.ai_move_done,action(c,name)));
      } else lines.add(c.getString(R.string.ai_step_done,action(c,name)));
    }
    String status=job.optString("status");
    if(status.equals("confirming")) {
      ArrayList<String> names=new ArrayList<>();
      if(actions!=null) for(int i=0;i<actions.length();i++) names.add(action(c,actions.opt(i)));
      return c.getString(R.string.ai_confirm_detail,String.join(" → ",names));
    }
    if(status.equals("planning")) return c.getString(R.string.ai_planning);
    if(status.equals("running") && actions!=null) {
      int next=steps==null?0:steps.length();
      if(next<actions.length()) lines.add(c.getString(R.string.ai_running,action(c,actions.opt(next))));
      JSONObject p=job.optJSONObject("progress");
      if(p!=null && p.has("traveledM"))lines.add(c.getString(R.string.ai_distance_progress,p.optDouble("traveledM"),p.optDouble("targetM")));
      if(p!=null && p.has("turnedDegrees"))lines.add(c.getString(R.string.ai_angle_progress,p.optDouble("turnedDegrees"),p.optDouble("targetDegrees")));
    }
    if(status.equals("failed")) lines.add(error(c,job.optString("error")));
    if(status.equals("canceled")) lines.add(c.getString(R.string.ai_canceled));
    if(status.equals("completed") && actions!=null && actions.length()==0)
      lines.add(c.getString(R.string.ai_no_action,job.optString("message")));
    else if(status.equals("completed") && !job.optString("message").isEmpty())lines.add(job.optString("message"));
    if(lines.isEmpty()) lines.add(c.getString(R.string.ai_done));
    return String.join("\n",lines);
  }
}
