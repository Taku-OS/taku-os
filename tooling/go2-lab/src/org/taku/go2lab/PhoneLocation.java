package org.taku.go2lab;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Foreground phone fixes only. A phone fix never automatically anchors the robot. */
final class PhoneLocation implements LocationListener {
  private final Activity activity;
  private final LocationManager manager;
  private final ExecutorService queue=Executors.newSingleThreadExecutor();
  private final AtomicBoolean sending=new AtomicBoolean();
  private final String session=UUID.randomUUID().toString();
  private int sequence;
  private boolean active;
  private boolean requested;
  PhoneLocation(Activity activity) {this.activity=activity;manager=activity.getSystemService(LocationManager.class);}
  void start() {
    if(active)return;
    if(activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
      if(requested)return;
      requested=true;
      activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},52);return;
    }
    active=true;
    for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}) {
      try {if(manager.isProviderEnabled(provider))manager.requestLocationUpdates(provider,1000,0,this);}
      catch(SecurityException|IllegalArgumentException ignored){}
    }
  }
  void stop(){active=false;manager.removeUpdates(this);}
  void destroy(){stop();queue.shutdownNow();}
  @Override public void onLocationChanged(Location l) {
    if(!active||!l.hasAccuracy()||!sending.compareAndSet(false,true))return;
    int id=++sequence;
    queue.execute(()->{
      try {
        long age=(SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos())/1000000;
        if(age<0||age>=30000)return;
        AppApi.post("navigator/phone-fix",new JSONObject().put("session",session).put("sequence",id)
          .put("longitude",l.getLongitude()).put("latitude",l.getLatitude()).put("accuracy",l.getAccuracy())
          .put("ageMs",age).put("provider",l.getProvider()).put("mock",l.isMock()));
      } catch(Exception ignored){} finally{sending.set(false);}
    });
  }
}
