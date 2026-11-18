package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Host-only stream semantics. No Android, provider, credentials or running server. */
public final class UiSseStreamTest {
 static int checks;
 interface Run {void run()throws Exception;}
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 static void rejects(Run run)throws Exception{boolean failed=false;try{run.run();}catch(Exception expected){failed=true;}check(failed);}
 static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
 static String event(JSONObject packet){return "data: "+packet+"\n\n";}
 static JSONObject chunk(Object content,Object finish)throws Exception{
  JSONObject delta=new JSONObject();if(content!=null)delta.put("content",content);
  return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish)));
 }
 static String content(String text)throws Exception{return event(chunk(text,null));}
 static String stop()throws Exception{return event(chunk(null,"stop"));}
 static String done(){return "data: [DONE]\n\n";}
 static String complete(String text)throws Exception{return content(text)+stop()+done();}
 static JSONObject parse(String wire)throws Exception{return UiSseStream.read(new ByteArrayInputStream(bytes(wire)),new UiSseStream.Flight(),delta->{});}
 static void rejectWithoutCallback(String wire)throws Exception{
  AtomicInteger calls=new AtomicInteger();rejects(()->UiSseStream.read(new ByteArrayInputStream(bytes(wire)),new UiSseStream.Flight(),delta->calls.incrementAndGet()));check(calls.get()==0);
 }
 static final class Fragments extends InputStream {
  final byte[] data;final int fragment;int at;boolean eof;
  Fragments(String text,int fragment){data=bytes(text);this.fragment=fragment;}
  @Override public int read(){if(at==data.length){eof=true;return -1;}return data[at++]&255;}
  @Override public int read(byte[] out,int offset,int length){if(at==data.length){eof=true;return -1;}int count=Math.min(Math.min(length,fragment),data.length-at);System.arraycopy(data,at,out,offset,count);at+=count;return count;}
 }
 static final class StubConnection extends HttpURLConnection {
  int disconnects;final Runnable close;
  StubConnection(Runnable close)throws Exception{super(new URL("http://127.0.0.1/host-parser-only"));this.close=close;}
  @Override public void disconnect(){disconnects++;if(close!=null)close.run();}
  @Override public boolean usingProxy(){return false;}
  @Override public void connect(){}
 }
 static void fragmented()throws Exception{
  String unicode="你好🙂 café";
  String multiline="\ufeff: keepalive\r\nevent: message\r\nid: first\r\nretry: 1000\r\nunknown: ignored\r\ndata: {\"choices\": [\r\ndata: {\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""+unicode+"\"},\"finish_reason\":null}\r\ndata: ]}\r\n\r\n";
  JSONObject usage=new JSONObject().put("prompt_tokens",12).put("completion_tokens",5).put("total_tokens",17);
  String wire=multiline+content("\n第二行")+stop()+event(new JSONObject().put("choices",new JSONArray()).put("usage",usage))+done()+": closed\n\n";
  for(int fragment:new int[]{1,2,3,5,8,31,8192}){
   Fragments input=new Fragments(wire,fragment);List<String> deltas=new ArrayList<>();
   JSONObject result=UiSseStream.read(input,new UiSseStream.Flight(),delta->{check(!input.eof);deltas.add(delta);});
   check(deltas.equals(List.of(unicode,"\n第二行")));check(result.getString("text").equals(unicode+"\n第二行"));check(result.getJSONObject("usage").getInt("total_tokens")==17);
  }
  for(String newline:new String[]{"\n","\r\n","\r"})check(parse(complete("ok").replace("\n",newline)).getString("text").equals("ok"));
  JSONObject terminal=chunk("last","stop").put("usage",usage);check(parse(event(terminal)+done()).getJSONObject("usage").getInt("completion_tokens")==5);
  check(parse(complete("ok")).getJSONObject("usage").length()==0);
  check(parse(event(chunk(null,null))+complete("role only first")).getString("text").equals("role only first"));
 }
 static void earlyDelivery()throws Exception{
  byte[] first=bytes(content("first")),rest=bytes(content("second")+stop()+done());AtomicBoolean delivered=new AtomicBoolean();
  InputStream input=new InputStream(){int at;boolean tail;
   @Override public int read(){throw new AssertionError("unexpected scalar read");}
   @Override public int read(byte[] out,int offset,int length){
    if(!tail){int count=Math.min(length,first.length-at);System.arraycopy(first,at,out,offset,count);at+=count;if(at==first.length){tail=true;at=0;}return count;}
    if(!delivered.get())throw new AssertionError("reader waited for the next network chunk before delivery");
    if(at==rest.length)return -1;int count=Math.min(length,rest.length-at);System.arraycopy(rest,at,out,offset,count);at+=count;return count;
   }
  };
  JSONObject result=UiSseStream.read(input,new UiSseStream.Flight(),delta->{if(delta.equals("first"))delivered.set(true);});
  check(delivered.get());check(result.getString("text").equals("firstsecond"));
 }
 static void finalityAndMalformed()throws Exception{
  rejects(()->parse(content("partial")));rejects(()->parse(content("partial")+stop()));rejects(()->parse(content("partial")+done()));rejectWithoutCallback(done());
  rejects(()->parse(content("partial")+"data: {\"choices\":"));rejects(()->parse(content("partial")+stop()+"data: [DONE]\n"));
  rejects(()->parse(complete("ok")+content("late")));rejects(()->parse(content("ok")+stop()+stop()+done()));rejects(()->parse(complete("ok")+done()));
  rejects(()->parse(content("ok")+stop()+event(new JSONObject().put("choices",new JSONArray()))+done()));
  rejects(()->parse(content("ok")+event(new JSONObject().put("choices",new JSONArray()).put("usage",new JSONObject()))+stop()+done()));
  for(String reason:new String[]{"length","tool_calls","content_filter","cancelled"})rejectWithoutCallback(event(chunk("unsafe",reason))+done());
  for(Object tools:new Object[]{new JSONArray(),new JSONArray().put(new JSONObject()),"invalid"}){
   JSONObject packet=chunk("unsafe",null);packet.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").put("tool_calls",tools);rejectWithoutCallback(event(packet));
  }
  JSONObject function=chunk("unsafe",null);function.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").put("function_call",new JSONObject());rejectWithoutCallback(event(function));
  rejectWithoutCallback(event(new JSONObject().put("error",new JSONObject().put("code","failed"))));
  JSONObject buffered=chunk(null,"stop");buffered.getJSONArray("choices").getJSONObject(0).put("message",new JSONObject().put("content","buffered"));rejectWithoutCallback(event(buffered)+done());
  rejectWithoutCallback(new JSONObject().put("choices",new JSONArray()).toString());
  JSONObject wrongType=chunk(new JSONArray().put("not text"),null);rejectWithoutCallback(event(wrongType));
  JSONObject wrongIndex=chunk("unsafe",null);wrongIndex.getJSONArray("choices").getJSONObject(0).put("index",1);rejectWithoutCallback(event(wrongIndex));
  JSONObject multiple=chunk("unsafe",null);multiple.getJSONArray("choices").put(multiple.getJSONArray("choices").getJSONObject(0));rejectWithoutCallback(event(multiple));
  rejectWithoutCallback("data: {'choices': []}\n\n");rejectWithoutCallback("data: {\"choices\":[],}\n\n");rejectWithoutCallback("data: {\"choices\":[],\"choices\":[]}\n\n");
  rejectWithoutCallback("data: {\"cho\\u0069ces\":[],\"choices\":[]}\n\n");rejectWithoutCallback("data: {\"choices\":[]} trailing\n\n");rejectWithoutCallback("data: {\"choices\":[],\"x\":NaN}\n\n");
  rejectWithoutCallback("data: {\"choices\":[],\"x\":01}\n\n");rejectWithoutCallback("data: {\"choices\":[],\"x\":\"bad\\x00\"}\n\n");
  byte[] invalidUtf8=new byte[]{'d','a','t','a',':',' ',(byte)0xc3,0x28,'\n','\n'};rejects(()->UiSseStream.read(new ByteArrayInputStream(invalidUtf8),new UiSseStream.Flight(),delta->{}));
  byte[] truncatedUtf8=new byte[]{'d','a','t','a',':',' ',(byte)0xf0,(byte)0x9f,'\n','\n'};rejects(()->UiSseStream.read(new ByteArrayInputStream(truncatedUtf8),new UiSseStream.Flight(),delta->{}));
 }
 static void limitsAndCallbackFailure()throws Exception{
  check(parse(complete("x".repeat(UiSseStream.MAX_TEXT))).getString("text").length()==UiSseStream.MAX_TEXT);
  rejectWithoutCallback(complete("x".repeat(UiSseStream.MAX_TEXT+1)));
  rejects(()->parse(content("x".repeat(UiSseStream.MAX_TEXT))+content("overflow")+stop()+done()));
  rejects(()->parse(":"+"x".repeat(UiSseStream.MAX_LINE_BYTES)+"\n\n"));
  String part="data: "+" ".repeat(1024)+"\n";rejects(()->parse(part.repeat(UiSseStream.MAX_EVENT_CHARS/1024+1)+"\n"));
  rejects(()->parse((":"+"x".repeat(1000)+"\n").repeat(UiSseStream.MAX_STREAM_BYTES/1000+1)));
  String empty=event(chunk(null,null));rejects(()->parse(empty.repeat(UiSseStream.MAX_EVENTS+1)+complete("too late")));
  String deep="[".repeat(40)+"0"+"]".repeat(40);rejectWithoutCallback("data: {\"choices\":[],\"nested\":"+deep+"}\n\n");
  Exception failure=new Exception("callback sentinel");AtomicInteger calls=new AtomicInteger();
  try{UiSseStream.read(new ByteArrayInputStream(bytes(complete("first"))),new UiSseStream.Flight(),delta->{calls.incrementAndGet();throw failure;});throw new AssertionError("callback failure swallowed");}
  catch(Exception expected){check(expected==failure);}check(calls.get()==1);
 }
 static void cancellation()throws Exception{
  UiSseStream.Flight before=new UiSseStream.Flight();before.cancel();check(before.cancelled());
  InputStream unread=new InputStream(){@Override public int read(){throw new AssertionError("cancelled stream was read");}};
  rejects(()->UiSseStream.read(unread,before,delta->{throw new AssertionError("cancelled callback");}));
  UiSseStream.Flight during=new UiSseStream.Flight();StubConnection active=new StubConnection(null);during.attach(active);AtomicInteger calls=new AtomicInteger();
  rejects(()->UiSseStream.read(new ByteArrayInputStream(bytes(content("first")+content("second")+stop()+done())),during,delta->{calls.incrementAndGet();during.cancel();}));
  check(calls.get()==1);check(during.cancelled());check(active.disconnects==1);during.cancel();check(active.disconnects==1);rejects(()->during.attach(new StubConnection(null)));
  UiSseStream.Flight finished=new UiSseStream.Flight();StubConnection detached=new StubConnection(null);finished.attach(detached);finished.detach(detached);finished.cancel();check(detached.disconnects==0);
  UiSseStream.Flight one=new UiSseStream.Flight();StubConnection first=new StubConnection(null),second=new StubConnection(null);one.attach(first);rejects(()->one.attach(second));one.detach(second);one.cancel();check(first.disconnects==1);check(second.disconnects==0);
  CountDownLatch reading=new CountDownLatch(1),disconnected=new CountDownLatch(1),ended=new CountDownLatch(1);UiSseStream.Flight blocked=new UiSseStream.Flight();StubConnection connection=new StubConnection(disconnected::countDown);blocked.attach(connection);AtomicReference<Throwable> failure=new AtomicReference<>();
  InputStream waiting=new InputStream(){
   @Override public int read()throws IOException{throw new AssertionError("unexpected scalar read");}
   @Override public int read(byte[] out,int offset,int length)throws IOException{reading.countDown();try{if(!disconnected.await(2,TimeUnit.SECONDS))throw new IOException("disconnect did not wake reader");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}throw new IOException("disconnected");}
  };
  Thread worker=new Thread(()->{try{UiSseStream.read(waiting,blocked,delta->{throw new AssertionError("callback after blocked cancel");});}catch(Throwable e){failure.set(e);}finally{ended.countDown();}},"ui-sse-host-cancel");worker.setDaemon(true);worker.start();
  try{check(reading.await(2,TimeUnit.SECONDS));blocked.cancel();check(ended.await(2,TimeUnit.SECONDS));check(failure.get() instanceof IOException);check(connection.disconnects==1);}finally{blocked.cancel();worker.join(2000);}
 }
 static void cancelBlockedCallback()throws Exception{
  UiSseStream.Flight flight=new UiSseStream.Flight();StubConnection connection=new StubConnection(null);flight.attach(connection);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),ended=new CountDownLatch(1),cancelReturned=new CountDownLatch(1);
  AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
  byte[] wire=bytes(content("first")+content("second")+stop()+done());
  Thread worker=new Thread(()->{
   try{UiSseStream.read(new ByteArrayInputStream(wire),flight,delta->{calls.incrementAndGet();entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("callback release timed out");});}
   catch(Throwable e){failure.set(e);}finally{ended.countDown();}
  },"ui-sse-host-blocked-callback");
  Thread canceller=new Thread(()->{try{flight.cancel();}finally{cancelReturned.countDown();}},"ui-sse-host-cancel-callback");
  worker.setDaemon(true);canceller.setDaemon(true);worker.start();
  try{
   check(entered.await(2,TimeUnit.SECONDS));canceller.start();check(cancelReturned.await(2,TimeUnit.SECONDS));
   check(flight.cancelled());check(connection.disconnects==1);check(ended.getCount()==1);check(calls.get()==1);
  }finally{release.countDown();worker.join(2000);if(canceller.getState()!=Thread.State.NEW)canceller.join(2000);}
  check(ended.await(2,TimeUnit.SECONDS));check(failure.get() instanceof InterruptedIOException);check(calls.get()==1);
 }
 public static void main(String[] args)throws Exception{
  fragmented();earlyDelivery();finalityAndMalformed();limitsAndCallbackFailure();cancellation();cancelBlockedCallback();
  System.out.println("PASS UiSseStreamTest: "+checks+" checks");
 }
}
