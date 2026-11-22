#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the production preview/publication guard with deterministic actors; no device/model."""
from pathlib import Path
import os,re,subprocess,tempfile

MODULE=Path(__file__).resolve().parents[1]
source=(MODULE/'src/org/agentos/shopping/ShoppingObserverService.java').read_text()
def method(name):
    match=re.search(r'^    private (?:static )?[\w.<>]+ '+name+r'\([^\n]*(?:\n[^\n]*)?\)\s*\{',source,re.M)
    if not match: raise AssertionError(name)
    at=source.index('{',match.start())+1;depth=1
    while depth: depth+=(source[at]=='{')-(source[at]=='}');at+=1
    return source[match.start():at]

# Passive guards precede mutation; explicit/capture paths retain fresh strong reads.
start=source.index('private void refresh(boolean force,boolean windowChanged)')
end=source.index('private boolean refreshCurrent',start)
passive=source[start:end]
assert passive.index('WindowSnapshot.readPassive(')<passive.index('pauseForPreview(result)')<passive.index('policy.observe(')
assert 'ensureHost();' in passive and passive.index('pauseForPreview(result)')<passive.index('ensureHost();')
begin=source[source.index('private boolean beginExplicit('):source.index('private void deliverLiveFrame(')]
assert 'WindowSnapshot.read(this,activityName)' in begin or 'WindowSnapshot.read(this, activityName)' in begin

