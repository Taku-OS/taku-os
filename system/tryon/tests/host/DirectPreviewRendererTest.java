// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Production renderer admission/ownership, driven by deterministic fake worker/UI queues. */
public final class DirectPreviewRendererTest {
    private static int checks;
    public static void main(String[] args) {
        queuedDecodeAfterStop(); staleDecodeAfterStop(); pendingUiAfterStop(); oldRunCannotClearNewRun();
        rotationDropsOldPresentation(); slowUiIsBoundedAndLatestWins();
        rejectedUiAndDecoderFailureRelease(); validatedSizesAndByteOwnership();
        System.out.println("DirectPreviewRendererTest: " + checks + " checks passed; fake decoded objects, no Android pixels/window");
    }
    private static void queuedDecodeAfterStop() {
        Fixture f=new Fixture();f.begin(1);f.renderer.offer(jpeg(1),640,480,1);
        f.renderer.end(1);f.worker.runAll();f.ui.runAll();
        check(f.created.isEmpty()&&f.shows==0,"stop before queued worker starts avoids stale decode entirely");
        f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void staleDecodeAfterStop() {
        Fixture f=new Fixture();f.begin(1);
        f.afterDecode=()->f.renderer.end(1);
        check(f.renderer.offer(jpeg(1),640,480,1),"active run accepts encoded return for asynchronous decode");
        f.worker.runAll();f.ui.runAll();
        check(f.shows==0&&f.current==null,"stop while decoding prevents the late value from reaching UI");
        check(f.created.size()==1&&f.created.get(0).released==1,"late decoded value is released exactly once");
        check(!f.renderer.offer(jpeg(2),640,480,1),"end rejects later old-network outputs immediately");
        f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void pendingUiAfterStop() {
        Fixture f=new Fixture();f.begin(1);f.renderer.offer(jpeg(1),640,480,1);f.worker.runAll();
        check(f.created.size()==1&&f.shows==0,"decoded frame is actually waiting for a UI callback");
        f.renderer.end(1);f.ui.runAll();
        check(f.shows==0&&f.created.get(0).released==1,"already-posted UI callback cannot paint after stop");
        f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void oldRunCannotClearNewRun() {
        Fixture f=new Fixture();f.begin(1);f.renderer.offer(jpeg(1),640,480,1);f.worker.runAll();
        check(f.renderer.begin(2),"fresh run can supersede a queued old return");
        check(!f.renderer.begin(2)&&!f.renderer.begin(1)&&!f.renderer.begin(0),"duplicate/old/zero lease cannot reopen or clear active generation");
        f.renderer.end(1);f.renderer.offer(jpeg(2),480,640,2);
        f.ui.runAll();f.worker.runAll();f.ui.runAll();
        check(f.shows==1&&f.current!=null&&f.current.id==2,"new run remains visible despite old UI/end callbacks");
        check(f.created.get(0).released==1,"replaced queued frame is released without showing");
        int clears=f.clears;f.renderer.end(1);f.ui.runAll();
        check(f.clears==clears&&f.current!=null&&f.current.id==2,"late old end cannot clear a new displayed image");
        f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void rotationDropsOldPresentation() {
        Fixture f=new Fixture();f.begin(1);f.renderer.offer(jpeg(1),640,480,1);f.worker.runAll();
        f.renderer.invalidatePresentation();f.ui.runAll();
        check(f.shows==0&&f.created.get(0).released==1,"rotation invalidates a decoded old geometry before UI submission");
        check(f.renderer.offer(jpeg(2),480,640,1),"presentation rotation keeps the same bounded media run open for fresh geometry");
        f.worker.runAll();f.ui.runAll();
        check(f.current!=null&&f.current.width==480&&f.current.height==640,"fresh encoded dimensions are passed intact to the decoder");
        f.renderer.invalidatePresentation();f.ui.runAll();
        check(f.current==null&&f.created.get(1).released==1,"old displayed geometry is detached before being reclaimed");
        f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void slowUiIsBoundedAndLatestWins() {
        Fixture f=new Fixture();f.begin(1);
        f.renderer.offer(jpeg(1),640,480,1);f.worker.runAll();
        check(f.created.size()==1&&f.ui.size()==1,"one decoded object may wait for UI");
        for(int id=2;id<=100;id++)check(f.renderer.offer(jpeg(id),640,480,1),"newest JPEG can replace a pending return");
        check(f.worker.size()==0&&f.created.size()==1,"slow UI cannot queue decode jobs or accumulate decoded objects");
        f.ui.runAll();
        check(f.worker.size()==1,"finishing current UI callback releases exactly one next decode job");
        f.worker.runAll();f.ui.runAll();
        check(f.created.size()==2&&f.current!=null&&f.current.id==100,"coalescing decodes only the newest pending JPEG");
        check(f.created.get(0).released==1,"display replacement releases previous decoded ownership once");
        f.renderer.close();f.ui.runAll();f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void rejectedUiAndDecoderFailureRelease() {
        Fixture f=new Fixture();f.begin(1);f.ui.reject=true;
        f.renderer.offer(jpeg(1),640,480,1);f.worker.runAll();
        check(f.created.size()==1&&f.created.get(0).released==1&&f.shows==0,"rejected UI post releases decoded object without displaying it");
        f.ui.reject=false;f.failDecode=true;f.renderer.offer(jpeg(2),640,480,1);f.worker.runAll();f.ui.runAll();
        check(f.created.size()==1&&f.current==null,"decoder failure cannot fabricate a result object");
        f.failDecode=false;f.renderer.offer(jpeg(3),640,480,1);f.worker.runAll();f.ui.runAll();
        check(f.current!=null&&f.current.id==3,"decode/UI failure does not permanently hold the bounded actor busy");
        f.throwOnShow=true;f.renderer.offer(jpeg(4),640,480,1);f.worker.runAll();f.ui.runAll();
        check(f.current==null,"partially attached failed display is cleared before release");
        f.renderer.close();f.ui.runAll();f.checkAllReleased();
    }
    private static void validatedSizesAndByteOwnership() {
        Fixture f=new Fixture();f.begin(1);
        check(!f.renderer.offer(jpeg(1),0,640,1)&&!f.renderer.offer(jpeg(1),640,1921,1),"invalid returned dimensions cannot schedule work");
        check(!f.renderer.offer(new byte[]{1,2,3,4},640,480,1),"non-JPEG envelope cannot schedule decode");
        check(!f.renderer.offer(jpeg(1),640,480,2),"mismatched media lease is rejected");
        byte[] tooLarge=new byte[400*1024+1];tooLarge[0]=(byte)255;tooLarge[1]=(byte)216;tooLarge[tooLarge.length-2]=(byte)255;tooLarge[tooLarge.length-1]=(byte)217;
        check(!f.renderer.offer(tooLarge,640,480,1),"oversized JPEG cannot be retained");
        check(f.worker.size()==0,"all rejected inputs leave no worker jobs");
        byte[] source=jpeg(6);check(f.renderer.offer(source,1920,1080,1),"bounded original encoded dimensions are accepted");source[2]=99;
        f.worker.runAll();f.ui.runAll();
        check(f.current!=null&&f.current.id==6&&f.current.width==1920&&f.current.height==1080,"producer mutation and view sizing cannot alter owned bytes/encoded dimensions");
        f.renderer.close();f.ui.runAll();
        check(!f.renderer.begin(2)&&!f.renderer.offer(jpeg(7),640,480,1),"closed renderer cannot be revived by a new lease or late output");
        f.checkAllReleased();
    }
    private static final class Value {
        final int id,width,height;int released;
        Value(int id,int width,int height){this.id=id;this.width=width;this.height=height;}
    }
    private static final class Queue implements Executor,DirectPreviewRenderer.Dispatcher {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();boolean reject;boolean running;
        public void execute(Runnable task){if(reject)throw new RejectedExecutionException("synthetic stopped worker");tasks.add(task);}
        public boolean post(Runnable task){if(reject)return false;tasks.add(task);return true;}
        int size(){return tasks.size();}
        void runAll(){int count=0;while(!tasks.isEmpty()){if(++count>1000)throw new AssertionError("unbounded test queue");running=true;try{tasks.removeFirst().run();}finally{running=false;}}}
    }
    private static final class Fixture {
        final Queue worker=new Queue(),ui=new Queue();final List<Value> created=new ArrayList<>();
        final DirectPreviewRenderer<Value> renderer;
        Value current;int shows,clears;boolean failDecode,throwOnShow;Runnable afterDecode;
        Fixture(){renderer=new DirectPreviewRenderer<>((bytes,width,height)->{
            if(failDecode)throw new IllegalArgumentException("synthetic invalid decode");
            Value value=new Value(bytes[2]&255,width,height);created.add(value);if(afterDecode!=null)afterDecode.run();return value;
        },new DirectPreviewRenderer.Display<Value>(){
            public void show(Value value){check(ui.running,"show executes only on UI dispatcher");current=value;shows++;if(throwOnShow)throw new IllegalStateException("synthetic failed attach");}
            public void clear(){check(ui.running,"clear executes only on UI dispatcher");current=null;clears++;}
            public void release(Value value){value.released++;check(value.released==1,"decoded ownership is released exactly once");}
        },ui,worker);}
        void begin(long generation){check(renderer.begin(generation),"fixture begins explicit media lease");ui.runAll();}
        void checkAllReleased(){for(Value value:created)check(value.released==1,"all decoded objects are released after close");}
    }
    private static byte[] jpeg(int id){return new byte[]{(byte)255,(byte)216,(byte)id,(byte)255,(byte)217};}
    private static void check(boolean okay,String message){checks++;if(!okay)throw new AssertionError(message);}
}
