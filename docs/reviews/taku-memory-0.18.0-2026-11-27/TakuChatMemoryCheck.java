package org.agentos.lab;
import org.json.*;
/** JVM check of the Broker side: which turns are sent to memory, with what text, in what order. */
public final class TakuChatMemoryCheck {
 static int n;static void check(boolean b,String what){n++;if(!b)throw new AssertionError(what);System.out.println("ok  "+what);}
 static JSONObject ev(String m,JSONObject p){return new JSONObject().put("method",m).put("params",p);}
 static void settle()throws Exception{Thread.sleep(300);}
 public static void main(String[] a)throws Exception{
  String th="0d6c1a52-6f44-4b71-9c0b-7a1d2f3e4a5b";
  JSONArray input=new JSONArray().put(new JSONObject().put("type","text").put("text","  My bike tune-up cost $25  ")).put(new JSONObject().put("type","text").put("text","[panel context]")).put(new JSONObject().put("type","localImage").put("path","/x.png"));
  TakuChatMemory.starting("turn/start",new JSONObject().put("threadId",th).put("input",input));
  TakuChatMemory.event(ev("turn/started",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t1"))));
  TakuChatMemory.event(ev("item/completed",new JSONObject().put("threadId",th).put("turnId","t1").put("item",new JSONObject().put("type","agentMessage").put("text","Checking memory first."))));
  TakuChatMemory.event(ev("item/completed",new JSONObject().put("threadId",th).put("turnId","t1").put("item",new JSONObject().put("type","agentMessage").put("text","Noted: the chain cost $25."))));
  TakuChatMemory.event(ev("turn/completed",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t1").put("status","completed"))));
  settle();
  check(MemoryBridge.calls.size()==2,"a primary turn sends the user's message and Taku's reply");
  JSONObject u=MemoryBridge.calls.get(0).getJSONObject("params"),r=MemoryBridge.calls.get(1).getJSONObject("params");
  check(MemoryBridge.calls.get(0).getString("method").equals("chat")&&u.getString("speaker").equals("user")&&u.getString("text").equals("My bike tune-up cost $25")&&u.getString("thread").equals(th)&&u.getString("turn").equals("t1"),"the user's text is the first text item, trimmed; panel context and images are not sent");
  check(r.getString("speaker").equals("assistant")&&r.getString("text").equals("Noted: the chain cost $25."),"the reply is the turn's last message, kept as written when plain");
  MemoryBridge.calls.clear();
  TakuChatMemory.starting("turn/start",new JSONObject().put("threadId",th).put("input",input).put("outputSchema",new JSONObject()));
  TakuChatMemory.event(ev("turn/started",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t2"))));
  TakuChatMemory.event(ev("turn/completed",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t2").put("status","completed"))));
  MemoryGen.owned.add("org");
  TakuChatMemory.starting("turn/start",new JSONObject().put("threadId","org").put("input",input));
  TakuChatMemory.event(ev("turn/started",new JSONObject().put("threadId","org").put("turn",new JSONObject().put("id","t3"))));
  TakuChatMemory.starting("thread/realtime/start",new JSONObject().put("threadId",th).put("input",input));
  TakuChatMemory.event(ev("turn/started",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t4"))));
  settle();
  check(MemoryBridge.calls.isEmpty(),"structured tasks, organizer threads and realtime turns are not sent");
  TakuChatMemory.starting("turn/start",new JSONObject().put("threadId",th).put("input",input));
  TakuChatMemory.event(ev("turn/started",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t5"))));
  TakuChatMemory.event(ev("item/completed",new JSONObject().put("threadId",th).put("turnId","t5").put("item",new JSONObject().put("type","agentMessage").put("text","Half an answer"))));
  TakuChatMemory.event(ev("turn/completed",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t5").put("status","interrupted"))));
  settle();
  check(MemoryBridge.calls.size()==1&&MemoryBridge.calls.get(0).getJSONObject("params").getString("speaker").equals("user"),"an interrupted turn keeps the user's message but not the partial reply");
  MemoryBridge.calls.clear();
  TakuChatMemory.event(ev("item/completed",new JSONObject().put("threadId",th).put("turnId","t9").put("item",new JSONObject().put("type","agentMessage").put("text","Unrelated"))));
  TakuChatMemory.event(ev("turn/completed",new JSONObject().put("threadId",th).put("turn",new JSONObject().put("id","t9").put("status","completed"))));
  settle();
  check(MemoryBridge.calls.isEmpty(),"replies of turns whose message was not sent are not sent");
  check(TakuChatMemory.readable(th,"t1","@taku.reply/1\n{\"type\":\"begin\" broken").isEmpty(),"a framed reply that does not parse is not kept as protocol text");
  check(TakuChatMemory.readable(th,"t1","{\"type\":\"note\"} is plain JSON-looking text").startsWith("{"),"text the protocol shows as plain is kept as written");
  System.out.println("TakuChatMemoryCheck: "+n+" checks passed");
 }
}