fixture=r'''package org.agentos.shopping;
import java.util.*;
public final class ShoppingObserverService {
 static int checks;
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 final Policy policy=new Policy();Reminder reminders;Object confirmation;ObservationPolicy.Candidate offer;
 long lifecycle=17,lastRead,classificationToastGeneration;int reads,withdrawals,cacheClears;
 String activityName="LivePlayActivity",phase="suggesting",displayStatus="",lastRefreshReason="",lastOfferInvalidation="",eventId="initial",classificationStatus="";
 boolean destroyed;final Main main=new Main();
 long now(){return 9000;}boolean unlocked(){return true;}void clearCache(){cacheClears++;}
 void cancelPendingAcceptance(){withdrawals++;}
 boolean maintainPendingAcceptance(ObservationPolicy.Candidate c,WindowSnapshot.ReadResult r){return false;}
 boolean retainOccludedOffer(ObservationPolicy.Candidate c,WindowSnapshot.ReadResult r){return false;}
 boolean classificationAlive(ObservationPolicy.Candidate c){return !destroyed&&policy.isCurrent(c,now());}
 boolean sameClassificationPage(ObservationPolicy.Candidate c){return policy.currentPage()==c.page();}
'''+ '\n'.join(method(n) for n in ('pauseForPreview','refreshCurrent','refreshDisplayedOffer','publish','withdraw','acceptanceReadReason','completeClassification','showClassificationResult'))+r'''
 static ShoppingObserverService fresh(){
  ShoppingObserverService s=new ShoppingObserverService();s.offer=s.policy.candidate;s.reminders=new Reminder();
  WindowSnapshot.result=new WindowSnapshot.ReadResult(s.policy.page,"live_room");Toast.count=0;return s;
 }
 public static void main(String[] args){
  for(String mode:new String[]{"compact","preview","fullscreen"}) {
   ShoppingObserverService s=fresh();var original=s.policy.page;long generation=s.policy.generation;Reminder binding=s.reminders;
   WindowSnapshot.result=new WindowSnapshot.ReadResult(null,"try_on_preview");
   check(!s.refreshCurrent(s.offer),"preview cannot authorize a fresh publication: "+mode);
   check(s.policy.observes==0&&s.policy.page==original&&s.policy.generation==generation,"preview preserves original page and generation");
   check(s.offer==null&&s.reminders==null&&binding.closes==1&&s.eventId.isEmpty(),"current offer and binding are actually withdrawn");
   check(s.phase.equals("preview_active")&&s.lastRefreshReason.equals("try_on_preview"),"fixed phase and reason expose actual guard");
   check(s.refreshCurrent(s.policy.candidate)==false&&binding.closes==1,"repeated preview read does not reconnect reminders or double-close");
   check(s.main.posts==0&&binding.emits==0&&s.policy.polls==0,"pause schedules no cooldown, reminder or model poll");
   WindowSnapshot.result=new WindowSnapshot.ReadResult(original,"live_room");
   check(s.refreshCurrent(s.policy.candidate)&&s.policy.observes==1,"preview disappearance resumes ordinary validation immediately");
   check(s.policy.page==original&&s.policy.generation==generation,"same visible room remains the same visit");
  }
  ShoppingObserverService s=fresh();Reminder binding=s.reminders;var candidate=s.offer;
  WindowSnapshot.result=new WindowSnapshot.ReadResult(null,"try_on_preview");s.publish(candidate);
  check(binding.emits==0&&s.main.posts==0&&s.policy.observes==0,"an already queued publisher cannot recreate the covering reminder");
  s=fresh();candidate=s.offer;binding=s.reminders;WindowSnapshot.result=new WindowSnapshot.ReadResult(null,"try_on_preview");
  check(!s.refreshDisplayedOffer(candidate),"late publication ACK cannot promote an occluded visit");
  check(binding.closes==1&&binding.emits==0&&s.policy.observes==0,"ACK follows the same actual withdrawal guard");
  s=fresh();candidate=s.offer;binding=s.reminders;WindowSnapshot.result=new WindowSnapshot.ReadResult(null,"try_on_preview");
  s.completeClassification(candidate,ProductTypeAnswer.Result.APPAREL,true);
  check(s.policy.resolved==1&&s.phase.equals("preview_active"),"in-flight classifier settles its lease without replacing preview phase");
  check(binding.emits==0&&s.main.posts==0&&Toast.count==0,"classifier completion cannot emit or toast above live preview");
  s=fresh();binding=s.reminders;candidate=s.offer;WindowSnapshot.result=new WindowSnapshot.ReadResult(s.policy.page,"live_room");s.publish(candidate);
  check(binding.emits==1&&s.offer==candidate&&s.main.posts==1,"ordinary room still publishes normally without global timing suppression");
  s=fresh();WindowSnapshot.result=new WindowSnapshot.ReadResult(null,"not_live_room");
  check(s.pauseForPreview(WindowSnapshot.result)==false&&s.reminders!=null,"ordinary absence does not pretend a preview is active");
  System.out.println("Preview publication guard actual-method checks: "+checks);
 }
}
class WindowSnapshot {
 record ReadResult(LivePage page,String reason){}
 static ReadResult result;
 static ReadResult readPassive(Object s,String a,LivePage p){return result;}
}
class LivePage {}
class ObservationPolicy {record Candidate(long generation,LivePage page,long createdAt){} }
class Policy {
 final LivePage page=new LivePage();final ObservationPolicy.Candidate candidate=new ObservationPolicy.Candidate(3,page,1000);
 long generation=3;int observes,polls,resolved;ProductTypeAnswer.Result settled;
 boolean observe(LivePage page,long now){observes++;return false;}
 boolean isCurrent(ObservationPolicy.Candidate c,long now){return c==candidate;}
 LivePage currentPage(){return page;}Config config(){return new Config();}
 void dismiss(ObservationPolicy.Candidate c,long now){}
 ObservationPolicy.Candidate resolveClassification(ObservationPolicy.Candidate c,ProductTypeAnswer.Result r,long now){resolved++;settled=r;return candidate;}
 ProductTypeAnswer.Result classificationResult(){return settled;}
}
class Config {long offerTtlMs(){return 60000;} }
class Reminder {boolean allowed=true;int emits,closes;void emit(String id,int revision){emits++;}void close(){closes++;} }
class Main {int posts;void postDelayed(Runnable r,long ms){posts++;} }
class Preferences {static boolean enabled(Object s){return true;}static boolean classification(Object s){return true;} }
class ProductTypeAnswer {enum Result {APPAREL,NON_APPAREL,UNKNOWN,UNAVAILABLE,TIMED_OUT} }
class ProductTypeStore {static void save(Object s,LivePage p,ProductTypeAnswer.Result r){} }
class Toast {static final int LENGTH_LONG=1;static int count;static Toast makeText(Object s,String d,int duration){count++;return new Toast();}void show(){} }
'''
fixture=fixture.replace('Object confirmation;','Reminder confirmation;')
home=os.environ.get('JAVA_HOME');javac=str(Path(home)/'bin/javac') if home else 'javac';java=str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='observer-preview-guard-') as temp:
    f=Path(temp)/'ShoppingObserverService.java';f.write_text(fixture)
    subprocess.run([javac,'--release','17','-d',temp,str(f)],check=True)
    subprocess.run([java,'-cp',temp,'org.agentos.shopping.ShoppingObserverService'],check=True,timeout=10)
