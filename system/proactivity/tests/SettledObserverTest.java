package org.agentos.proactivity;

import org.agentos.proactivity.api.ObserverSpec;

public final class SettledObserverTest {
    private static int assertions;
    private static void check(boolean condition){assertions++;if(!condition)throw new AssertionError("assertion "+assertions);}
    private static SettledObserver engine(){return new SettledObserver(new ObserverSpec("example.app.rule","Rule","example.app.changed",700,1500,3,10000,"Title","Summary"));}
    private static SettledObserver.Event event(int n,long at){return new SettledObserver.Event("event-"+n,"example.app.changed",n,at,n);}
    public static void main(String[] args) {
        SettledObserver observer=engine();check(observer.event(event(1,1000)));check(observer.poll(1699)==null);
        check(observer.event(event(2,1600)));check(observer.poll(1700)==null);check(!observer.event(event(2,1600)));
        var candidate=observer.poll(2300);check(candidate!=null && candidate.records().size()==2);check(observer.poll(2400)==null);
        check(observer.current(candidate));observer.event(event(3,2500));check(!observer.current(candidate));
        observer.offered(2400);check(observer.poll(3200)==null);
        observer.event(event(4,4000));check(observer.poll(4700)!=null);
        observer=engine();for(int i=1;i<=100;i++)observer.event(event(i,1000+i));
        candidate=observer.poll(2000);check(candidate.records().size()==3);check(candidate.records().get(0).sequence()==98);
        observer.invalidate();check(!observer.current(candidate));check(observer.poll(3000)==null);
        observer=engine();observer.event(event(1,0));check(observer.poll(200000)==null);
        observer=engine();observer.restoreCooldown(5000,0);observer.event(event(1,5100));check(observer.poll(5800)==null);
        observer=engine();observer.event(event(1,1000));
        try{observer.event(event(0,1200));throw new AssertionError("Out-of-order event accepted");}catch(IllegalArgumentException expected){assertions++;}
        try{observer.event(new SettledObserver.Event("wrong","other.type",2,1500,2));throw new AssertionError("Wrong type accepted");}catch(IllegalArgumentException expected){assertions++;}
        try{new ObserverSpec("bad/id","Rule","example.app.changed",700,1500,3,10000,"Title","Summary");throw new AssertionError("Bad ID accepted");}catch(IllegalArgumentException expected){assertions++;}
        System.out.println(assertions+" proactivity scheduling assertions passed");
    }
}
