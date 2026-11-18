package org.agentos.assistant;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/** In-memory Unix-socket client. Interactive answers and auth URLs never enter task files. */
final class NativeClient {
 static final ExecutorService io=Executors.newCachedThreadPool();static final Handler main=new Handler(Looper.getMainLooper());
 interface Callback{void ready(JSONObject value);void failed(String message);}
 static JSONObject exchange(JSONObject request)throws Exception {
  try(LocalSocket socket=new LocalSocket()){
   socket.connect(new LocalSocketAddress("taku-agent-native"));if(socket.getPeerCredentials().getUid()!=0)throw new IOException("Invalid native service identity");socket.setSoTimeout(45000);
   socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));socket.shutdownOutput();
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=socket.getInputStream().read(b))!=-1){out.write(b,0,n);if(out.size()>6000000)throw new IOException("Native result exceeds the limit");}
   JSONObject answer=new JSONObject(out.toString("UTF-8"));if(!answer.optBoolean("ok")){if("STEER_NOT_SUBMITTED".equals(answer.optString("code")))throw new AgentGatewaySubmissions.SteerNotSubmitted();throw new IOException(answer.optString("message","Native service returned no result"));}return answer.getJSONObject("result");
  }
 }
 /** A separate lifetime from exchange(): one request, then bounded JSONL until closed. */
 static LocalSocket openSessionStream(JSONObject request)throws Exception {
  LocalSocket socket=new LocalSocket();
  try {
   socket.connect(new LocalSocketAddress("taku-agent-native"));
   if(socket.getPeerCredentials().getUid()!=0)throw new IOException("INVALID_RUNTIME_IDENTITY");
   socket.setSoTimeout(15000);
   JSONObject subscribe=new JSONObject(request.toString()).put("op","subscribeSession");
   socket.getOutputStream().write(subscribe.toString().getBytes(StandardCharsets.UTF_8));socket.shutdownOutput();
   return socket;
  }catch(Exception error){socket.close();throw error;}
 }
 static JSONObject state(String id)throws Exception{return exchange(new JSONObject().put("op","state").put("threadId",id));}
 static JSONObject rpcSync(String method,JSONObject params)throws Exception {JSONObject r=new JSONObject().put("op","rpc").put("method",method);if(params!=null)r.put("params",params);return exchange(r);}
 static void async(JSONObject request,Callback callback){io.execute(()->{try{JSONObject r=exchange(request);main.post(()->callback.ready(r));}catch(Exception e){String m=e.getMessage()==null?"Native service temporarily unavailable":e.getMessage();main.post(()->callback.failed(m));}});}
 static void rpc(String method,JSONObject params,Callback callback){try{JSONObject request=new JSONObject().put("op","rpc").put("method",method);if(params!=null)request.put("params",params);async(request,callback);}catch(Exception e){callback.failed("Invalid native request");}}
 static void respond(String key,JSONObject response,Callback callback){try{async(new JSONObject().put("op","respond").put("key",key).put("response",response),callback);}catch(Exception e){callback.failed("Could not send answer");}}
}
