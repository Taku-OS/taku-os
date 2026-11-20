package org.agentos.memory;

import android.app.*;
import android.content.*;

/** Puts a proactive reminder in front of the user: the system display when it is connected, otherwise a notification. */
final class ProactiveNotice {
 static final String CHANNEL="proactive";
 static String heading(Context c,String kind,String title){
  if(kind.equals("evening"))return title==null||title.isBlank()?c.getString(R.string.nudge_evening_title):c.getString(R.string.nudge_evening_prefix,title);
  return title==null||title.isBlank()?c.getString(R.string.nudge_kind_message):c.getString(R.string.nudge_message_title,title);
 }
 static long deliver(Context c,MemoryStore s,String kind,String title,String body,String anchor,String context,long now)throws Exception{return deliver(c,s,kind,title,body,anchor,context,now,null);}
 /** @param extra for message reminders: {message, conflict, package} of the notification that triggered it, used by the feed card. @return the reminder's id */
 static long deliver(Context c,MemoryStore s,String kind,String title,String body,String anchor,String context,long now,org.json.JSONObject extra)throws Exception{
  long id=s.saveNudge(kind,title==null?"":title,body,anchor,context,now);   // Store the original title and localize its framing on display; context preserves what the model saw for assistant handoff.
  if(s.prefs.getBoolean("feedCards",false)){Context app=c.getApplicationContext();FEED.execute(()->feed(app,s,id,kind,title,body,now,extra));}
  String heading=heading(c,kind,title);
  if(ObservationService.nudge("nudge-"+id,heading,body))return id;   // Use the island-style hint when system surfaces are available.
  NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm==null)return id;
  nm.notify("nudge",(int)id,build(c,id,heading,body,true));
  return id;
 }
 static Notification build(Context c,long id,String heading,String body,boolean ask){
  NotificationManager nm=c.getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel(CHANNEL,c.getString(R.string.nudge_channel),NotificationManager.IMPORTANCE_DEFAULT));
  Intent open=new Intent(c,MemoryActivity.class).putExtra("tab","suggestions").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  PendingIntent pi=PendingIntent.getActivity(c,(int)id,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification.Builder b=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.taku_brand_mark).setContentTitle(heading).setContentText(body.split("\n",2)[0]).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true);
  if(ask){
   // Send only to this app's unexported receiver; reminder ID is the sole argument and Broker reads the body.
   Intent hand=new Intent(c,NudgeAction.class).setAction(NudgeAction.ASK).putExtra("id",id);
   PendingIntent ap=PendingIntent.getBroadcast(c,(int)id,hand,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   b.addAction(new Notification.Action.Builder(null,c.getString(R.string.nudge_ask),ap).build());
  }
  return b.build();
 }
 /** Home feed work, one call at a time and off the delivery thread: a card the home feed's AI designs can take up to two minutes. */
 static final java.util.concurrent.ExecutorService FEED=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"memory-feed");t.setDaemon(true);return t;});
 /** How far back reminders are read for reactions: every card expires within two days. */
 static final long WINDOW=3L*86400000L;
 /**
  * Also puts the reminder on the Taku home feed when that is turned on and installed; a failure there never blocks the notification.
  * With feedDescribe the home feed's AI designs the card from a description; if it cannot, or its card changes the buttons or the
  * text, the fixed layout is published instead. Every card memory leaves on the feed is recorded in kv feedExpiring and
  * retracted when it expires or the user removes it: hidden cards still consume device storage.
  */
 static void feed(Context c,MemoryStore s,long id,String kind,String title,String body,long now,org.json.JSONObject extra){
  try{
   readBack(c,s,now);sweep(c,s,now);
   boolean evening=kind.equals("evening");java.util.List<String> items=java.util.Arrays.asList(body.split("\n"));
   String message=extra==null?"":extra.optString("message"),pkg=extra==null?"":extra.optString("package");boolean conflict=extra!=null&&extra.optBoolean("conflict");
   String cardId=null;
   if(s.prefs.getBoolean("feedDescribe",false))cardId=described(c,s,evening?FeedCards.describeEvening(c,title,items,now):FeedCards.describeMessage(c,title,message,body,conflict,pkg,now));
   if(cardId==null){
    org.json.JSONObject card=evening?FeedCards.evening(c,"memory.brief."+id,title,items,now):FeedCards.message(c,"memory.nudge."+id,title,message,body,conflict,pkg,now);
    FeedCards.publish(c,card);cardId=card.getString("id");expiring(s,cardId,card.getLong("expiresAt"));
   }
   s.nudgeCard(id,cardId);
   if(evening){   // Retract last night's brief only after the new one publishes; retain it if the new card is rejected.
    String last=s.kvGet("feedBriefCard","");s.kvSet("feedBriefCard",cardId);
    if(!last.isEmpty()&&!last.equals(cardId))try{FeedCards.retract(c,last);forget(s,last);}catch(FeedCards.Refused r){if(FeedCards.gone(r))forget(s,last);}catch(Exception later){}   // Keep failed retractions registered for another attempt at expiry.
   }
  }catch(FeedCards.Refused r){android.util.Log.w("TakuMemory",r.code.equals("FEED_FULL")?"Home cards are full; sending only a notification this time":"Home card was not published: "+r.getMessage());}
  catch(Exception e){android.util.Log.w("TakuMemory","Home card was not published: "+e.getMessage());}
 }
 /** Refusals worth one more try: the model's JSON or body did not pass, or another generation was running. */
 static final java.util.Set<String> RETRY=java.util.Set.of("INVALID_MODEL_OUTPUT","INVALID_SPEC","BUSY");
 /**
  * Asks the home feed's AI for the card and checks it, at most twice; returns its id, or null to use the fixed layout
  * (nothing of a described card is left on the feed then). A call the home feed never answered is not retried: it may still
  * have made the card, so memory looks for it on the feed first ({@link #adopt}). A full feed (FEED_FULL) is passed on:
  * publishing the fixed card then would push out the oldest card on the feed, which may be another app's.
  */
 static String described(Context c,MemoryStore s,FeedCards.Ask ask)throws Exception{
  for(int attempt=1;attempt<=2;attempt++){
   String id;long started=System.currentTimeMillis();
   try{id=FeedCards.generate(c,ask.prompt).getString("id");}
   catch(FeedCards.Refused no){
    android.util.Log.i("TakuMemory","Home AI did not compose a card ("+no.code+", attempt "+attempt+")");
    if(no.code.equals("FEED_FULL"))throw no;
    if(!RETRY.contains(no.code))return null;
    if(no.code.equals("BUSY"))Thread.sleep(5000);
    continue;
   }
   catch(Exception lost){   // Missing/unreadable reply means unknown outcome; check Home for the card first.
    String found=adopt(c,s,ask,started);
    android.util.Log.i("TakuMemory","Home AI did not respond ("+lost.getMessage()+"），"+(found==null?"This card is not on Home either":"This card was found on Home"));
    return found;
   }
   String bad;try{bad=FeedCheck.check(FeedCards.get(c,id),ask.actions,ask.texts);}catch(Exception e){bad="unreadable "+e.getMessage();}
   if(bad==null){expiring(s,id,ask.expires);return id;}   // Home-generated cards do not expire themselves; Memory retracts them when due.
   try{FeedCards.retract(c,id);}catch(Exception later){expiring(s,id,0);}   // Leave failed retraction for the next cleanup.
   android.util.Log.i("TakuMemory","The card composed by Home AI did not meet requirements ("+bad+", attempt "+attempt+"); withdrawn");
  }
  android.util.Log.i("TakuMemory","Home AI failed to compose a usable card twice; using a fixed layout");
  return null;
 }
 /**
  * After a generation whose answer never came: the card it made, if any, is a new `agent-generated:` card created since the
  * call with exactly the description's buttons (only one generation runs at a time). It is kept when it passes
  * {@link FeedCheck} and retracted otherwise; returns its id, or null when there is none.
  */
 static String adopt(Context c,MemoryStore s,FeedCards.Ask ask,long started){
  try{
   org.json.JSONObject snap=FeedCards.list(c);String kept=null;org.json.JSONObject due=expiringCards(s);
   java.util.List<org.json.JSONObject> shown=new java.util.ArrayList<>();
   for(String part:new String[]{"cards","pins"}){org.json.JSONArray a=snap.optJSONArray(part);if(a!=null)for(int i=0;i<a.length();i++)if(a.optJSONObject(i)!=null)shown.add(a.optJSONObject(i));}
   for(org.json.JSONObject card:shown){
    String id=card.optString("id");if(!id.startsWith("agent-generated:")||due.has(id)||!sameButtons(card.optJSONArray("actions"),ask.actions))continue;
    org.json.JSONObject full=FeedCards.get(c,id);if(full.optLong("createdAt")<started-5000)continue;
    if(kept==null&&FeedCheck.check(full,ask.actions,ask.texts)==null){kept=id;expiring(s,id,ask.expires);due=expiringCards(s);}
    else try{FeedCards.retract(c,id);}catch(Exception later){expiring(s,id,0);due=expiringCards(s);}
   }
   return kept;
  }catch(Exception e){return null;}
 }
 /** The feed summary's actions [{id, label}] are the description's actions, same ids and labels. */
 static boolean sameButtons(org.json.JSONArray summary,org.json.JSONObject actions){
  if(summary==null||summary.length()!=actions.length())return false;
  for(int i=0;i<summary.length();i++){org.json.JSONObject a=summary.optJSONObject(i);if(a==null)return false;org.json.JSONObject want=actions.optJSONObject(a.optString("id"));if(want==null||!FeedCheck.norm(want.optString("label")).equals(FeedCheck.norm(a.optString("label"))))return false;}
  return true;
 }
 /** Replays durable Feed events into reminders before acknowledging the cursor. Runs on {@link #FEED}. */
 static int readBack(Context c,MemoryStore s,long now){
  try{
   org.json.JSONArray mine=s.nudgeCards(now-WINDOW);if(mine.length()==0)return 0;
   org.json.JSONObject snap=FeedCards.list(c);org.json.JSONObject due=expiringCards(s);
   java.util.Set<Long> changed=new java.util.HashSet<>();
   java.util.Map<String,org.json.JSONObject> cards=new java.util.HashMap<>();
   for(int i=0;i<mine.length();i++){org.json.JSONObject n=mine.getJSONObject(i);cards.put(n.getString("card"),n);}
   String head=snap.optString("eventCursor");
   if(!head.isEmpty()){
    String after=s.kvGet("feedFeedbackCursor","0");
    // Clearing the Feed application's data creates a new database epoch.
    if(!after.equals("0")&&!after.substring(0,after.lastIndexOf(':')).equals(head.substring(0,head.lastIndexOf(':'))))after="0";
    String checkpoint=FeedCards.events(c,after,event->{
     org.json.JSONObject n=cards.get(event.optString("cardId"));if(n==null)return;
     org.json.JSONObject frame=new org.json.JSONObject().put("events",new org.json.JSONArray().put(event));
     org.json.JSONArray merged=FeedFeedback.merge(n.getJSONArray("feedback"),frame,n.getString("card"),now);
     if(merged!=null){s.nudgeFeedback(n.getLong("id"),merged);n.put("feedback",merged);changed.add(n.getLong("id"));}
    });
    s.kvSet("feedFeedbackCursor",checkpoint);
   }
   for(int i=0;i<mine.length();i++){
    org.json.JSONObject n=mine.getJSONObject(i);String card=n.getString("card");
    // Older installed launchers have no eventCursor; keep upgrade compatibility.
    if(head.isEmpty()){
     org.json.JSONArray merged=FeedFeedback.merge(n.getJSONArray("feedback"),snap,card,now);
     if(merged!=null){s.nudgeFeedback(n.getLong("id"),merged);n.put("feedback",merged);changed.add(n.getLong("id"));}
    }
    // Dealt with on Home: the notification or system hint for the same reminder would only repeat it.
    if(changed.contains(n.getLong("id"))&&FeedFeedback.handled(n.getJSONArray("feedback")))settle(c,n.getLong("id"));
    if(due.has(card)&&FeedFeedback.has(snap.optJSONArray("removed"),card))
     try{FeedCards.retract(c,card);forget(s,card);}catch(FeedCards.Refused r){if(FeedCards.gone(r))forget(s,card);}catch(Exception later){}
   }
   return changed.size();
  }catch(Exception e){android.util.Log.i("TakuMemory","Could not read Home card feedback: "+e.getMessage());return 0;}
 }
 /** Takes down the notification and the system hint of one reminder; the stored reminder and its card are kept. */
 static void settle(Context c,long id){
  ObservationService.withdraw("nudge-"+id);
  NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm!=null)nm.cancel("nudge",(int)id);
 }
 /**
  * The user handed this reminder to Taku outside Home (notification or Suggestions page). Clears its notification and hint,
  * which still offer the hand-off; the notification path then posts its own "handed over" status. A message reminder's Home
  * card is retracted too, unless the user pinned it there: it only repeats the same reminder and button. The evening brief
  * card stays, since it is the overview of tomorrow. Card work runs on {@link #FEED} after reading reactions back.
  */
 static void handedOff(Context c,MemoryStore s,long id){
  settle(c,id);
  Context app=c.getApplicationContext();
  FEED.execute(()->{
   try{
    readBack(app,s,s.now());
    org.json.JSONObject n=s.nudge(id);String card=n.optString("card");
    if(card.isEmpty()||n.optString("kind").equals("evening")||FeedFeedback.reactions(n.optJSONArray("feedback")).contains("pinned"))return;
    try{FeedCards.retract(app,card);forget(s,card);}catch(FeedCards.Refused r){if(FeedCards.gone(r))forget(s,card);}
   }catch(Exception e){android.util.Log.i("TakuMemory","Could not retract the card of a handed-off reminder: "+e.getMessage());}
  });
 }
 /** Retracts memory's cards whose time is up. Runs on {@link #FEED}; the list lives in kv feedExpiring as {cardId: until}. Returns how many it cleared. */
 static int sweep(Context c,MemoryStore s,long now){
  org.json.JSONObject due=expiringCards(s);int n=0;
  for(String id:FeedCheck.keys(due)){
   if(due.optLong(id)>now)continue;
   try{FeedCards.retract(c,id);due.remove(id);n++;}
   catch(FeedCards.Refused r){if(FeedCards.gone(r)){due.remove(id);n++;}}   // Already absent.
   catch(Exception later){}   // Home temporarily unavailable; retry later.
  }
  if(n>0)s.kvSet("feedExpiring",due.toString());
  return n;
 }
 /**
  * Takes every card memory left on the home feed off it, for when the feed cards (or speaking up first) are turned off.
  * Cards the home feed cannot be reached for stay recorded and go at the next nightly pass. Returns how many it cleared.
  */
 static int withdraw(Context c,MemoryStore s){
  org.json.JSONObject due=expiringCards(s);java.util.Set<String> ids=new java.util.LinkedHashSet<>(FeedCheck.keys(due));
  String brief=s.kvGet("feedBriefCard","");if(!brief.isEmpty())ids.add(brief);
  try{org.json.JSONArray recent=s.nudgeCards(s.now()-WINDOW);for(int i=0;i<recent.length();i++)ids.add(recent.getJSONObject(i).getString("card"));}catch(Exception e){}   // Cards published before 0.15 without registration.
  int n=0;
  for(String id:ids){
   try{FeedCards.retract(c,id);due.remove(id);n++;}
   catch(FeedCards.Refused r){if(FeedCards.gone(r))due.remove(id);}
   catch(Exception later){if(!due.has(id))try{due.put(id,0);}catch(org.json.JSONException x){}}
  }
  s.kvSet("feedExpiring",due.toString());if(!due.has(brief))s.kvSet("feedBriefCard","");
  return n;
 }
 static void withdrawLater(Context c,MemoryStore s){Context app=c.getApplicationContext();FEED.execute(()->withdraw(app,s));}
 /** The nightly pass: reads reactions and clears expired cards, or withdraws them all when the feed cards are off. */
 static void sweepLater(Context c,MemoryStore s,long now){tidyLater(c,s,now);}
 /** Set while a tidy pass is waiting on {@link #FEED}, so a long generation does not pile them up. */
 static final java.util.concurrent.atomic.AtomicBoolean TIDY=new java.util.concurrent.atomic.AtomicBoolean();
 /**
  * Also called by the 15-minute maintenance job, so it does not depend on a nightly organize that may fail or be turned
  * off: cards the home feed's AI made have no expiry of their own and would otherwise stay up. Feedback resumes from
  * the saved Feed event cursor (or the legacy launcher's recentActions). Decides locally first; it calls the home feed only when a recorded card
  * is due, a recent reminder has a card to read reactions for, or the feed cards were turned off with cards still up.
  */
 static void tidyLater(Context c,MemoryStore s,long now){
  org.json.JSONObject due=expiringCards(s);
  boolean on=s.feedOn(),expired=false,recent=false;
  for(String id:FeedCheck.keys(due))if(due.optLong(id)<=now){expired=true;break;}
  if(on)try{recent=s.nudgeCards(now-WINDOW).length()>0;}catch(Exception e){}
  if(on?!(expired||recent):due.length()==0)return;
  if(!FeedCards.available(c)||!TIDY.compareAndSet(false,true))return;
  Context app=c.getApplicationContext();boolean sweepToo=expired,readToo=recent;
  FEED.execute(()->{try{if(!on)withdraw(app,s);else{if(readToo)readBack(app,s,now);if(sweepToo)sweep(app,s,now);}}finally{TIDY.set(false);}});
 }
 static org.json.JSONObject expiringCards(MemoryStore s){try{return new org.json.JSONObject(s.kvGet("feedExpiring","{}"));}catch(org.json.JSONException e){return new org.json.JSONObject();}}
 static void expiring(MemoryStore s,String id,long until)throws org.json.JSONException{s.kvSet("feedExpiring",expiringCards(s).put(id,until).toString());}
 static void forget(MemoryStore s,String id){org.json.JSONObject due=expiringCards(s);if(due.remove(id)!=null)s.kvSet("feedExpiring",due.toString());}
}
