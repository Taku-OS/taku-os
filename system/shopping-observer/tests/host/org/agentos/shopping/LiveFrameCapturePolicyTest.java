// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class LiveFrameCapturePolicyTest {
    private static int assertions;
    private static final LivePage ROOM=LivePage.of("com.zhiliaoapp.musically",199,"presenter","");
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static LiveFrameCapturePolicy job(){return new LiveFrameCapturePolicy(ROOM,7,1000);}
    private static LiveFrameTarget target(){return LiveFrameTarget.parse(LiveFrameTargetTest.GOOD);}
    private static void rejected(Runnable action){assertions++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid action accepted");}
    public static void main(String[] args){
        check(LiveFrameCapturePolicy.MAX_SAMPLES==3&&LiveFrameCapturePolicy.DEADLINE_MS==20000,
            "explicit selection has bounded sampling and a fixed preparation deadline");
        LiveFrameCapturePolicy p=job();
        check(!p.source().hasProduct(),"a real no-card LIVE can be accepted");
        check(p.pageRevision()==7&&p.deadlineAt()==21000,"explicit attempt binding has a bounded 20s deadline");
        check(p.observe(LivePage.of(ROOM.packageName(),199,"presenter","new dress title"),1100),"product card appearing or changing does not change presenter identity");
        check(p.claimScreenshot(1200)&&!p.claimScreenshot(1200),"exactly one fresh screenshot can be claimed");
        check(!p.claimAnalysis(1201),"analysis cannot precede the actual screenshot callback");
        check(p.screenshotReady(1300)&&!p.screenshotReady(1300),"one screenshot callback advances the transaction");
        check(p.claimAnalysis(1400)&&!p.claimAnalysis(1400),"at most one automatic model call, no automatic retry");
        check(!p.complete(1401),"no handoff before garment quality/bounds validation");
        check(!p.analysisReady(null,1500),"missing or ambiguous result does not advance to crop");
        check(p.analysisReady(target(),1600)&&!p.analysisReady(target(),1600),"one valid target permits original-frame crop only once");
        check(p.complete(1700)&&!p.complete(1700)&&!p.isActive(1700),"one successful handoff is terminal");
        for(LivePage changed:new LivePage[]{null,LivePage.of(ROOM.packageName(),200,"presenter",""),LivePage.of(ROOM.packageName(),199,"another presenter",""),LivePage.of("com.ss.android.ugc.trill",199,"presenter","")}) {
            LiveFrameCapturePolicy switched=job();check(!switched.observe(changed,1100),"room/package/window departure cancels");
            check(!switched.observe(ROOM,1200)&&!switched.claimScreenshot(1200),"returning later cannot revive a cancelled ticket");
        }
        for(int stage=0;stage<4;stage++) {
            LiveFrameCapturePolicy cancelled=job();cancelled.claimScreenshot(1100);
            if(stage>0)cancelled.screenshotReady(1200);
            if(stage>1)cancelled.claimAnalysis(1300);
            if(stage>2)cancelled.analysisReady(target(),1400);
            cancelled.cancel();
            check(!cancelled.isActive(1500)&&!cancelled.screenshotReady(1500)&&!cancelled.claimAnalysis(1500)
                &&!cancelled.analysisReady(target(),1500)&&!cancelled.complete(1500),"cancel at every asynchronous stage rejects late callbacks");
        }
        LiveFrameCapturePolicy late=job();late.claimScreenshot(1100);late.screenshotReady(1200);late.claimAnalysis(1300);
        check(late.isActive(20999)&&!late.analysisReady(target(),21000)&&!late.complete(21001),"model latency cannot extend the total deadline");
        LiveFrameCapturePolicy clock=job();check(!clock.isActive(999)&&!clock.isActive(1000),"clock rollback before explicit start fails permanently");
        rejected(()->new LiveFrameCapturePolicy(null,7,1000));
        rejected(()->new LiveFrameCapturePolicy(ROOM,-1,1000));
        rejected(()->new LiveFrameCapturePolicy(ROOM,7,Long.MAX_VALUE-1));
        rejected(()->new LiveFrameCapturePolicy(ROOM,7,-1));
        boundedSampling();
        failedAdditionalSampling();
        selectedAnalysis();
        retainedSelection();
        terminalSelection();
        selectionDeadlines();
        System.out.println(assertions+" live frame transaction assertions passed");
    }

    private static void boundedSampling(){
        for(int count=1;count<=LiveFrameCapturePolicy.MAX_SAMPLES;count++){
            LiveFrameCapturePolicy p=job();
            check(p.samplesCaptured()==0&&!p.claimAdditionalScreenshot(1100),"sampling cannot skip the first screenshot claim");
            check(p.claimScreenshot(1100)&&p.samplesCaptured()==0,"claiming work does not report a captured image");
            for(int sample=1;sample<=count;sample++){
                check(!p.claimAdditionalScreenshot(1200)&&!p.claimAnalysis(1200),"an outstanding screenshot must finish before sampling or analysis");
                check(p.screenshotReady(1300)&&p.samplesCaptured()==sample&&!p.screenshotReady(1300),"each pending screenshot callback advances and counts only once");
                check(!p.claimScreenshot(1350),"the original API cannot claim a second initial frame");
                if(sample<count)check(p.claimAdditionalScreenshot(1400),"additional frames may be sampled before automatic analysis");
            }
            if(count==LiveFrameCapturePolicy.MAX_SAMPLES)
                check(!p.claimAdditionalScreenshot(1450)&&p.samplesCaptured()==3,"a fourth screenshot cannot be claimed");
            check(p.claimAnalysis(1500)&&!p.claimAnalysis(1500),"one automatic analysis may use any bounded sample count");
            check(!p.claimAdditionalScreenshot(1600)&&p.samplesCaptured()==count,"starting automatic analysis freezes the sampling budget");
            check(p.deadlineAt()==21000,"additional samples never renew the preparation deadline");
        }
    }

    private static LiveFrameCapturePolicy selecting(){
        LiveFrameCapturePolicy p=job();
        if(!p.claimScreenshot(1100)||!p.screenshotReady(1200)||!p.claimAnalysis(1300)||!p.waitForSelection(1400))
            throw new AssertionError("selection fixture could not reach the waiting state");
        return p;
    }

    private static void failedAdditionalSampling(){
        LiveFrameCapturePolicy first=job();
        check(!first.skipAdditionalScreenshot(1100),"a missing first capture cannot be skipped before work is claimed");
        first.claimScreenshot(1100);
        check(!first.skipAdditionalScreenshot(1200)&&first.samplesCaptured()==0&&!first.claimAnalysis(1200),"a first-frame failure cannot manufacture an analyzable image");
        for(int count=1;count<LiveFrameCapturePolicy.MAX_SAMPLES;count++){
            LiveFrameCapturePolicy p=job();p.claimScreenshot(1100);p.screenshotReady(1200);
            if(count==2){p.claimAdditionalScreenshot(1250);p.screenshotReady(1300);}
            check(!p.skipAdditionalScreenshot(1350),"only a pending additional capture may be skipped");
            check(p.claimAdditionalScreenshot(1400)&&p.skipAdditionalScreenshot(1500),"a failed optional capture can fall back to an earlier successful sample");
            check(p.samplesCaptured()==count&&p.stage()==LiveFrameCapturePolicy.Stage.ANALYZING
                    &&!p.screenshotReady(1600)&&!p.skipAdditionalScreenshot(1600),"fallback neither adds a success nor accepts a late screenshot callback");
            check(!p.claimAdditionalScreenshot(1650)&&p.claimAnalysis(1700)&&!p.skipAdditionalScreenshot(1800),"failed optional sampling stops further captures and proceeds with the sole automatic analysis");
            check(p.deadlineAt()==21000&&p.analysisReady(target(),1900)&&p.complete(2000),"fallback preserves the deadline and normal one-shot handoff");
        }
        LiveFrameCapturePolicy expired=job();expired.claimScreenshot(1100);expired.screenshotReady(1200);expired.claimAdditionalScreenshot(1300);
        check(!expired.skipAdditionalScreenshot(21000)&&!expired.skipAdditionalScreenshot(1400),"optional capture failure cannot skip the original deadline or revive expiry");
        LiveFrameCapturePolicy cancelled=job();cancelled.claimScreenshot(1100);cancelled.screenshotReady(1200);cancelled.claimAdditionalScreenshot(1300);cancelled.cancel();
        check(!cancelled.skipAdditionalScreenshot(1400),"optional capture fallback is unavailable after cancellation");
        LiveFrameCapturePolicy selected=selecting();selected.claimSelectedAnalysis(500,500,1500);
        check(!selected.skipAdditionalScreenshot(1600),"selected analysis cannot reopen automatic sampling through fallback");
    }

    private static void selectedAnalysis(){
        LiveFrameCapturePolicy p=job();
        check(!p.waitForSelection(1100)&&!p.claimSelectedAnalysis(500,500,1100),"a tap cannot skip source capture or automatic analysis");
        p.claimScreenshot(1100);
        check(!p.waitForSelection(1150),"selection is unavailable while capture is pending");
        p.screenshotReady(1200);
        check(!p.waitForSelection(1250),"selection cannot precede the one claimed automatic analysis");
        p.claimAnalysis(1300);
        check(p.waitForSelection(1400)&&!p.waitForSelection(1400),"automatic recognition may yield to one selection state");
        check(p.stage()==LiveFrameCapturePolicy.Stage.WAITING_SELECTION&&!p.selectionAnalysisClaimed(),"waiting alone does not authorize a selected model call");
        check(!p.analysisReady(target(),1450)&&!p.claimAnalysis(1450)&&!p.claimAdditionalScreenshot(1450),"waiting rejects automatic results, automatic retries and resampling");
        for(int[] point:new int[][]{{-1,0},{1001,0},{0,-1},{0,1001},{Integer.MIN_VALUE,500},{500,Integer.MAX_VALUE}})
            check(!p.claimSelectedAnalysis(point[0],point[1],1500)&&!p.selectionAnalysisClaimed(),"invalid coordinates do not consume the one valid selection");
        check(p.claimSelectedAnalysis(0,1000,1600)&&p.selectionAnalysisClaimed(),"normalized coordinate endpoints are valid for the one selected analysis");
        check(p.stage()==LiveFrameCapturePolicy.Stage.SELECTED_ANALYZING&&!p.claimSelectedAnalysis(500,500,1600)
                &&!p.waitForSelection(1600)&&!p.claimRetainedSelection(500,500,1600),"a selected call cannot be claimed twice or restarted through another entry point");
        check(!p.analysisReady(null,1650)&&!p.complete(1650),"selection never bypasses valid garment analysis and crop preparation");
        check(p.analysisReady(target(),1700)&&!p.analysisReady(target(),1700),"a selected result allows one crop only");
        check(p.complete(1800)&&!p.complete(1800)&&!p.claimSelectedAnalysis(500,500,1800),"selected handoff is terminal and one-shot");
        check(p.samplesCaptured()==1&&p.deadlineAt()==21000,"selection neither fabricates another source frame nor renews its deadline");

        LiveFrameCapturePolicy concurrent=selecting();
        boolean[] won=new boolean[2];
        Thread first=new Thread(()->won[0]=concurrent.claimSelectedAnalysis(1000,0,1500));
        Thread second=new Thread(()->won[1]=concurrent.claimSelectedAnalysis(500,500,1500));
        first.start();second.start();
        try{first.join();second.join();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        check(won[0]!=won[1]&&concurrent.selectionAnalysisClaimed(),"concurrent pointer callbacks can claim only one additional analysis");
    }

    private static void retainedSelection(){
        LiveFrameCapturePolicy p=job();
        check(!p.claimRetainedSelection(-1,500,1100)&&!p.claimRetainedSelection(500,1001,1100)
                &&p.stage()==LiveFrameCapturePolicy.Stage.WAITING_CAPTURE&&!p.selectionAnalysisClaimed(),"bad retained coordinates leave a fresh instance unused");
        check(p.claimRetainedSelection(1000,0,1200)&&p.selectionAnalysisClaimed(),"a new explicit attempt can claim one caller-verified frozen selection");
        check(p.stage()==LiveFrameCapturePolicy.Stage.SELECTED_ANALYZING&&p.samplesCaptured()==0,"retained selection does not report or simulate a screenshot");
        check(!p.claimScreenshot(1300)&&!p.claimAdditionalScreenshot(1300)&&!p.screenshotReady(1300)
                &&!p.claimAnalysis(1300)&&!p.waitForSelection(1300)&&!p.claimRetainedSelection(500,500,1300),"retained selection permanently excludes the automatic capture and analysis branches");
        check(!p.analysisReady(null,1400)&&p.analysisReady(target(),1500)&&p.complete(1600),"retained work still requires one valid selected result before handoff");
        check(p.samplesCaptured()==0&&p.deadlineAt()==21000,"retained work preserves truthful capture diagnostics and the original deadline");
        LiveFrameCapturePolicy started=job();started.claimScreenshot(1100);
        check(!started.claimRetainedSelection(500,500,1150),"an in-flight fresh screenshot cannot be replaced with a retained selection");
        started.screenshotReady(1200);
        check(!started.claimRetainedSelection(500,500,1250),"a sampled instance is no longer fresh for retained selection");
    }

    private static void terminalSelection(){
        for(int step=0;step<7;step++){
            LiveFrameCapturePolicy p=job();
            if(step>=1)p.claimScreenshot(1100);
            if(step>=2)p.screenshotReady(1200);
            if(step>=3)p.claimAnalysis(1300);
            if(step>=4)p.waitForSelection(1400);
            if(step>=5)p.claimSelectedAnalysis(500,500,1500);
            if(step>=6)p.analysisReady(target(),1600);
            int samples=p.samplesCaptured();boolean selection=p.selectionAnalysisClaimed();
            p.cancel();
            check(!p.claimScreenshot(1700)&&!p.claimAdditionalScreenshot(1700)&&!p.skipAdditionalScreenshot(1700)&&!p.screenshotReady(1700)
                    &&!p.claimAnalysis(1700)&&!p.waitForSelection(1700)&&!p.claimSelectedAnalysis(500,500,1700)
                    &&!p.claimRetainedSelection(500,500,1700)&&!p.analysisReady(target(),1700)&&!p.complete(1700),"cancel at every capture/selection stage rejects all late claims and results");
            check(p.stage()==LiveFrameCapturePolicy.Stage.CANCELLED&&p.samplesCaptured()==samples
                    &&p.selectionAnalysisClaimed()==selection,"late callbacks cannot alter cancelled diagnostics or resurrect the attempt");
        }
        LiveFrameCapturePolicy departed=selecting();
        check(!departed.observe(LivePage.of(ROOM.packageName(),199,"another presenter",""),1500)
                &&!departed.claimSelectedAnalysis(500,500,1600)&&!departed.claimRetainedSelection(500,500,1600),"leaving the verified room also revokes selected and retained entry points");
    }

    private static void selectionDeadlines(){
        LiveFrameCapturePolicy p=selecting();
        check(p.claimSelectedAnalysis(500,500,20999)&&p.deadlineAt()==21000,"a valid late tap cannot renew the preparation deadline");
        check(!p.analysisReady(target(),21000)&&!p.claimSelectedAnalysis(500,500,2000),"a late selected result expires permanently even while the old attempt has expired");
        LiveFrameCapturePolicy waiting=selecting();
        check(!waiting.claimSelectedAnalysis(500,500,21000)&&!waiting.claimSelectedAnalysis(500,500,1500),"waiting at the deadline cannot reuse an expired attempt by moving time backward");
        LiveFrameCapturePolicy retained=job();
        check(!retained.claimRetainedSelection(500,500,21000)&&!retained.claimRetainedSelection(500,500,1500),"retained selection cannot revive an expired fresh instance");
        LiveFrameCapturePolicy sampling=job();sampling.claimScreenshot(1100);sampling.screenshotReady(1200);
        check(!sampling.claimAdditionalScreenshot(21000)&&!sampling.claimAnalysis(1500),"additional sampling also fails closed at the unchanged deadline");
        LiveFrameCapturePolicy fresh=new LiveFrameCapturePolicy(ROOM,7,22000);
        check(fresh.claimRetainedSelection(500,500,22100)&&fresh.samplesCaptured()==0&&fresh.deadlineAt()==42000
                &&!p.isActive(22100),"only a separate new explicit attempt instance can resume frozen work; it never renews the old instance");
    }
}
