package org.taku.weather;
public final class RefreshReceiver extends android.content.BroadcastReceiver {
 @Override public void onReceive(android.content.Context c,android.content.Intent i){WeatherData.schedule(c);WeatherData.refresh(c);}
}
