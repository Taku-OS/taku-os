package org.agentos.lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Exercises the production scheduler with a frame source that can become static. */
public final class FrameCoalescerTest {
    private static int assertions;
    private static void check(boolean condition,String message) {
        assertions++;
        if(!condition)throw new AssertionError(message);
    }
    private static final class Harness implements FrameCoalescer.Scheduler {
        record Work(long due,long order,Runnable task) {}
        final PriorityQueue<Work> queue=new PriorityQueue<>(Comparator.comparingLong(Work::due).thenComparingLong(Work::order));
        final List<Integer> published=new ArrayList<>();
        final List<Long> consumedAt=new ArrayList<>();
        long now=1000,order;
        int latest;
        final FrameCoalescer frames=new FrameCoalescer(()->now,this,200,()->{
            consumedAt.add(now);
            if(latest!=0){published.add(latest);latest=0;}
        });
        public void postDelayed(Runnable task,long delayMs){queue.add(new Work(now+delayMs,order++,task));}
        public void remove(Runnable task){queue.removeIf(work->work.task()==task);}
        void offer(int frame){latest=frame;frames.onFrameAvailable();}
        void advance(long target){
            while(!queue.isEmpty()&&queue.peek().due()<=target){Work work=queue.remove();now=work.due();work.task().run();}
            now=target;
        }
    }

    public static void main(String[] args) {
        Harness h=new Harness();
        h.offer(1);
        check(h.published.isEmpty(),"Notification must not acquire/consume a frame");
        h.advance(1000);
        check(h.published.equals(List.of(1)),"First frame must publish immediately");
        h.advance(1040);h.offer(2);
        h.advance(1090);h.offer(3);
        check(h.queue.size()==1,"Burst must schedule only one reader");
        check(h.frames.hasPendingFrame(),"Capture must know a newer frame is pending");
        h.advance(1199);
        check(h.published.equals(List.of(1)),"Burst must not exceed the frame rate");
        h.advance(1200);
        check(h.published.equals(List.of(1,3)),"Final burst frame must publish without another notification");
        check(!h.frames.hasPendingFrame(),"Final frame must clear pending state");
        h.advance(10000);
        check(h.published.equals(List.of(1,3)),"Static screen must retain its final frame with no polling encodes");

        Harness stream=new Harness();
        stream.offer(1);stream.advance(1000);
        for(int i=2;i<=26;i++){stream.advance(1000+(i-1)*20);stream.offer(i);}
        stream.advance(1600);
        check(stream.published.get(stream.published.size()-1)==26,"Continuous burst tail must also publish");
        check(stream.consumedAt.size()==4,"500 ms burst must require only four consumptions including first and tail");
        for(int i=1;i<stream.consumedAt.size();i++)check(stream.consumedAt.get(i)-stream.consumedAt.get(i-1)>=200,"Frame consumption must remain at most 5 fps");

        Harness closing=new Harness();
        closing.offer(1);closing.advance(1000);closing.advance(1010);closing.offer(2);
        Runnable late=closing.queue.peek().task();
        closing.frames.close();
        check(closing.queue.isEmpty(),"Close must remove delayed reader callback");
        check(!closing.frames.hasPendingFrame(),"Close must clear pending state");
        late.run();closing.offer(3);closing.advance(2000);
        check(closing.published.equals(List.of(1)),"Closed reader must ignore both late callback and later notifications");
        System.out.println("FrameCoalescerTest: "+assertions+" assertions passed");
    }
}
