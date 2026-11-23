// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exercises the actual Android adapters with fresh, counted/recycled fake handles. No SDK runtime/network. */
public final class WindowSnapshotReadTest {
    private static int assertions;
    private static final String PKG="com.zhiliaoapp.musically",ACTIVITY="com.ss.android.ugc.aweme.live.LivePlayActivity";
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static final class Fixture {
        final AccessibilityService service=new AccessibilityService();
        final PowerManager power=new PowerManager();
        final KeyguardManager lock=new KeyguardManager();
        final WindowManager manager=new WindowManager();
        final AccessibilityWindowInfo window=new AccessibilityWindowInfo();
        final AccessibilityNodeInfo root=new AccessibilityNodeInfo();
        Fixture(){
            service.services.put(PowerManager.class,power);service.services.put(KeyguardManager.class,lock);
            service.services.put(WindowManager.class,manager);window.root=root;service.windows.add(window);
            put("wei",50,60,450,130,"Presenter","");
            put("ahd",20,40,700,200,"","Presenter header");
            put("d1i",960,50,1040,130,"","");
            put("mr1",200,200,500,260,"100 viewers","");
            put("f7z",30,2210,750,2310,"private unsent reply","");
            put("f5p",250,2030,750,2110,"Blue shirt","");
            put("f62",20,2000,200,2180,"","");
            put("f4m",800,2100,1040,2200,"Buy","");
            put("f63",250,2120,450,2180,"$15","");
            put("f64",250,2180,700,2230,"In stock","");
            AccessibilityNodeInfo gift=node("",10,300,200,350,"禮物庫","");
            root.texts.put("禮物庫",List.of(gift));
        }
        AccessibilityNodeInfo node(String id,int l,int t,int r,int b,String text,String description){
            AccessibilityNodeInfo node=new AccessibilityNodeInfo();node.viewId=id.isEmpty()?"":PKG+":id/"+id;
            node.bounds=new Rect(l,t,r,b);node.text=text;node.description=description;return node;
        }
        AccessibilityNodeInfo put(String id,int l,int t,int r,int b,String text,String description){
            AccessibilityNodeInfo node=node(id,l,t,r,b,text,description);root.ids.put(PKG+":id/"+id,List.of(node));return node;
        }
        AccessibilityNodeInfo get(String id){return root.ids.get(PKG+":id/"+id).get(0);}
        WindowSnapshot.ReadResult read(){return WindowSnapshot.read(service,ACTIVITY,true);}
        int queries(){return root.idQueries.values().stream().mapToInt(Integer::intValue).sum();}
        void host(String name){get("wei").text=name;get("ahd").description=name+" header";}
        AccessibilityWindowInfo hint(String owner){
            AccessibilityWindowInfo hint=new AccessibilityWindowInfo();hint.id=200+service.windows.size();
            hint.type=AccessibilityWindowInfo.TYPE_SYSTEM;hint.layer=2;hint.active=false;hint.focused=false;
            hint.bounds=new Rect(21,23,1059,528);hint.root=new AccessibilityNodeInfo();
            hint.root.packageName=owner;hint.root.windowId=hint.id;service.windows.add(hint);return hint;
        }
        WindowSnapshot.ReadResult occluded(LivePage source){get("mr1").visible=false;return WindowSnapshot.readForOffer(service,ACTIVITY,source);}
    }
    public static void main(String[] args)throws Exception {
        Looper.getMainLooper();
        countedSampling();freshBoundaryChanges();geometryRules();windowRules();offerOcclusion();detailLiveContainer();previewWindows();compatibilityAndOwnership();
        System.out.println(assertions+" window snapshot adapter assertions passed");
    }
    private static void countedSampling(){
        Fixture baseline=new Fixture();
        for(int frame=0;frame<3;frame++)for(int boundary=0;boundary<2;boundary++){
            var page=WindowSnapshot.read(baseline.service,ACTIVITY).page();
            check(page!=null&&LiveFrameWindow.read(baseline.service,page)!=null,"standalone baseline reads valid geometry");
        }
        check(baseline.service.windowReads==12&&baseline.window.rootRequests==12&&baseline.root.refreshes==12,
            "old independent page/layout path reads and refreshes twelve roots for three frames");
        check(baseline.queries()==102,"baseline repeats six geometry queries after each eleven-ID page read");
        Fixture merged=new Fixture();
        for(int frame=0;frame<3;frame++){
            var before=merged.read();var after=merged.read();
            check(before.page()!=null&&after.page()!=null&&before.layout()!=null&&before.layout().equals(after.layout()),
                "each frame's independent fresh pre/post reads yield matching page and geometry");
            check(before.layout().contentTop()==260&&before.layout().contentBottom()==2000,
                "same queried header/product bounds define trusted video rather than full screenshot");
        }
        check(merged.service.windowReads==6&&merged.window.rootRequests==6&&merged.root.refreshes==6,
            "three frames use six selected-root reads/refreshes, with no second layout root");
        check(merged.queries()==66&&merged.root.idQueries.size()==11,"merged reads issue only the eleven existing ID queries");
        for(int count:merged.root.idQueries.values())check(count==6,"every ID is queried once per fresh boundary");
        check(merged.window.recycles==6&&merged.root.recycles==6,"every fresh window and selected root is recycled once");
    }
    private static void freshBoundaryChanges(){
        Fixture f=new Fixture();var before=f.read();
        f.get("f62").bounds.top=1900;
        var after=f.read();
        check(after.page()!=null&&after.layout()!=null&&!before.layout().equals(after.layout()),
            "a native product-bar move between screenshot boundaries changes the fresh layout");
        check(before.layout().contentBottom()==2000&&after.layout().contentBottom()==1900,
            "layout is a value from its own read, never a mutable or stale cached result");
        LiveFrameContinuity continuity=new LiveFrameContinuity(before.page(),1000,21000);
        f.host("Other Presenter");f.get("f62").bounds.top=1850;
        var changed=f.read();
        check(changed.page()!=null&&changed.page().hostName().equals("Other Presenter")&&changed.layout().contentBottom()==1850,
            "fresh host and changed geometry come from the same current root");
        check(continuity.observe(changed.page(),changed.reason(),changed.windowId(),changed.packageName(),1100)==LiveFrameContinuity.Result.CANCEL,
            "same-window room changes cannot reuse a previous boundary's authorization");
        f=new Fixture();before=f.read();f.window.id=101;f.root.windowId=101;
        after=f.read();
        continuity=new LiveFrameContinuity(before.page(),1000,21000);
        check(after.layout()!=null&&after.layout().windowId()==101&&!before.layout().equals(after.layout()),"fresh window identity is included in layout equality");
        check(continuity.observe(after.page(),after.reason(),after.windowId(),after.packageName(),1100)==LiveFrameContinuity.Result.CANCEL,
            "a new window is rejected even with identical presenter and garment bounds");
    }
    private static void geometryRules(){
        Fixture f=new Fixture();AccessibilityNodeInfo original=f.get("f62");
        f.root.ids.put(PKG+":id/f62",Collections.nCopies(17,original));
        var read=f.read();
        check(read.page()!=null&&read.page().hasProduct()&&read.layout()==null,
            "raw duplicate count over sixteen rejects layout even though page nodes deduplicate");
        f=new Fixture();original=f.get("f62");AccessibilityNodeInfo invisible=f.node("f62",0,0,0,0,"","");invisible.visible=false;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();nodes.add(original);nodes.addAll(Collections.nCopies(16,invisible));
        f.root.ids.put(PKG+":id/f62",nodes);read=f.read();
        check(read.page()!=null&&read.layout()==null,"raw invisible matches still count against the geometry query cap");
        nodes.remove(nodes.size()-1);read=f.read();
        check(read.layout()!=null,"sixteen raw matches with fifteen invisible entries remain valid");
        for(Rect box:new Rect[]{new Rect(20,1000,200,1180),new Rect(-1,2000,200,2180),new Rect(20,2000,1090,2180),new Rect(20,2000,20,2180)}){
            f=new Fixture();f.get("f62").bounds=box;read=f.read();
            check(read.page()!=null&&read.layout()==null,"upper-half, out-of-window and empty product geometry fail closed");
        }
        f=new Fixture();f.get("ahd").bounds.bottom=1800;f.get("f62").bounds.top=2000;
        read=f.read();check(read.page()!=null&&read.layout()==null,"less than 256 video pixels cannot authorize a screenshot crop");
        f=new Fixture();f.get("ahd").bounds.bottom=1744;
        read=f.read();check(read.layout()!=null&&read.layout().contentBottom()-read.layout().contentTop()==256,"exact 256px geometry retains existing boundary behavior");
        f=new Fixture();f.get("wei").visible=false;
        check(f.read().page()==null&&f.read().layout()==null,"a layout is never exposed for an unverified invisible host");
        f=new Fixture();f.root.ids.put(PKG+":id/wei",List.of(f.get("wei"),f.node("wei",50,60,450,130,"Other Presenter","")));
        check(f.read().page()==null&&f.read().layout()==null,"two visible host anchors remain ambiguous");
    }
    private static void rejected(Fixture f,String reason){
        var read=f.read();check(read.page()==null&&read.layout()==null&&reason.equals(read.reason()),"unsafe source rejected: "+reason+" (got "+read.reason()+")");
    }
    private static void windowRules(){
        Fixture f=new Fixture();f.lock.deviceLocked=true;rejected(f,"screen_locked");check(f.service.windowReads==0,"device lock fails before root reads");
        f=new Fixture();f.lock.keyguardLocked=true;rejected(f,"screen_locked");check(f.service.windowReads==0,"keyguard lock fails before root reads");
        f=new Fixture();f.power.interactive=false;rejected(f,"screen_locked");
        f=new Fixture();AccessibilityWindowInfo keyboard=new AccessibilityWindowInfo();keyboard.id=200;keyboard.type=AccessibilityWindowInfo.TYPE_INPUT_METHOD;keyboard.bounds=new Rect(0,1600,1080,2424);f.service.windows.add(keyboard);rejected(f,"keyboard_visible");
        f=new Fixture();AccessibilityWindowInfo other=new AccessibilityWindowInfo();other.id=200;f.service.windows.add(other);rejected(f,"multiple_app_windows");
        f=new Fixture();f.window.pip=true;rejected(f,"picture_in_picture");
        f=new Fixture();f.window.active=false;f.window.focused=false;rejected(f,"app_not_focused");
        f=new Fixture();f.root.packageName="other.app";rejected(f,"outside_tiktok");
        f=new Fixture();f.window.bounds.right=700;rejected(f,"window_not_fullscreen");
        f=new Fixture();f.root.refreshable=false;rejected(f,"root_unavailable");
        f=new Fixture();f.manager.bounds=new Rect(0,0,2424,1080);rejected(f,"unsupported_orientation");
        f=new Fixture();f.window.displayId=1;rejected(f,"multiple_app_windows");
        f=new Fixture();AccessibilityWindowInfo overlay=new AccessibilityWindowInfo();overlay.id=200;overlay.type=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;overlay.active=false;overlay.focused=false;f.service.windows.add(overlay);
        check(f.read().layout()!=null,"non-focusable accessibility progress overlay does not replace the sole verified application");
    }
    private static boolean retained(WindowSnapshot.ReadResult read,LivePage source){
        return OfferOcclusionGuard.retain(new ObservationPolicy.Candidate(ObservationPolicy.Kind.TEXT,1,source,1000),
            true,read.reason(),read.page()!=null,read.offerOcclusion(),1100,60000);
    }
    private static void offerOcclusion(){
        Fixture f=new Fixture();LivePage source=f.read().page();AccessibilityWindowInfo hint=f.hint("org.agentos.assistant");
        var read=f.occluded(source);
        check(read.page()==null&&read.layout()==null&&"not_live_room".equals(read.reason())&&retained(read,source),
            "actual unnamed, textless platform Assistant popup preserves only metadata for the original offer");
        check(f.get("wei").visible&&f.get("ahd").visible,"lost LIVE evidence need not hide stable presenter anchors");
        check(hint.root.refreshes==1&&hint.root.recycles==hint.rootRequests,"popup metadata and diagnostics recycle every fresh popup root");
        read=f.read();check(read.page()==null&&read.layout()==null&&read.offerOcclusion()==null,
            "capture/strong read never receives the offer-preserving metadata");
        f.get("mr1").visible=true;read=WindowSnapshot.readForOffer(f.service,ACTIVITY,source);
        check(read.page()!=null&&!retained(read,source),"visible recovery resumes ordinary page validation");
        check(OfferOcclusionGuard.acceptance(new ObservationPolicy.Candidate(ObservationPolicy.Kind.TEXT,1,source,1000),true,
            read.reason(),true,read.offerOcclusion(),1100,2200,60000)==OfferOcclusionGuard.Acceptance.WAIT,
            "even a visible page cannot start acceptance while the real popup remains attached");
        f.service.windows.remove(hint);read=WindowSnapshot.readForOffer(f.service,ACTIVITY,source);
        check(OfferOcclusionGuard.acceptance(new ObservationPolicy.Candidate(ObservationPolicy.Kind.TEXT,1,source,1000),true,
            read.reason(),true,read.offerOcclusion(),1100,2200,60000)==OfferOcclusionGuard.Acceptance.READY,
            "only detached popup plus fresh visible page is ready for the separate strong acceptance read");
        for(String owner:new String[]{"org.agentos.surfaces","org.agentos.assistant"}){
            f=new Fixture();source=f.read().page();f.hint(owner);check(retained(f.occluded(source),source),"supported platform host identity: "+owner);
        }
        for(int failure=0;failure<18;failure++){
            f=new Fixture();source=f.read().page();hint=f.hint("org.agentos.assistant");
            switch(failure){
                case 0 -> f.host("Other Presenter");
                case 1 -> {f.window.id=101;f.root.windowId=101;}
                case 2 -> f.root.ids.remove(PKG+":id/wei");
                case 3 -> f.root.ids.put(PKG+":id/wei",List.of(f.get("wei"),f.get("wei")));
                case 4 -> f.get("wei").bounds=new Rect(0,0,0,0);
                case 5 -> f.get("wei").bounds=new Rect(-500,20,-100,100);
                case 6 -> f.get("wei").password=true;
                case 7 -> f.get("ahd").description="Different Host header";
                case 8 -> f.root.ids.remove(PKG+":id/f7z");
                case 9 -> hint.root.packageName="unknown.overlay";
                case 10 -> f.service.packages.platformSigned.remove("org.agentos.assistant");
                case 11 -> f.service.packages.assistantUid=10100;
                case 12 -> f.hint("org.agentos.surfaces");
                case 13 -> hint.focused=true;
                case 14 -> hint.bounds=new Rect(21,800,1059,1300);
                case 15 -> hint.bounds=new Rect(0,0,1080,2424);
                case 16 -> hint.layer=-1;
                case 17 -> hint.root.refreshable=false;
            }
            check(!retained(f.occluded(source),source),"ambiguous/foreign/changed/unsafe metadata fails closed: "+failure);
        }
        f=new Fixture();source=f.read().page();hint=f.hint("org.agentos.assistant");
        AccessibilityWindowInfo chrome=f.hint("com.android.systemui");chrome.bounds=new Rect(0,0,1080,30);
        check(retained(f.occluded(source),source),"narrow signed SystemUI status chrome does not invalidate the matching popup");
        chrome.bounds=new Rect(0,0,1080,1000);check(!retained(f.occluded(source),source),"large SystemUI dialog is an unknown overlay");
        f=new Fixture();source=f.read().page();f.hint("org.agentos.assistant");
        AccessibilityWindowInfo keyboard=f.hint("keyboard.app");keyboard.type=AccessibilityWindowInfo.TYPE_INPUT_METHOD;
        read=f.occluded(source);check("keyboard_visible".equals(read.reason())&&read.offerOcclusion()==null,"keyboard rejection precedes metadata preservation");
        f=new Fixture();source=f.read().page();f.hint("org.agentos.assistant");f.lock.deviceLocked=true;
        read=f.occluded(source);check("screen_locked".equals(read.reason())&&read.offerOcclusion()==null,"lock rejection precedes metadata preservation");
        f=new Fixture();source=f.read().page();f.hint("org.agentos.assistant");f.window.focused=false;f.window.active=false;
        read=f.occluded(source);check("app_not_focused".equals(read.reason())&&read.offerOcclusion()==null,"app switch cannot preserve a displayed offer");
    }
    private static void detailLiveContainer(){
        String detail="com.ss.android.ugc.aweme.detail.ui.DetailActivity";
        Fixture f=new Fixture();f.root.texts.clear();
        var strong=WindowSnapshot.read(f.service,detail,true);
        check(strong.page()!=null&&strong.layout()!=null&&"live_room".equals(strong.reason()),
            "fresh DetailActivity LIVE anchors authorize page and geometry without gift text");
        var passive=WindowSnapshot.readPassive(f.service,detail,null);
        check(passive.page()!=null&&passive.page().contentKey().equals(strong.page().contentKey()),
            "passive and strong DetailActivity reads share the verified page");
        LivePage source=strong.page();f.hint("org.agentos.assistant");f.get("mr1").visible=false;
        var occluded=WindowSnapshot.readForOffer(f.service,detail,source);
        check(occluded.page()==null&&"not_live_room".equals(occluded.reason())&&retained(occluded,source),
            "DetailActivity hint retention uses the same verified container helper");
        check(WindowSnapshot.read(f.service,detail,true).page()==null
            &&WindowSnapshot.read(f.service,detail,true).layout()==null,
            "retained DetailActivity offer metadata cannot bypass strong source validation");
        var unknown=WindowSnapshot.readForOffer(f.service,"example.other.DetailActivity",source);
        check(unknown.page()==null&&unknown.offerOcclusion()==null,
            "unverified DetailActivity aliases cannot retain an occluded offer");
        f.get("mr1").visible=true;f.service.windows.remove(1);
        check(WindowSnapshot.read(f.service,detail,true).layout()!=null,
            "DetailActivity recovers ordinary fresh source validation after hint exit");
    }
    private static void compatibilityAndOwnership()throws Exception {
        Fixture f=new Fixture();var old=WindowSnapshot.read(f.service,ACTIVITY);
        check(old.page()!=null&&old.layout()==null,"legacy page-only overload retains its original behavior");
        check(new WindowSnapshot.ReadResult(old.page(),"live_room").windowId()==100&&new WindowSnapshot.ReadResult(old.page(),"live_room",100,PKG).layout()==null,
            "legacy result constructors remain compatible");
        f=new Fixture();AccessibilityNodeInfo broken=f.get("f62");broken.throwBounds=true;
        rejected(f,"snapshot_unavailable");
        check(broken.recycles==1&&f.root.recycles==1&&f.window.recycles==1,"geometry exceptions recycle the raw match, root and window handles");
        Fixture offThread=new Fixture();WindowSnapshot.ReadResult[] result=new WindowSnapshot.ReadResult[1];
        Thread worker=new Thread(()->result[0]=offThread.read());worker.start();worker.join();
        check("wrong_thread".equals(result[0].reason())&&result[0].layout()==null&&offThread.service.windowReads==0,"fresh combined reads retain the Android main-thread requirement");
    }

