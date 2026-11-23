// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class LiveFrameContinuityTest {
    private static int assertions;
    private static final String PKG="com.zhiliaoapp.musically";
    private static final LivePage SOURCE=LivePage.of(PKG,100,"Presenter","Original shirt");
    private static LiveFrameContinuity fresh(){return new LiveFrameContinuity(SOURCE,1000,21000);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static LiveFrameContinuity.Result read(LiveFrameContinuity gate,LivePage page,long now){
        return gate.observe(page,"live_room",page.windowId(),page.packageName(),now);
    }
    private static LiveFrameContinuity.Result missing(LiveFrameContinuity gate,String reason,long now){
        return gate.observe(null,reason,100,PKG,now);
    }
    public static void main(String[] args){
        var p=fresh();
        check(read(p,SOURCE,1000)==LiveFrameContinuity.Result.VERIFIED,"source starts verified");
        check(missing(p,"not_live_room",1200)==LiveFrameContinuity.Result.WAIT,"one incomplete room tree waits rather than pretending a different room");
        check(missing(p,"app_not_focused",1400)==LiveFrameContinuity.Result.WAIT,"a second same-window transient remains only WAIT");
        check(read(p,LivePage.of(PKG,100,"Presenter","Rotated product"),1500)==LiveFrameContinuity.Result.VERIFIED,"verified same presenter survives card churn and clears the gap");
        check(missing(p,"app_not_focused",3000)==LiveFrameContinuity.Result.WAIT,"a later independent transient can wait");
        check(read(p,SOURCE,3100)==LiveFrameContinuity.Result.VERIFIED,"one or two transient reads do not cancel later verification");

        p=fresh();missing(p,"not_live_room",2000);
        check(missing(p,"app_not_focused",3499)==LiveFrameContinuity.Result.WAIT,"continuous missing reads have a strict 1500ms allowance");
        check(missing(p,"not_live_room",3500)==LiveFrameContinuity.Result.CANCEL,"exact grace boundary cancels rather than renewing on each read");
        check(read(p,SOURCE,3501)==LiveFrameContinuity.Result.CANCEL,"verified data cannot resurrect a cancelled request");

        for(LivePage page:new LivePage[]{LivePage.of(PKG,100,"Another Presenter","Same shirt"),
                LivePage.of(PKG,101,"Presenter","Original shirt"),
                LivePage.of("com.ss.android.ugc.trill",100,"Presenter","Original shirt")}) {
            p=fresh();missing(p,"not_live_room",1100);
            check(read(p,page,1200)==LiveFrameContinuity.Result.CANCEL,"verified host, window or package changes cancel immediately during a gap");
        }
        for(String reason:new String[]{"screen_locked","keyboard_visible","multiple_app_windows","unsupported_orientation",
                "picture_in_picture","window_not_fullscreen","outside_tiktok","root_unavailable","snapshot_unavailable",
                "window_unavailable","wrong_thread","live_room","unknown",null}) {
            p=fresh();
            check(missing(p,reason,1100)==LiveFrameContinuity.Result.CANCEL,"unsafe or unknown reason cannot use transient continuity: "+reason);
        }
        for(String pkg:new String[]{"","another.app",null}) {
            p=fresh();
            check(p.observe(null,"app_not_focused",100,pkg,1100)==LiveFrameContinuity.Result.CANCEL,"unverified or different package cannot use grace");
        }
        p=fresh();
        check(p.observe(null,"not_live_room",101,PKG,1100)==LiveFrameContinuity.Result.CANCEL,"different null-page window cancels immediately");
        p=fresh();
        check(p.observe(null,"not_live_room",-1,PKG,1100)==LiveFrameContinuity.Result.CANCEL,"missing window identity fails closed");
        p=fresh();
        check(p.observe(SOURCE,"not_live_room",100,PKG,1100)==LiveFrameContinuity.Result.CANCEL,"a page and failure reason cannot be combined as verification");
        p=fresh();
        check(p.observe(SOURCE,"live_room",101,PKG,1100)==LiveFrameContinuity.Result.CANCEL,"page metadata must agree with its verified window");

        p=fresh();
        check(read(p,SOURCE,20999)==LiveFrameContinuity.Result.VERIFIED,"verification is still valid before the original hard deadline");
        check(read(p,SOURCE,21000)==LiveFrameContinuity.Result.CANCEL,"exact hard deadline cancels even fully verified pages");
        p=fresh();missing(p,"not_live_room",20500);
        check(missing(p,"app_not_focused",20999)==LiveFrameContinuity.Result.WAIT,"grace may wait only inside the existing deadline");
        check(missing(p,"not_live_room",21000)==LiveFrameContinuity.Result.CANCEL,"grace never extends the original twenty-second deadline");
        p=new LiveFrameContinuity(SOURCE,1000,2000);
        check(missing(p,"not_live_room",1999)==LiveFrameContinuity.Result.WAIT
            &&read(p,SOURCE,2000)==LiveFrameContinuity.Result.CANCEL,"a shorter action-token deadline remains binding");
        p=fresh();
        check(read(p,SOURCE,999)==LiveFrameContinuity.Result.CANCEL&&read(p,SOURCE,1001)==LiveFrameContinuity.Result.CANCEL,"clock rollback before start is terminal");
        p=fresh();read(p,SOURCE,1200);
        check(missing(p,"not_live_room",1199)==LiveFrameContinuity.Result.CANCEL,"clock rollback within observation is terminal");
        for(long[] range:new long[][]{{-1,2000},{1000,1000},{1000,999},{1000,21001}}) {
            boolean rejected=false;
            try{new LiveFrameContinuity(SOURCE,range[0],range[1]);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid or extended continuity deadline rejected");
        }
        System.out.println(assertions+" live frame continuity assertions passed");
    }
}
