package org.taku.weather;
public final class DataProvider extends android.content.ContentProvider {
 @Override public boolean onCreate(){return true;}
 @Override public android.os.Bundle call(String method,String arg,android.os.Bundle extras){android.os.Bundle out=new android.os.Bundle();long token=android.os.Binder.clearCallingIdentity();try{
  org.json.JSONObject p=new org.json.JSONObject(arg==null?"{}":arg),result;
  switch(method){case "open":result=WeatherLaunch.command(getContext());break;case "get":result=WeatherData.read(getContext());break;case "location/set":result=WeatherData.location(getContext(),p);break;case "cities/search":result=new org.json.JSONObject().put("cities",WeatherData.search(p.getString("query")));break;case "refresh":WeatherData.refresh(getContext());result=WeatherData.read(getContext());break;default:throw new IllegalArgumentException("UNKNOWN_METHOD");}
  out.putString("result",new org.json.JSONObject().put("ok",true).put("result",result).toString());
 }catch(Exception e){out.putString("result","{\"ok\":false,\"error\":"+org.json.JSONObject.quote(e.getMessage())+"}");}finally{android.os.Binder.restoreCallingIdentity(token);}return out;}
 @Override public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException("Use call get, open, refresh, cities/search or location/set");}
 @Override public String getType(android.net.Uri u){return "application/json";}
 @Override public android.net.Uri insert(android.net.Uri u,android.content.ContentValues v){throw new UnsupportedOperationException();}
 @Override public int update(android.net.Uri u,android.content.ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int delete(android.net.Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