    private static void previewWindows(){
        for(Rect bounds:new Rect[]{new Rect(402,28,743,144),new Rect(296,44,782,777),new Rect(0,0,1080,2424)}) {
            Fixture f=new Fixture();LivePage before=f.read().page();
            AccessibilityWindowInfo preview=f.hint("org.taku.tryon");preview.root.description="Live try-on window";
            preview.bounds=new Rect(bounds);preview.root.bounds=new Rect(bounds);
            var read=WindowSnapshot.readPassive(f.service,ACTIVITY,before);
            check(read.page()==null&&read.layout()==null&&"try_on_preview".equals(read.reason()),"actual passive read detects compact/preview/fullscreen Native root");
            check(WindowSnapshot.diagnostic().contains("\"reason\":\"try_on_preview\""),"preview reason remains readable as fixed diagnostic metadata");
            check(preview.root.textReads==0,"preview detection never reads Native text");
            check(WindowSnapshot.read(f.service,ACTIVITY).page()!=null,"strong LIVE/source read retains its original overlay rules");
            f.service.windows.remove(preview);
            check(WindowSnapshot.readPassive(f.service,ACTIVITY,before).page()!=null,"preview removal immediately restores ordinary LIVE read without cooldown");
        }
        Fixture f=new Fixture();AccessibilityWindowInfo host=f.hint("org.agentos.assistant");
        AccessibilityNodeInfo child=f.node("",402,28,743,144,"private Native text","Live cosplay · Lucy");child.packageName="org.taku.tryon";host.root.children.add(child);
        check("try_on_preview".equals(WindowSnapshot.readPassive(f.service,ACTIVITY,null).reason()),"bounded child traversal finds the Native root embedded in trusted Assistant SCVH");
        check(child.textReads==0&&host.root.descriptionReads==0,"embedded detection reads only the Native fixed description, not Assistant text or description");
        check(child.recycles==1,"embedded Native child handle is recycled on an early matching return");
        for(int kind=0;kind<10;kind++) {
            f=new Fixture();host=f.hint("org.taku.tryon");host.root.description="Live try-on window";
            switch(kind) {
                case 0 -> host.root.description="AI try-on settings";
                case 1 -> {host.root.description="";host.root.text="Live try-on window";}
                case 2 -> host.root.visible=false;
                case 3 -> host.root.bounds=new Rect(0,0,0,0);
                case 4 -> host.root.bounds=new Rect(1500,0,1800,100);
                case 5 -> host.displayId=1;
                case 6 -> host.root.packageName="other.overlay";
                case 7 -> host.root.packageName="org.agentos.assistant";
                case 8 -> host.root.editable=true;
                case 9 -> host.root.password=true;
            }
            check(WindowSnapshot.readPassive(f.service,ACTIVITY,null).page()!=null,"settings, text spoof, hidden/offscreen/foreign windows never pause LIVE observation: "+kind);
            check(host.root.textReads==0,"Native/settings node text never enters preview detection");
        }
        for(boolean trusted:new boolean[]{true,false}) {
            f=new Fixture();host=f.hint("org.agentos.assistant");child=f.node("",402,28,743,144,"private","Live try-on window");child.packageName="org.taku.tryon";host.root.children.add(child);
            if(!trusted)f.service.packages.platformSigned.remove("org.agentos.assistant");
            check("try_on_preview".equals(WindowSnapshot.readPassive(f.service,ACTIVITY,null).reason())==trusted,"only platform-signed Assistant can introduce an embedded Native preview");
        }
        f=new Fixture();host=f.hint("org.taku.tryon");host.root.description="Live try-on window";host.type=AccessibilityWindowInfo.TYPE_APPLICATION;
        check("try_on_preview".equals(WindowSnapshot.readPassive(f.service,ACTIVITY,null).reason()),"passive read recognizes a real separate Native application window");
        check("multiple_app_windows".equals(WindowSnapshot.read(f.service,ACTIVITY).reason()),"strong screenshot/explicit read does not gain a multiple-window exception");
        // Exhaust one shared read-wide node budget before the second trusted window.
        f=new Fixture();host=f.hint("org.agentos.assistant");
        for(int branch=0;branch<32;branch++) {
            AccessibilityNodeInfo parent=host.root;
            for(int depth=0;depth<8;depth++) {
                AccessibilityNodeInfo node=f.node("",402,28,743,144,"private","");node.packageName="org.taku.tryon";parent.children.add(node);parent=node;
            }
        }
        AccessibilityWindowInfo beyond=f.hint("org.taku.tryon");beyond.root.description="Live try-on window";
        check(WindowSnapshot.readPassive(f.service,ACTIVITY,null).page()!=null,"one shared bounded node budget prevents walking arbitrary additional trees");
        check(beyond.root.refreshes==0&&beyond.root.descriptionReads==0,"later windows cannot reset the read-wide node budget");
        f=new Fixture();host=f.hint("org.agentos.assistant");AccessibilityNodeInfo parent=host.root;
        for(int depth=0;depth<14;depth++) {AccessibilityNodeInfo node=f.node("",402,28,743,144,"private",depth==13?"Live try-on window":"");node.packageName="org.taku.tryon";parent.children.add(node);parent=node;}
        check(WindowSnapshot.readPassive(f.service,ACTIVITY,null).page()!=null,"depth bound rejects a marker hidden beyond the supported embedded view depth");
    }
}
