package org.agentos.memory;

import android.app.Notification;
import android.os.*;
import android.service.notification.*;
import java.util.concurrent.*;

/**
 * Reads notifications from user-selected apps through the platform notification listener (requires the user's
 * notification-access grant). Title and text only; ongoing, foreground-service, group-summary, secret and
 * one-time-code notifications are skipped. Nothing is posted, dismissed or acted on.
 */
public final class NotificationTap extends NotificationListenerService {
 final ExecutorService io=Executors.newSingleThreadExecutor();
 @Override public void onNotificationPosted(StatusBarNotification sbn){
  try{
   MemoryStore store=MemoryStore.get(this);String pkg=sbn.getPackageName();
   if(pkg==null||!store.observing(pkg)||pkg.equals(getPackageName())||pkg.equals("org.agentos.assistant")||!getSystemService(UserManager.class).isUserUnlocked())return;
   Notification n=sbn.getNotification();
   if(sbn.isOngoing()||(n.flags&(Notification.FLAG_FOREGROUND_SERVICE|Notification.FLAG_GROUP_SUMMARY))!=0||n.visibility==Notification.VISIBILITY_SECRET)return;
   Bundle x=n.extras;if(x==null)return;
   CharSequence title=x.getCharSequence(Notification.EXTRA_TITLE),text=x.getCharSequence(Notification.EXTRA_BIG_TEXT);if(text==null)text=x.getCharSequence(Notification.EXTRA_TEXT);
   String t=title==null?"":title.toString().trim(),b=text==null?"":text.toString().trim();if(b.isEmpty())return;
   String body=(t.isEmpty()?"":t+"\n")+b;
   if(Privacy.SECRET.matcher(body).find())return;
   long generation=store.prefs.getLong("generation",0);String label=t.isEmpty()?pkg:t;
   io.execute(()->{try{store.ingest(pkg,"notification",label,body,generation);}catch(Exception e){store.failed("Notification",e);}});
  }catch(Exception e){try{MemoryStore.get(this).failed("Notification",e);}catch(Exception ignored){}}
 }
 @Override public void onDestroy(){io.shutdown();super.onDestroy();}
}
