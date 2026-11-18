package org.agentos.runtime;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class AppApiClient {
  public static String call(String appId,String request){
    HttpURLConnection connection=null;
    try{
      if(appId==null||!appId.matches("[a-zA-Z0-9_-]+"))throw new IOException("No application API id");
      new JSONObject(request);
      byte[] payload=request.getBytes(StandardCharsets.UTF_8);
      connection=(HttpURLConnection)new URL("http://127.0.0.1:8765/v1/apps/"+appId+"/call").openConnection();
      connection.setConnectTimeout(1500);connection.setReadTimeout(10000);connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);
      try(OutputStream out=connection.getOutputStream()){out.write(payload);}
      InputStream stream=connection.getResponseCode()>=400?connection.getErrorStream():connection.getInputStream();
      if(stream==null)throw new IOException("Empty API response");
      try(InputStream in=stream){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>1048576)throw new IOException("API response exceeds 1 MiB");}return bytes.toString("UTF-8");}
    }catch(Exception error){try{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code","API_UNAVAILABLE").put("message",error.getMessage())).toString();}catch(Exception ignored){return "{\"ok\":false,\"error\":{\"code\":\"API_UNAVAILABLE\"}}";}}
    finally{if(connection!=null)connection.disconnect();}
  }
}
