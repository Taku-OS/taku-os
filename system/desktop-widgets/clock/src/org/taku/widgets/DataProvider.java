package org.taku.widgets;
public final class DataProvider extends android.content.ContentProvider {
 @Override public boolean onCreate(){return true;}
 @Override public android.os.Bundle call(String method,String arg,android.os.Bundle extras){android.os.Bundle out=new android.os.Bundle();long token=android.os.Binder.clearCallingIdentity();try{
  org.json.JSONObject result;
  if("get".equals(method))result=ClockData.read(getContext());
  else if("command".equals(method)){String command=new org.json.JSONObject(arg==null?"{}":arg).getString("command");if("alarms".equals(command)) {
   android.content.Intent intent=ClockData.alarmIntent(getContext());
   result=new org.json.JSONObject().put("effect","activity-launch-required").put("action",intent.getAction()).put("agentCommand","am start -a "+intent.getAction());
  } else if(ClockData.requiresActivity(command)) {
   if("clock".equals(command)) ClockData.clockIntent(getContext());
   android.content.Intent intent=ClockData.actionIntent(getContext(),command);
   result=new org.json.JSONObject().put("effect","activity-launch-required").put("action",intent.getAction())
    .put("agentCommand","am start -n org.taku.widgets/.WidgetActionActivity -a "+intent.getAction());
  } else { ClockData.command(getContext(),command);result=new org.json.JSONObject().put("effect",command); }}
  else throw new IllegalArgumentException("UNKNOWN_METHOD");
  out.putString("result",new org.json.JSONObject().put("ok",true).put("result",result).toString());
 }catch(Exception e){out.putString("result","{\"ok\":false,\"error\":"+org.json.JSONObject.quote(e.getMessage())+"}");}finally{android.os.Binder.restoreCallingIdentity(token);}return out;}
 @Override public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException("Use call get or command");}
 @Override public String getType(android.net.Uri u){return "application/json";}
 @Override public android.net.Uri insert(android.net.Uri u,android.content.ContentValues v){throw new UnsupportedOperationException();}
 @Override public int update(android.net.Uri u,android.content.ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int delete(android.net.Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
