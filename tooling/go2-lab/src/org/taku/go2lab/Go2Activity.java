package org.taku.go2lab;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;

/** Loopback UI shell. Motion policy is enforced by the local bridge. */
public final class Go2Activity extends Activity {
  private WebView web;
  private ValueCallback<android.net.Uri[]> audioFileCallback;
  private static final int AUDIO_FILE = 41;
  private FrameLayout frame;
  private android.view.View errorView;
  private PhoneRuntime runtime;
  private InAppAi ai;
  private PhoneLocation phoneLocation;
  private boolean navigationPage;
  private String pendingPage;
  private static final String HOME = "http://127.0.0.1:18845/panel";
  private LinearLayout toolbar;
  private TextView pageTitle;
  private String currentPage = "home";
  private final java.util.Map<String,String[]> pageNames = java.util.Map.ofEntries(
    java.util.Map.entry("home",new String[]{"Go2","Go2"}),
    java.util.Map.entry("state",new String[]{"Device details","设备详情"}),
    java.util.Map.entry("camera",new String[]{"Camera","摄像头"}),
    java.util.Map.entry("follow_view",new String[]{"Live Camera","实时摄像头"}),
    java.util.Map.entry("audio",new String[]{"Voice & Sounds","语音与声音"}),
    java.util.Map.entry("audio_import",new String[]{"Import audio","导入音频"}),
    java.util.Map.entry("motion",new String[]{"Movement","行进控制"}),
    java.util.Map.entry("environment",new String[]{"Surroundings","周围环境"}),
    java.util.Map.entry("avoidance",new String[]{"Obstacle Assist","避障辅助"}),
    java.util.Map.entry("navigation",new String[]{"Local Navigation","本地导航"}),
    java.util.Map.entry("amap",new String[]{"Map & Routes","地图与路线"}),
    java.util.Map.entry("navigator",new String[]{"Navigation","定位与导航"}));
  private String label(String en,String zh){return "zh".equals(AppLocale.language(this))?zh:en;}
  private void updateChrome(String page){
    currentPage=page;
    toolbar.setVisibility("home".equals(page)?android.view.View.GONE:android.view.View.VISIBLE);
    String[] names=pageNames.getOrDefault(page,new String[]{"Live Camera","实时摄像头"});
    pageTitle.setText(names["zh".equals(AppLocale.language(this))?1:0]);
  }
  private void showMenu(){
    String[] pages={"home","follow_view","audio","motion","environment","navigator","navigation","amap","avoidance","state","audio_import","camera"};
    String[] names=new String[pages.length+1];
    for(int i=0;i<pages.length;i++){String[] values=pageNames.get(pages[i]);names[i]=values["zh".equals(AppLocale.language(this))?1:0];}
    names[pages.length]=label("Connection settings","连接设置");
    new android.app.AlertDialog.Builder(this).setTitle(label("Go2 skills","Go2 技能")).setItems(names,(d,index)->{if(index==pages.length)runtime.show();else showPage(pages[index]);}).show();
  }
  private void showPage(String page) {
    if(web==null)return;
    if("home".equals(page)){pendingPage=null;if(ai.hasEncounter())ai.leaveEncounter();phoneLocation.stop();navigationPage=false;ai.setScene("general");updateChrome("home");web.loadUrl(HOME);return;}
    if("audio_preview_stop".equals(page)){web.evaluateJavascript("window.go2AudioStopPreview?.()",null);return;}
    if("follow_stop".equals(page)){web.evaluateJavascript("window.go2FollowStop?.()",null);return;}
    boolean story=page.startsWith("scene:");
    boolean followView="follow_view".equals(page);
    boolean autonomous=page.startsWith("follow_auto:");
    boolean follow=story||followView||autonomous||page.startsWith("follow:");
    boolean audio="audio".equals(page);
    boolean audioTools=audio||"audio_import".equals(page);
    boolean navigator="navigator".equals(page);
    if(!follow&&!java.util.Arrays.asList("state","camera","environment","motion","audio","audio_import","avoidance","navigation","amap","navigator").contains(page))return;
    if(!story&&ai.hasEncounter())ai.cancelSceneCommand();
    updateChrome(follow?"follow_view":page);
    navigationPage=navigator;
    if(navigator)phoneLocation.start();else phoneLocation.stop();
    if(!story&&!audio&&!followView&&ai.hasEncounter())ai.leaveEncounter();
    if(ai!=null)ai.setScene(story||audio&&ai.hasEncounter()?"encounter":audioTools?"audio":follow?"follow":"general");
    sceneChrome(ai.hasEncounter());
    String target=navigator?"http://127.0.0.1:18845/navigator":follow?"http://127.0.0.1:18845/follow-demo"+(story?"#native-scene":"#native"):audio?"http://127.0.0.1:18845/audio-library"+(ai.hasEncounter()?"#native-en":"#native"):"http://127.0.0.1:18845/";
    if(!target.equals(web.getUrl())){web.evaluateJavascript("window.go2FollowStop?.()",null);pendingPage=page;web.loadUrl(target);return;}
    if(story){
      web.evaluateJavascript("window.go2SceneUpdate?.("+ai.encounterSnapshot().toString()+")",null);
      if("scene:say".equals(page))web.evaluateJavascript("window.go2SceneSpeak?.()",null);
      if("scene:bark".equals(page))web.evaluateJavascript("window.go2SceneBark?.()",null);
      if("scene:end".equals(page)||"scene:hold".equals(page))web.evaluateJavascript("window.go2SceneStopAudio?.()",null);
    }
    else if(followView)return;
    else if(follow)web.evaluateJavascript("window.go2FollowStart?.("+org.json.JSONObject.quote(page.substring(page.indexOf(':')+1))+","+autonomous+")",null);
    else if(audio)return;
    else web.evaluateJavascript("document.getElementById("+org.json.JSONObject.quote("tab-"+("audio_import".equals(page)?"audio":page))+")?.click();window.scrollTo(0,0)",null);
  }
  private void sceneChrome(boolean scene) {
    if(toolbar!=null)updateChrome(currentPage);
    getWindow().getInsetsController().show(android.view.WindowInsets.Type.systemBars());
  }
  private void goBack(){
    web.evaluateJavascript("(()=>{const dialog=document.querySelector('dialog[open]');if(dialog){dialog.close();return true;}return false;})()",closed->{
      if(isDestroyed()||"true".equals(closed))return;
      if(!HOME.equals(web.getUrl())||ai.hasEncounter())showPage("home");else finish();
    });
  }
  private boolean allowed(android.net.Uri uri) {
    return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 18845;
  }
  private void load() {
    clearError();
    updateChrome("home");
    web.loadUrl(HOME);
  }
  private void clearError() {
    if (errorView != null) { frame.removeView(errorView); errorView = null; }
  }
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,this::goBack);
    LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
    setContentView(layout);
    runtime = new PhoneRuntime(this, this::load);
    phoneLocation = new PhoneLocation(this);
    ai = new InAppAi(this, this::showPage);
    layout.setBackgroundColor(getColor(R.color.go2_paper));
    toolbar = new LinearLayout(this); toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
    toolbar.setPadding(ai.view.dp(6),0,ai.view.dp(6),0);
    Button back = new Button(this);back.setText("‹");back.setTextSize(30);back.setContentDescription(label("Back to My Device","返回我的设备"));
    back.setBackgroundColor(android.graphics.Color.TRANSPARENT);back.setMinWidth(0);back.setPadding(0,0,0,0);
    back.setOnClickListener(v->showPage("home"));toolbar.addView(back,new LinearLayout.LayoutParams(ai.view.dp(44),ai.view.dp(48)));
    pageTitle = new TextView(this);pageTitle.setTextSize(17);pageTitle.setTextColor(getColor(R.color.go2_ink));pageTitle.setTypeface(null,android.graphics.Typeface.BOLD);
    pageTitle.setSingleLine(true);pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    toolbar.addView(pageTitle,new LinearLayout.LayoutParams(0,-2,1));
    Button menu=new Button(this);menu.setText("•••");menu.setTextSize(15);menu.setMinWidth(0);menu.setPadding(0,0,0,0);
    menu.setBackgroundColor(android.graphics.Color.TRANSPARENT);menu.setContentDescription(label("All skills and settings","全部技能与设置"));
    menu.setOnClickListener(v->showMenu());toolbar.addView(menu,new LinearLayout.LayoutParams(ai.view.dp(44),ai.view.dp(48)));
    layout.addView(toolbar);updateChrome("home");
    frame = new FrameLayout(this); layout.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    layout.addView(ai.view.root);
    web = new WebView(this);
    frame.addView(web);
    web.setBackgroundColor(getColor(R.color.go2_paper));
    WebView.setWebContentsDebuggingEnabled(true);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    web.addJavascriptInterface(new Object(){
      @JavascriptInterface public String getLanguage(){return AppLocale.language(Go2Activity.this);}
      @JavascriptInterface public void setLanguage(String value){
        if(!"en".equals(value)&&!"zh".equals(value))return;
        runOnUiThread(()->{if(!isDestroyed()){AppLocale.save(Go2Activity.this,value);sceneChrome(ai.hasEncounter());}});
      }
    },"Go2Locale");
    web.addJavascriptInterface(new Object(){
      @JavascriptInterface public void command(String text){
        if(text!=null&&text.length()<=120)runOnUiThread(()->{if(!isDestroyed())ai.sceneCommand(text);});
      }
    },"Go2Scene");
    web.addJavascriptInterface(new Object(){
      @JavascriptInterface public void openPage(String page){
        if(page==null||!pageNames.containsKey(page))return;
        runOnUiThread(()->{if(!isDestroyed())showPage(page);});
      }
      @JavascriptInterface public boolean isConnecting(){return runtime.isBusy();}
      @JavascriptInterface public void settings(){runOnUiThread(()->{if(!isDestroyed())runtime.show();});}
    },"Go2App");
    settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
        if (v.getUrl() == null || !allowed(android.net.Uri.parse(v.getUrl()))) return false;
        if (audioFileCallback != null) audioFileCallback.onReceiveValue(null);
        audioFileCallback = callback;
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        try { startActivityForResult(intent, AUDIO_FILE); }
        catch (android.content.ActivityNotFoundException e) { audioFileCallback.onReceiveValue(null); audioFileCallback = null; }
        return true;
      }
    });
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {if(!url.equals(v.getUrl()))return;v.evaluateJavascript("document.body.classList.add('native-shell');",null);if(pendingPage!=null){String page=pendingPage;pendingPage=null;showPage(page);}}
      @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
        clearError();
        if(ai.hasEncounter()&&!url.startsWith("http://127.0.0.1:18845/follow-demo")&&!url.startsWith("http://127.0.0.1:18845/audio-library")){ai.leaveEncounter();sceneChrome(false);}
        navigationPage="http://127.0.0.1:18845/navigator".equals(url);
        if(!navigationPage)phoneLocation.stop();
      }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        android.net.Uri uri = r.getUrl();
        if (r.isForMainFrame() && "https".equals(uri.getScheme()) && "uri.amap.com".equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
            && ("/search".equals(uri.getPath()) || "/navigation".equals(uri.getPath()))) {
          try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri)); }
          catch (android.content.ActivityNotFoundException e) { Toast.makeText(Go2Activity.this, R.string.no_browser, Toast.LENGTH_LONG).show(); }
          return true;
        }
        return !allowed(uri);
      }
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        android.net.Uri uri = r.getUrl();
        if(allowed(uri)) {
          String path=uri.getPath();
          if(java.util.Arrays.asList("/panel","/panel.js","/panel.css","/robot.png","/wallpaper.png").contains(path)) {
            String name="/panel".equals(path)?"panel.html":path.substring(1);
            String mime=name.endsWith(".html")?"text/html":name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":"image/png";
            try{return new WebResourceResponse(mime,name.endsWith(".png")?null:"UTF-8",getAssets().open(name));}catch(java.io.IOException ignored){}
          }
        }
        boolean maps = !r.isForMainFrame() && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 18846;
        return (allowed(uri) || maps) ? null : new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
      }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (!r.isForMainFrame()) return;
        LinearLayout error = new LinearLayout(Go2Activity.this);
        error.setBackgroundColor(getColor(R.color.go2_paper));
        error.setOrientation(LinearLayout.VERTICAL); error.setPadding(ai.view.dp(24), ai.view.dp(70), ai.view.dp(24), ai.view.dp(24));
        TextView title=new TextView(Go2Activity.this);title.setText(label("Let’s reconnect","重新连接"));title.setTextSize(28);title.setTextColor(getColor(R.color.go2_ink));title.setTypeface(null,android.graphics.Typeface.BOLD);error.addView(title);
        TextView message = new TextView(Go2Activity.this); message.setText(org.taku.go2lab.R.string.offline);message.setTextSize(14);message.setTextColor(0xff687b89);message.setPadding(0,ai.view.dp(15),0,ai.view.dp(25));
        Button retry = new Button(Go2Activity.this); retry.setText(org.taku.go2lab.R.string.retry);
        retry.setOnClickListener(view -> load());
        Button setup = new Button(Go2Activity.this); setup.setText(R.string.phone_settings);
        setup.setOnClickListener(view -> runtime.show());
        // Keep WebView attached while Chromium completes its network callback.
        // Avoid detaching the renderer during an in-flight network callback.
        for(Button b:new Button[]{setup,retry}){b.setAllCaps(false);b.setTextSize(14);b.setPadding(ai.view.dp(16),ai.view.dp(10),ai.view.dp(16),ai.view.dp(10));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(b==setup?0xff223b4f:0xffffffff);bg.setCornerRadius(ai.view.dp(26));b.setBackground(bg);b.setTextColor(b==setup?0xffffffff:0xff223b4f);}
        error.addView(message);error.addView(setup,new LinearLayout.LayoutParams(-1,ai.view.dp(52)));LinearLayout.LayoutParams retryParams=new LinearLayout.LayoutParams(-1,ai.view.dp(52));retryParams.topMargin=ai.view.dp(12);error.addView(retry,retryParams);clearError(); errorView = error;
        frame.addView(error);
      }
    });
    layout.setOnApplyWindowInsetsListener((v, insets) -> {
      android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
      layout.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
    });
    getWindow().getInsetsController().setSystemBarsAppearance(8, 8);
    load();
  }
  @Override protected void onPause() {
    if(phoneLocation!=null)phoneLocation.stop();
    if (ai != null) ai.pause();
    super.onPause();
  }
  @Override protected void onResume() { super.onResume(); if (ai != null) ai.resume(); if(navigationPage&&phoneLocation!=null)phoneLocation.start(); }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
    super.onRequestPermissionsResult(request,permissions,grants);
    if(request==52&&navigationPage&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)phoneLocation.start();
  }
  @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
    super.onActivityResult(request, result, data);
    if (request != AUDIO_FILE || audioFileCallback == null) return;
    android.net.Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
    audioFileCallback.onReceiveValue(uri != null && "content".equals(uri.getScheme()) ? new android.net.Uri[]{uri} : null);
    audioFileCallback = null;
  }
  @Override public void onDestroy() {
    if(phoneLocation!=null)phoneLocation.destroy();
    if (ai != null) ai.destroy();
    if (audioFileCallback != null) { audioFileCallback.onReceiveValue(null); audioFileCallback = null; }
    web.removeJavascriptInterface("Go2App");web.removeJavascriptInterface("Go2Scene");web.removeJavascriptInterface("Go2Locale");web.destroy(); super.onDestroy();
  }
}
