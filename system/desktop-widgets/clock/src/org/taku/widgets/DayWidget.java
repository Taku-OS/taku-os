package org.taku.widgets;
public final class DayWidget extends android.appwidget.AppWidgetProvider {
 @Override public void onUpdate(android.content.Context c,android.appwidget.AppWidgetManager m,int[] ids) { for(int id:ids) Widgets.render(c,m,id,"DayWidget"); ClockData.schedule(c); }
 @Override public void onAppWidgetOptionsChanged(android.content.Context c,android.appwidget.AppWidgetManager m,int id,android.os.Bundle b) { Widgets.render(c,m,id,"DayWidget"); }
}
