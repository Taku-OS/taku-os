package org.agentos.memory;

import android.app.*;
import android.content.*;
import org.json.*;

/** "Hand to the assistant" on a proactive reminder: the Broker reads the reminder back and queues a read-only assistant task. */
public final class NudgeAction extends BroadcastReceiver {
 static final String ASK="org.agentos.memory.NUDGE_ASK";
 @Override public void onReceive(Context c,Intent intent){
  if(!ASK.equals(intent.getAction()))return;long id=intent.getLongExtra("id",-1);if(id<=0)return;
  PendingResult pending=goAsync();MemoryStore s=MemoryStore.get(c);
  new Thread(()->{
   String text;
   try{JSONObject n=s.nudge(id);JSONObject r=BrokerClient.call("nudgeTask",new JSONObject().put("id",id));text=c.getString(R.string.nudge_asked);
    NotificationManager nm=c.getSystemService(NotificationManager.class);
    if(nm!=null)nm.notify("nudge",(int)id,ProactiveNotice.build(c,id,ProactiveNotice.heading(c,n.getString("kind"),n.getString("title")),text+"\n\n"+n.getString("body"),false));
    if(r.optBoolean("alreadyAccepted"))text=c.getString(R.string.nudge_already_asked);
   }catch(Exception e){text=c.getString(R.string.nudge_ask_failed,e.getMessage()==null?"":e.getMessage());}
   String shown=text;new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{android.widget.Toast.makeText(c,shown,android.widget.Toast.LENGTH_SHORT).show();pending.finish();});
  },"nudge-ask").start();
 }
}
