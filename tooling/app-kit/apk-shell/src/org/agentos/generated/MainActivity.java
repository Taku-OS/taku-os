package org.agentos.generated;
import android.app.*;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import java.nio.file.Files;
import java.io.File;
public class MainActivity extends Activity {
  private WebView web;
  private String appId;
  private android.os.FileObserver observer;
  private String loadedHtml;
  private String launchRoute;
  private boolean invalidLaunchRoute;
  private void readLaunchContext(){
    android.content.Intent intent=getIntent();
    if(intent==null||!intent.hasExtra("route"))return;
    try{
      String route=intent.getStringExtra("route");
      // Internal paths only: no URL schemes, encodings, queries or dot segments.
      if(route!=null&&route.length()<=256&&("/".equals(route)||route.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")))launchRoute=route;
      else invalidLaunchRoute=true;
    }catch(Exception e){invalidLaunchRoute=true;}
  }
  @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);recreate();}
  @Override public void onCreate(Bundle state){super.onCreate(state);readLaunchContext();web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.addJavascriptInterface(new Bridge(),"Phone");android.widget.FrameLayout frame=new android.widget.FrameLayout(this);frame.setBackgroundColor(android.graphics.Color.rgb(245,246,242));frame.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());frame.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});setContentView(frame);
    try{try(java.io.InputStream in=getAssets().open("app-id.txt")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[256];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);appId=bytes.toString("UTF-8").trim();}
      web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){android.net.Uri u=request.getUrl();if(!"agentos.local".equals(u.getHost()))return null;try{String path=u.getPath();String asset=path.startsWith("/runtime/")?path.substring(1):"app.bundle.js";return new WebResourceResponse("application/javascript","UTF-8",getAssets().open(asset));}catch(Exception e){return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(e.toString().getBytes()));}}});
      try(java.io.InputStream in=getAssets().open("runtime/index.html")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);loadedHtml=bytes.toString("UTF-8").replace("__APP_ID__","app");}
      web.loadDataWithBaseURL("https://agentos.local/",loadedHtml,"text/html","UTF-8",null);
    }catch(Exception e){Toast.makeText(this,e.toString(),1).show();}
  }
  public class Bridge {
    @JavascriptInterface public String launchContext(){
      org.json.JSONObject context=new org.json.JSONObject();
      try{context.put("route",launchRoute==null?org.json.JSONObject.NULL:launchRoute);if(invalidLaunchRoute)context.put("error","INVALID_ROUTE");return context.toString();}
      catch(org.json.JSONException e){return "{\"route\":null,\"error\":\"INVALID_LAUNCH_CONTEXT\"}";}
    }
    @JavascriptInterface public String callJson(String request){return org.agentos.runtime.AppApiClient.call(appId,request);}
    @JavascriptInterface public void notify(String title,String message){
      NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("water","Water log",3));nm.notify(101,new Notification.Builder(MainActivity.this,"water").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message).build());
    }
    @JavascriptInterface public String read(){return preferences().getString("data","{}");}
    @JavascriptInterface public void save(String data){preferences().edit().putString("data",data).commit();}
    private android.content.SharedPreferences preferences(){return appId==null?getPreferences(0):getSharedPreferences("mini-"+appId,0);}
  }
  @Override public void onDestroy(){if(observer!=null)observer.stopWatching();if(web!=null){web.destroy();web=null;}super.onDestroy();}
}
