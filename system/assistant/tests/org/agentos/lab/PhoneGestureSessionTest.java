package org.agentos.lab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PhoneGestureSessionTest {
    static int checks;
    static void check(boolean value) { checks++;if(!value)throw new AssertionError(); }
    interface Work { void run() throws Exception; }
    static void rejects(Work work) throws Exception {try{work.run();}catch(Exception expected){checks++;return;}throw new AssertionError("accepted invalid gesture");}
    record Event(int display,long down,long at,String phase,int x,int y,float pressure) {}
    public static void main(String[] args) throws Exception {
        long[] now={100};List<Event> events=new ArrayList<>();
        PhoneGestureSession pointer=new PhoneGestureSession(()->now[0],(d,down,at,phase,x,y,pressure)->events.add(new Event(d,down,at,phase,x,y,pressure)));
        String a="thread-a\nturn-1",b="thread-b\nturn-2";
        pointer.startOwner(a);pointer.startOwner(b);
        rejects(()->pointer.touch(a,0,"DOWN",100,100));
        rejects(()->pointer.touch(a,2,"DOWN",720,100));
        rejects(()->pointer.touch(a,2,"DOWN",100,1280));
        rejects(()->pointer.touch(a,2,"DOWN",-1,100));
        rejects(()->pointer.touch("",2,"DOWN",100,100));
        rejects(()->pointer.touch(a,2,"CLICK",100,100));
        rejects(()->pointer.touch(a,2,"MOVE",100,100));
        check(events.isEmpty());
        pointer.touch(a,2,"DOWN",100,200);now[0]=200;
        check(pointer.state().expiresAt()==15100);check(events.get(0).pressure()==1f);
        rejects(()->pointer.touch(a,2,"DOWN",100,200));
        rejects(()->pointer.touch(b,2,"MOVE",200,200));
        rejects(()->pointer.touch(a,3,"UP",100,200));
        pointer.touch(a,2,"MOVE",250,200);
        check(events.get(1).down()==100);check(events.get(1).at()==200);check(events.get(1).display()==2);
        check(pointer.state().expiresAt()==15200);check(pointer.state().x()==250);
        pointer.cancelOwner("thread-a\nold-turn");check(pointer.state()!=null);
        pointer.cancelThread("thread-b");check(pointer.state()!=null);
        rejects(pointer::requireIdle);
        now[0]=300;pointer.touch(a,2,"UP",250,200);
        check(events.get(2).pressure()==0f);check(events.get(2).down()==100);check(pointer.state()==null);
        pointer.requireIdle();
        pointer.touch(a,2,"DOWN",10,20);now[0]=15300;pointer.expire();
        check(pointer.state()==null);check(events.get(events.size()-1).phase().equals("CANCEL"));
        rejects(()->pointer.touch(a,2,"UP",10,20));
        pointer.touch(b,2,"DOWN",30,40);pointer.cancelThread("thread-b");
        check(pointer.state()==null);check(events.get(events.size()-1).pressure()==0f);
        pointer.touch(a,2,"DOWN",10,20);now[0]+=15000;
        rejects(()->pointer.touch(a,2,"MOVE",20,20));check(pointer.state()==null);
        pointer.touch(a,2,"DOWN",10,20);pointer.cancelOwner(a);check(pointer.state()==null);
        boolean[] fail={false};
        PhoneGestureSession uncertain=new PhoneGestureSession(()->now[0],(d,down,at,phase,x,y,pressure)->{if(fail[0])throw new IOException("reject");});
        uncertain.startOwner(a);uncertain.startOwner(b);
        uncertain.touch(a,2,"DOWN",10,20);fail[0]=true;
        rejects(()->uncertain.touch(a,2,"MOVE",20,20));check(uncertain.state()!=null);
        rejects(()->uncertain.touch(b,2,"DOWN",30,20));
        fail[0]=false;uncertain.cancel();check(uncertain.state()==null);
        // A queued worker arriving after cancellation must not resurrect the pointer.
        pointer.endOwner(a);rejects(()->pointer.touch(a,2,"DOWN",10,20));
        check(pointer.state()==null);
        pointer.startOwner(a);rejects(()->pointer.touch(a,2,"DOWN",10,20));
        String c="thread-a\nturn-3";pointer.startOwner(c);pointer.touch(c,2,"DOWN",10,20);pointer.endOwner(c);
        rejects(()->pointer.touch(c,2,"MOVE",30,20));check(pointer.state()==null);
        String d="thread-a\nturn-4";pointer.endOwner(d);pointer.startOwner(d);
        rejects(()->pointer.touch(d,2,"DOWN",10,20));
        pointer.touch(b,2,"DOWN",10,20);pointer.close();
        rejects(()->pointer.touch(a,2,"DOWN",10,20));rejects(()->pointer.touch(b,2,"DOWN",10,20));
        System.out.println("PhoneGestureSessionTest: "+checks+" checks passed");
    }
}
