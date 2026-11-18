package org.agentos.tests;
import android.net.*;
import org.json.*;
import java.io.*;

/** Actual phone IPC/native-runtime probe. It does not synthesize model responses. */
public class NativeSocketProbe {
 static JSONObject exchange(JSONObject request)throws Exception {try(LocalSocket socket=new LocalSocket()){socket.connect(new LocalSocketAddress("taku-agent-native"));if(socket.getPeerCredentials().getUid()!=0)throw new IOException("Wrong owner");socket.setSoTimeout(45000);socket.getOutputStream().write(request.toString().getBytes("UTF-8"));socket.shutdownOutput();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=socket.getInputStream().read(b))!=-1)out.write(b,0,n);JSONObject r=new JSONObject(out.toString("UTF-8"));if(!r.optBoolean("ok"))throw new IOException(r.optString("message"));return r.getJSONObject("result");}}
 static JSONObject rpc(String method,JSONObject params)throws Exception{JSONObject r=new JSONObject().put("op","rpc").put("method",method);if(params!=null)r.put("params",params);return exchange(r);}
 public static void main(String[] args)throws Exception {
  try { run(args); } catch(Exception error) { System.err.println(error.getMessage()); System.exit(1); }
 }
 static void run(String[] args)throws Exception {
  android.os.Looper.prepareMainLooper();
  if(args.length>0&&args[0].equals("exchange")){System.out.println(exchange(new JSONObject(args[1])).toString());return;}
  if(args.length>0&&args[0].equals("rpc")){JSONObject result=rpc(args[1],args.length>2?new JSONObject(args[2]):null);System.out.println(result.toString());return;}
  JSONObject protocol=exchange(new JSONObject().put("op","protocol"));if(protocol.getJSONArray("methods").length()!=168)throw new IOException("Wrong protocol count");
  JSONObject models=rpc("model/list",new JSONObject().put("limit",100));if(models.getJSONArray("data").length()<1)throw new IOException("No native models");
  JSONObject limits=rpc("account/rateLimits/read",null);JSONObject mcp=rpc("mcpServerStatus/list",new JSONObject().put("limit",100));
  JSONObject result=new JSONObject().put("nativeIpcOwnerVerified",true).put("nativeMethodCount",168).put("nativeModelCount",models.getJSONArray("data").length()).put("nativeQuotaResponse",limits.has("rateLimits")).put("nativeMcpServerCount",mcp.getJSONArray("data").length()).put("modelResponsesSimulated",false);
  try(FileOutputStream out=new FileOutputStream("/data/local/tmp/native-socket-acceptance.json")){out.write(result.toString().getBytes("UTF-8"));}System.out.println(result.toString());
 }
}
