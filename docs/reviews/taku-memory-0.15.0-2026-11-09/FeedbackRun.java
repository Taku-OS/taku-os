package org.agentos.memory;
import org.json.*;
import java.util.*;
public class FeedbackRun{
 static int bad=0;static void check(boolean ok,String what){System.out.println((ok?"ok   ":"FAIL ")+what);if(!ok)bad++;}
 public static void main(String[] x)throws Exception{
  long t=1794500000000L;
  JSONArray log=new JSONArray()
   .put(new JSONObject().put("at",t).put("origin","agent").put("op","publish").put("cardId","memory.nudge.1").put("detail",JSONObject.NULL))
   .put(new JSONObject().put("at",t+1000).put("origin","gui").put("op","remove").put("cardId","memory.nudge.1").put("detail",JSONObject.NULL))
   .put(new JSONObject().put("at",t+1001).put("origin","gui").put("op","action:hide").put("cardId","memory.nudge.1").put("detail",new JSONObject().put("values",new JSONObject()).put("effect","dismiss")))
   .put(new JSONObject().put("at",t+2000).put("origin","gui").put("op","pin").put("cardId","agent-generated:x").put("detail",new JSONObject().put("index",0)))
   .put(new JSONObject().put("at",t+3000).put("origin","gui").put("op","action:go").put("cardId","agent-generated:x").put("detail",new JSONObject().put("values",new JSONObject()).put("effect","ask-taku")))
   .put(new JSONObject().put("at",t+4000).put("origin","gui").put("op","reorder-pins").put("cardId",JSONObject.NULL).put("detail",new JSONObject().put("ids",new JSONArray().put("agent-generated:x"))));
  JSONObject snap=new JSONObject().put("removed",new JSONArray().put("memory.nudge.1").put("memory.brief.2")).put("recentActions",log);
  JSONArray hidden=FeedFeedback.merge(new JSONArray(),snap,"memory.nudge.1",t+9000);
  check(hidden!=null&&hidden.length()==2&&FeedFeedback.reactions(hidden).equals(List.of("removed")),"hidden "+hidden);
  check(FeedFeedback.merge(hidden,snap,"memory.nudge.1",t+9000)==null,"idempotent");
  JSONArray used=FeedFeedback.merge(null,snap,"agent-generated:x",t+9000);
  check(FeedFeedback.reactions(used).equals(List.of("asked","pinned")),"used "+used);
  JSONArray late=FeedFeedback.merge(new JSONArray(),snap,"memory.brief.2",t+9000);
  check(late.length()==1&&late.getJSONObject(0).getString("origin").isEmpty(),"late "+late);
  JSONObject restored=new JSONObject(snap.toString());restored.put("removed",new JSONArray());restored.getJSONArray("recentActions").put(new JSONObject().put("at",t+6000).put("origin","gui").put("op","restore").put("cardId","memory.nudge.1").put("detail",JSONObject.NULL));
  JSONArray back=FeedFeedback.merge(hidden,restored,"memory.nudge.1",t+9000);
  check(back!=null&&FeedFeedback.reactions(back).isEmpty(),"restored "+back);
  JSONArray many=new JSONArray();for(int i=0;i<30;i++)many.put(new JSONObject().put("at",t+i).put("origin","gui").put("op",i%2==0?"pin":"unpin").put("cardId","memory.nudge.5").put("detail",JSONObject.NULL));
  JSONArray capped=FeedFeedback.merge(null,new JSONObject().put("recentActions",many),"memory.nudge.5",t);
  check(capped.length()==FeedFeedback.KEEP&&capped.getJSONObject(FeedFeedback.KEEP-1).getLong("at")==t+29&&FeedFeedback.reactions(capped).isEmpty(),"capped");
  System.out.println(bad==0?"ALL OK":bad+" failed");System.exit(bad);
 }
}
