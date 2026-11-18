package org.taku.weather;
public final class WidgetApplication extends android.app.Application {
 @Override public void onCreate(){super.onCreate();WeatherData.schedule(this);}
}
