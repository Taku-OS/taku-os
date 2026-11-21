// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
/** Controllable worker/stop ordering. No Android, sleeping, camera or cloud. */
public final class AttemptOwnerTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        queuedStop();tokenStop();latePrepared();startStopBoundary();cameraAndCloudGate();lateCleanup();
        System.out.println("AttemptOwnerTest: "+checks+" checks passed; deterministic preparation/cancellation/cleanup ordering");
    }
    static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    static void queuedStop(){
        AttemptOwner<String> o=new AttemptOwner<>(v->{});AttemptOwner.Task t=o.queue();o.close();
        check(!o.closed());check(!o.begin(t));check(o.queue()==null);o.done(t);check(o.closed());o.done(t);check(o.closed());
    }
    static void tokenStop(){
        AttemptOwner<String> o=new AttemptOwner<>(v->{});AttemptOwner.Task t=o.queue();check(o.begin(t));
        AtomicInteger cancel=new AtomicInteger();check(o.cancellation(cancel::incrementAndGet));o.close();check(cancel.get()==1);check(!o.closed());
        check(!o.cancellation(cancel::incrementAndGet));check(cancel.get()==2);o.done(t);check(o.closed());
    }
    static void latePrepared(){
        AtomicInteger stopped=new AtomicInteger(),started=new AtomicInteger();AttemptOwner<String> o=new AttemptOwner<>(v->stopped.incrementAndGet());
        AttemptOwner.Task t=o.queue();check(o.begin(t));o.close();check(!o.start("late",started::incrementAndGet));
        check(started.get()==0&&stopped.get()==1);o.done(t);check(!o.closed());o.cloudClosed(true);check(o.closed());
    }
    static void startStopBoundary()throws Exception{
        AtomicInteger order=new AtomicInteger(),started=new AtomicInteger(),stopped=new AtomicInteger();
        AttemptOwner<String> o=new AttemptOwner<>(v->{check(order.get()==1);stopped.incrementAndGet();});
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread start=new Thread(()->o.start("rtc",()->{entered.countDown();await(release);started.incrementAndGet();order.set(1);}));
        start.start();entered.await();Thread stop=new Thread(o::close);stop.start();release.countDown();start.join();stop.join();
        check(started.get()==1&&stopped.get()==1);check(!o.closed());o.cloudClosed(true);check(o.closed());
    }
    static void cameraAndCloudGate(){
        AttemptOwner<String> o=new AttemptOwner<>(v->{});o.cameraOpening();AttemptOwner.Task t=o.queue();check(o.begin(t));check(o.start("rtc",()->{}));
        o.close();o.cloudClosed(true);o.done(t);check(!o.closed());o.cameraClosed();check(o.closed());o.close();check(o.closed());
    }
    static void lateCleanup(){
        AttemptOwner<String> o=new AttemptOwner<>(v->{});o.start("rtc",()->{});o.close();o.cloudClosed(false);check(!o.closed());o.cloudClosed(true);check(o.closed());
    }
    static void await(CountDownLatch l){try{l.await();}catch(InterruptedException e){throw new AssertionError(e);}}
}
