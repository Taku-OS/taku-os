package org.taku.widgets;
public final class WidgetApplication extends android.app.Application {
 @Override public void onCreate(){super.onCreate();ClockData.init(this);
  android.content.IntentFilter filter=new android.content.IntentFilter();filter.addAction(android.content.Intent.ACTION_TIME_TICK);filter.addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED);filter.addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
  registerReceiver(new RefreshReceiver(),filter,android.content.Context.RECEIVER_NOT_EXPORTED);
 }
}
