package org.taku.widgets;
public final class RefreshReceiver extends android.content.BroadcastReceiver {
 @Override public void onReceive(android.content.Context c,android.content.Intent i){
  String action=i.getAction();
  if("previous".equals(action)||"play-pause".equals(action)||"next".equals(action))try{ClockData.command(c,action);}catch(Exception e){android.widget.Toast.makeText(c,e.getMessage(),android.widget.Toast.LENGTH_SHORT).show();}
  Widgets.updateAll(c);
 }
}
