package org.agentos.lab;

/** Production coordinator tests use synthetic strings; never count these as model generation. */
public final class ImeReplyReuseTest {
 static int checks;
 static void check(boolean value){if(!value)throw new AssertionError("check "+(checks+1));checks++;}
 public static void main(String[] args)throws Exception{
  ImeReplyReuse c=new ImeReplyReuse();
  var a=c.acquire("page:model:revision:1","","a",0);check(a.leader());
  var b=c.acquire("page:model:revision:1","","b",1);check(!b.leader()&&a.flight()==b.flight());check(c.pendingFlights()==1);
  check(c.cancel("a")==null);check(!a.flight().canceled);
  check(c.finish(a.flight(),"first",10).equals(java.util.List.of("b")));
  var hit=c.acquire("page:model:revision:1","","c",20);check(hit.cached().equals("first")&&hit.ageMs()==10);
  check(c.acquire("different-page:model:revision:1","","d",20).leader());
  check(c.acquire("page:other-model:revision:1","","e",20).leader());
  check(c.acquire("page:model:revision:2","","f",20).leader());
  var refresh=c.acquire("page:model:revision:1","fresh:previous","g",20);check(refresh.leader()&&refresh.cached()==null);
  var joined=c.acquire("page:model:revision:1","fresh:previous","h",21);check(!joined.leader());
  check(c.finish(refresh.flight(),"second",22).size()==2);
  check(c.acquire("page:model:revision:1","","i",23).cached().equals("second"));
  check(c.finish(a.flight(),"late stale result",24).isEmpty());
  check(c.acquire("page:model:revision:1","","j",25).cached().equals("second"));
  var cancel=c.acquire("canceled","","k",30);final boolean[] aborted={false};
  cancel.flight().hook(()->aborted[0]=true);var detached=c.cancel("k");check(detached==cancel.flight());detached.abort();
  check(aborted[0]);check(c.finish(cancel.flight(),"must not cache",31).isEmpty());
  var again=c.acquire("canceled","","l",32);check(again.leader());
  check(c.finish(cancel.flight(),"late canceled output",32).isEmpty());check(c.pendingFlights()>0);
  c.finish(again.flight(),null,33);check(c.acquire("canceled","","m",34).leader());
  var beforeHook=c.acquire("before-hook","","n",40);c.cancel("n");
  final boolean[] lateHook={false};beforeHook.flight().hook(()->lateHook[0]=true);check(lateHook[0]);
  c.prune(20022);check(c.cachedEntries()==0);check(c.acquire("page:model:revision:1","","o",20022).leader());
  c=new ImeReplyReuse();
  for(int i=0;i<5;i++){var lease=c.acquire("key"+i,"","r"+i,i);c.finish(lease.flight(),"value"+i,i);}
  check(c.cachedEntries()==4);check(c.acquire("key0","","old",10).leader());
  check(c.acquire("key4","","new",10).cached().equals("value4"));
  c.prune(20004);check(c.cachedEntries()==0);
  var ordered=new ImeReplyReuse();
  var older=ordered.acquire("one-page","","older",0);
  var newer=ordered.acquire("one-page","refresh","newer",1);
  check(ordered.finish(newer.flight(),"new group",2).equals(java.util.List.of("newer")));
  check(ordered.finish(older.flight(),"slow old group",3).equals(java.util.List.of("older")));
  check(ordered.acquire("one-page","","read",4).cached().equals("new group"));
  final ImeReplyReuse concurrent=new ImeReplyReuse();
  var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   var one=pool.submit(()->{start.await();return concurrent.acquire("same-frame","","one",0);});
   var two=pool.submit(()->{start.await();return concurrent.acquire("same-frame","","two",0);});
   start.countDown();var first=one.get();var second=two.get();
   check(first.leader()!=second.leader());check(first.flight()==second.flight());
   check(concurrent.finish(first.flight(),"shared",1).size()==2);check(concurrent.pendingFlights()==0);
  }finally{pool.shutdownNow();}
  System.out.println("PASS ImeReplyReuseTest: "+checks+" checks");
 }
}
