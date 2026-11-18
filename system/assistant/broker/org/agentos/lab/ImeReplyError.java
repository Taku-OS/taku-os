package org.agentos.lab;

import java.io.IOException;
import java.util.Locale;
import org.json.JSONObject;

/** Convert gateway failures to a small public vocabulary; never show upstream bodies. */
final class ImeReplyError extends IOException {
 final String code;
 ImeReplyError(String code,String message){super(message);this.code=code;}
 static ImeReplyError http(int status,String body){
  String message="";
  try{if(body!=null&&body.length()<=16384){JSONObject error=new JSONObject(body).optJSONObject("error");if(error!=null)message=error.optString("message").toLowerCase(Locale.ROOT);}}catch(Exception ignored){}
  if(status==401||(message.contains("codex")&&(message.contains("signed out")||message.contains("token refresh failed"))))
   return new ImeReplyError("auth_required","Codex sign-in expired. Tap to reconnect");
  if(status==429)return new ImeReplyError("quota","The current quota is exhausted. Try again later");
  return new ImeReplyError("unavailable","Could not reach the model. Reply in your own words for now");
 }
}
