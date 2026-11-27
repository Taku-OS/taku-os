package org.agentos.assistant;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONObject;

/** Hosts the Broker's Settings observer in an ActivityManager-managed system process. */
public final class MagpieSettingsObserverService extends Service {
 final Handler main=new Handler(Looper.getMainLooper());
 final ContentObserver observer=new ContentObserver(main){
  @Override public void onChange(boolean self,Uri uri){String key=uri==null?null:uri.getLastPathSegment();if(uri==null||uri.equals(Settings.Global.CONTENT_URI)||key==null||key.equals("taku_system_ai_config"))notifyBroker();}
  @Override public void onChange(boolean self){onChange(self,null);}
 };
 final Runnable notify=new Runnable(){public void run(){try{NativeClient.async(new JSONObject().put("op","magpieSettingsChanged"),new NativeClient.Callback(){public void ready(JSONObject ignored){}public void failed(String ignored){main.postDelayed(notify,5000);}});}catch(Exception e){main.postDelayed(this,5000);}}};
 void notifyBroker(){main.removeCallbacks(notify);main.post(notify);}
 @Override public void onCreate(){super.onCreate();TakuAccountTokenServer.ensureStarted(this);Thread init=new Thread(()->TakuAuthManager.get(getApplicationContext()).ensureUnlockedLoaded(),"taku-account-init");init.setDaemon(true);init.start();getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI,true,observer);notifyBroker();}
 @Override public int onStartCommand(Intent intent,int flags,int startId){notifyBroker();return START_STICKY;}
 @Override public void onDestroy(){main.removeCallbacks(notify);getContentResolver().unregisterContentObserver(observer);super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
