package org.agentos.shopping;

public final class OfferOcclusionGuardTest {
    private static int assertions;
    private static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
    private static OfferOcclusionGuard.Evidence evidence(int window,String pkg,boolean focused,boolean sameRoom,int hints,boolean other) {
        return new OfferOcclusionGuard.Evidence(window,pkg,focused,sameRoom,hints,other);
    }
    public static void main(String[] args) {
        String pkg="com.zhiliaoapp.musically";LivePage room=LivePage.of(pkg,890,"Presenter","Blue top");
        ObservationPolicy policy=new ObservationPolicy();policy.configure(true,true,true,1000);policy.observe(room,1000);
        var offer=policy.explicit(1000);long generation=policy.generation();
        var own=evidence(890,pkg,true,true,1,false);
        for(int elapsed=0;elapsed<60000;elapsed+=200) {
            check(OfferOcclusionGuard.retain(offer,policy.isCurrent(offer,1000+elapsed),"not_live_room",false,own,1000+elapsed,60000),"bounded passive/ACK reads retain only the original offer");
        }
        check(offer.createdAt()==1000 && policy.generation()==generation,"preservation never renews creation time or manufactures a generation");
        check(!OfferOcclusionGuard.retain(offer,policy.isCurrent(offer,61000),"not_live_room",false,own,61000,60000),"original exact TTL ends preservation");
        check(!OfferOcclusionGuard.retain(offer,true,"not_live_room",false,own,999,60000),"clock reversal is not fresh");
        check(!OfferOcclusionGuard.retain(offer,false,"not_live_room",false,own,1100,60000),"stale candidate/generation cannot be retained");
        check(!OfferOcclusionGuard.retain(null,true,"not_live_room",false,own,1100,60000),"no offer cannot become an offer");
        check(!OfferOcclusionGuard.retain(new ObservationPolicy.Candidate(ObservationPolicy.Kind.VISION,generation,room,1000),true,"not_live_room",false,own,1100,60000),"metadata never authorizes automatic model work");
        check(!OfferOcclusionGuard.retain(offer,true,"live_room",true,own,1100,60000),"restored visible source follows ordinary validation");
        for(String reason:new String[]{"screen_locked","outside_tiktok","app_not_focused","multiple_app_windows","root_unavailable","unsupported_orientation","keyboard_visible","snapshot_unavailable"})
            check(!OfferOcclusionGuard.retain(offer,true,reason,false,own,1100,60000),"other source failures remain immediate: "+reason);
        for(var invalid:new OfferOcclusionGuard.Evidence[]{null,evidence(891,pkg,true,true,1,false),evidence(890,"other.app",true,true,1,false),
                evidence(890,pkg,false,true,1,false),evidence(890,pkg,true,false,1,false),evidence(890,pkg,true,true,0,false),
                evidence(890,pkg,true,true,2,false),evidence(890,pkg,true,true,1,true)})
            check(!OfferOcclusionGuard.retain(offer,true,"not_live_room",false,invalid,1100,60000),"unknown/changed/ambiguous source cannot retain a hint");
        long deadline=2200;
        for(long at=1100;at<deadline;at+=100)
            check(OfferOcclusionGuard.acceptance(offer,true,"not_live_room",false,own,at,deadline,60000)==OfferOcclusionGuard.Acceptance.WAIT,
                "bounded accept waits without executing while popup is attached");
        var detached=evidence(890,pkg,true,true,0,false);
        check(OfferOcclusionGuard.acceptance(offer,true,"not_live_room",false,detached,1600,deadline,60000)==OfferOcclusionGuard.Acceptance.WAIT,
            "detached popup with stale visibility cache cannot authorize work");
        check(OfferOcclusionGuard.acceptance(offer,true,"live_room",true,own,1700,deadline,60000)==OfferOcclusionGuard.Acceptance.WAIT,
            "visible source with an attached popup must still wait");
        check(OfferOcclusionGuard.acceptance(offer,true,"live_room",true,detached,1800,deadline,60000)==OfferOcclusionGuard.Acceptance.READY,
            "detached popup and fresh verified source permit only the separate strict read");
        for(var optional:new OfferOcclusionGuard.Evidence[]{null,evidence(890,pkg,true,false,0,false),
                evidence(890,pkg,true,true,0,true)})
            check(OfferOcclusionGuard.acceptance(offer,true,"live_room",true,optional,1800,deadline,60000)==OfferOcclusionGuard.Acceptance.READY,
                "optional metadata cannot silently veto a verified LIVE page; strict begin validation still follows");
        check(OfferOcclusionGuard.acceptance(offer,true,"live_room",true,detached,deadline,deadline,60000)==OfferOcclusionGuard.Acceptance.REJECT,
            "exact pending deadline cannot be extended by another callback");
        check(OfferOcclusionGuard.acceptance(offer,true,"live_room",true,detached,61000,62000,60000)==OfferOcclusionGuard.Acceptance.REJECT,
            "pending hard deadline cannot exceed original offer TTL");
        for(var invalid:new OfferOcclusionGuard.Evidence[]{null,evidence(891,pkg,true,true,0,false),evidence(890,pkg,true,false,0,false),
                evidence(890,pkg,true,true,2,false),evidence(890,pkg,true,true,0,true)})
            check(OfferOcclusionGuard.acceptance(offer,true,"not_live_room",false,invalid,1200,deadline,60000)==OfferOcclusionGuard.Acceptance.REJECT,
                "waiting cannot cross missing/changed/ambiguous identity or an unknown overlay");
        check(OfferOcclusionGuard.acceptance(offer,false,"live_room",true,detached,1200,deadline,60000)==OfferOcclusionGuard.Acceptance.REJECT,
            "revoked generation/consumed offer cancels a pending accept");
        check(OfferOcclusionGuard.acceptance(offer,true,"keyboard_visible",false,detached,1200,deadline,60000)==OfferOcclusionGuard.Acceptance.REJECT,
            "other read failure never receives acceptance grace");
        policy.observe(null,61000);check(!policy.isCurrent(offer,61001),"ordinary source invalidation resumes at expiry");
        System.out.println(assertions+" bounded offer-occlusion assertions passed");
    }
}
