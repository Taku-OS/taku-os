package org.agentos.memory;
import org.json.*;
import java.nio.file.*;
import java.util.*;
/** Runs memory's FeedCheck (the same source file as the app) on cards the home feed's generator made off the device. */
public class CheckRun{
 public static void main(String[] a)throws Exception{
  JSONArray asks=new JSONArray(Files.readString(Path.of(a[0]))),gen=new JSONArray(Files.readString(Path.of(a[1])));
  Map<String,JSONObject> byKey=new HashMap<>();for(int i=0;i<asks.length();i++)byKey.put(asks.getJSONObject(i).getString("key"),asks.getJSONObject(i));
  JSONArray out=new JSONArray();
  for(int i=0;i<gen.length();i++){JSONObject g=gen.getJSONObject(i),ask=byKey.get(g.getString("key"));
   String r;if(!g.optBoolean("ok"))r="refused "+g.optString("code");
   else{List<String> texts=new ArrayList<>();JSONArray t=ask.getJSONArray("texts");for(int j=0;j<t.length();j++)texts.add(t.getString(j));
    String bad=FeedCheck.check(g.getJSONObject("card"),ask.getJSONObject("actions"),texts);r=bad==null?"kept":"changed: "+bad;}
   out.put(new JSONObject().put("key",g.getString("key")).put("result",r));System.out.println(g.getString("key")+"\t"+r);}
  if(a.length>2)Files.writeString(Path.of(a[2]),out.toString(1));
 }
}
