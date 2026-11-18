package org.agentos.memory;

/** Test build: no display or notification; Proactive's decisions are checked directly. */
final class ProactiveNotice {
 static String heading(android.content.Context c,String kind,String title){return title;}
 static void deliver(android.content.Context c,MemoryStore s,String kind,String title,String body,String anchor,String context,long now)throws Exception{s.saveNudge(kind,title,body,anchor,context,now);}
 static void deliver(android.content.Context c,MemoryStore s,String kind,String title,String body,String anchor,String context,long now,org.json.JSONObject extra)throws Exception{s.saveNudge(kind,title,body,anchor,context,now);}
 static void sweepLater(android.content.Context c,MemoryStore s,long now){}
 static void tidyLater(android.content.Context c,MemoryStore s,long now){}
 static void withdrawLater(android.content.Context c,MemoryStore s){}
}
