package org.agentos.minilab;
import android.app.*;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import java.nio.file.Files;
import java.io.File;
public class WebActivity extends Activity {
  private WebView web;
  private String appId;
  private android.os.FileObserver observer;
  private String loadedHtml;
  @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);recreate();}
  @Override public void onCreate(Bundle state){super.onCreate(state);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.addJavascriptInterface(new Bridge(),"Phone");android.widget.FrameLayout frame=new android.widget.FrameLayout(this);frame.setBackgroundColor(android.graphics.Color.rgb(245,246,242));frame.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());frame.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});setContentView(frame);
    try{appId=getIntent().getStringExtra("app");if(appId!=null&&!appId.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid app id");
      web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){android.net.Uri u=request.getUrl();if(!"agentos.local".equals(u.getHost()))return null;try{String path=u.getPath();java.io.InputStream input;
        if(path.startsWith("/runtime/")){input=getAssets().open(path.substring(1));}else{File file=new File(getFilesDir(),path.substring(1));if(!file.getCanonicalPath().startsWith(getFilesDir().getCanonicalPath()+"/apps/"))throw new java.io.IOException("Invalid path");input=new java.io.FileInputStream(file);}
        return new WebResourceResponse(path.endsWith(".js")?"application/javascript":"text/html","UTF-8",input);
      }catch(Exception error){return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(error.toString().getBytes()));}}});
      String html;
      if(appId==null){html=new String(Files.readAllBytes(new File(getFilesDir(),"web/index.html").toPath()),java.nio.charset.StandardCharsets.UTF_8);}else{try(java.io.InputStream in=getAssets().open("runtime/index.html")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);html=bytes.toString("UTF-8").replace("__APP_ID__",appId);}}
      loadedHtml=html;web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
      web.loadDataWithBaseURL("https://agentos.local/",html,"text/html","UTF-8",null);
      if(appId!=null){observer=new android.os.FileObserver(new File(getFilesDir(),"apps/"+appId),android.os.FileObserver.CLOSE_WRITE|android.os.FileObserver.MOVED_TO){@Override public void onEvent(int event,String path){if("app.bundle.js".equals(path))runOnUiThread(()->{if(web!=null)web.loadDataWithBaseURL("https://agentos.local/",loadedHtml,"text/html","UTF-8",null);});}};observer.startWatching();}
    }catch(Exception e){Toast.makeText(this,e.toString(),1).show();}
  }
  public class Bridge {
    @JavascriptInterface public String callJson(String request){return org.agentos.runtime.AppApiClient.call(appId,request);}
    @JavascriptInterface public void notify(String title,String message){
      NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("water","Water log",3));nm.notify(101,new Notification.Builder(WebActivity.this,"water").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message).build());
    }
    @JavascriptInterface public String read(){return preferences().getString("data","{}");}
    @JavascriptInterface public void save(String data){preferences().edit().putString("data",data).commit();}
    private android.content.SharedPreferences preferences(){return appId==null?getPreferences(0):getSharedPreferences("mini-"+appId,0);}
  }
  @Override public void onDestroy(){if(observer!=null)observer.stopWatching();if(web!=null){web.destroy();web=null;}super.onDestroy();}
}
