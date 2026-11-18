package org.agentos.lab;

import java.util.*;

/** Bounded, memory-only exact-frame reuse. No screenshots, credentials or Android state. */
final class ImeReplyReuse {
 static final long TTL_MS=20000;
 private record Entry(String value,long at){}
 static final class Flight {
  final String cacheKey,key;final Set<String> subscribers=new LinkedHashSet<>();
  volatile boolean canceled;boolean cacheable=true;volatile Runnable cancelHook;
  Flight(String cacheKey,String key){this.cacheKey=cacheKey;this.key=key;}
  void hook(Runnable action){cancelHook=action;if(canceled)action.run();}
  void abort(){canceled=true;Runnable action=cancelHook;if(action!=null)action.run();}
 }
 record Lease(String cached,long ageMs,Flight flight,boolean leader){}
 private final LinkedHashMap<String,Entry> cache=new LinkedHashMap<>(8,.75f,true);
 private final Map<String,Flight> flights=new HashMap<>(),owners=new HashMap<>();
 synchronized Lease acquire(String key,String refresh,String id,long now){
  prune(now);if(owners.containsKey(id))throw new IllegalStateException("Duplicate request");
  Entry hit=refresh.isEmpty()?cache.get(key):null;
  if(hit!=null)return new Lease(hit.value,now-hit.at,null,false);
  String flightKey=key+"\n"+refresh;Flight flight=flights.get(flightKey);boolean leader=flight==null;
  if(leader){
   // A slower older group may still have subscribers, but must never replace
   // the cache belonging to a more recently requested refresh.
   for(Flight older:flights.values())if(older.cacheKey.equals(key))older.cacheable=false;
   flight=new Flight(key,flightKey);flights.put(flightKey,flight);
  }
  flight.subscribers.add(id);owners.put(id,flight);return new Lease(null,0,flight,leader);
 }
 synchronized Flight cancel(String id){
  Flight f=owners.remove(id);if(f==null)return null;f.subscribers.remove(id);
  if(!f.subscribers.isEmpty())return null;
  f.canceled=true;flights.remove(f.key,f);return f;
 }
 synchronized List<String> finish(Flight f,String result,long now){
  if(f.canceled||!flights.remove(f.key,f))return List.of();
  List<String> ids=new ArrayList<>(f.subscribers);for(String id:ids)owners.remove(id,f);
  if(result!=null&&!ids.isEmpty()&&f.cacheable){
   prune(now);cache.put(f.cacheKey,new Entry(result,now));
   while(cache.size()>4)cache.remove(cache.keySet().iterator().next());
  }return ids;
 }
 synchronized void prune(long now){cache.values().removeIf(e->now-e.at>=TTL_MS||now<e.at);}
 synchronized int cachedEntries(){return cache.size();}
 synchronized int pendingFlights(){return flights.size();}
}
