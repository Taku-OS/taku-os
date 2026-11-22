// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class ObservationPolicyTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static LivePage garment(int w) { return RecordedLiveNodes.page("Highland Fashion", RecordedLiveNodes.CAPE, w); }
    private static LivePage room(String host, int w) { return RecordedLiveNodes.page(host, "", w); }
    private static ObservationPolicy enabled(boolean classification, boolean vision) {
        var p = new ObservationPolicy(); p.configure(true, classification, vision, 0); return p;
    }
    private static ObservationPolicy.Candidate classify(ObservationPolicy p, LivePage page, long at) {
        p.observe(page, at);
        var request = p.poll(at + 4000);
        check(request != null && request.kind() == ObservationPolicy.Kind.CLASSIFY, "Card requires text-only semantic classification");
        return request;
    }
    private static ObservationPolicy.Candidate apparel(ObservationPolicy p, LivePage page, long at) {
        var request = classify(p, page, at);
        var offer = p.resolveClassification(request, ProductTypeAnswer.Result.APPAREL, at + 4000);
        check(offer != null && offer.kind() == ObservationPolicy.Kind.TEXT, "Positive classification must create final reminder");
        return offer;
    }
    public static void main(String[] args) {
        var p = new ObservationPolicy();
        check(!p.observe(garment(100),0) && p.poll(100000)==null, "Observation defaults off");
        p=enabled(false,false); p.observe(garment(100),0);
        check(p.poll(100000)==null, "Card text cannot silently enable AI");
        p=enabled(false,true); p.observe(garment(100),0);
        check(p.poll(100000)==null, "Vision-only consent cannot classify text or screenshot a known card");
        p=enabled(true,false); p.observe(room("Cindy Clothing",100),0);
        check(p.poll(100000)==null, "Text-only consent cannot enable screenshots");

        p=enabled(true,false); p.observe(garment(100),0);
        check(p.poll(3999)==null, "Title must dwell before leaving device");
        var request=p.poll(4000);
        check(request!=null && request.kind()==ObservationPolicy.Kind.CLASSIFY, "Title cannot locally prove apparel");
        check(p.classificationInFlight() && p.modelInFlight() && !p.visionInFlight(), "Text must occupy one model lease");
        check(p.classificationResult()==null, "Unfinished classification is not apparel");
        check(p.poll(5000)==null && !p.published(request,5000) && !p.accept(request,5000), "Classification is not display/accept ticket");
        var copied=new ObservationPolicy.Candidate(request.kind(),request.generation(),request.page(),request.createdAt());
        check(p.resolveClassification(copied,ProductTypeAnswer.Result.APPAREL,5000)==null && p.modelInFlight(), "Copied request resolved real lease");
        check(p.resolveVision(request,true,5000)==null && p.classificationInFlight(), "Wrong resolver released text lease");
        var a=p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,20000);
        check(a!=null && a.kind()==ObservationPolicy.Kind.TEXT && a.createdAt()==20000, "Inference must not consume fresh offer TTL");
        check(p.classificationResult()==ProductTypeAnswer.Result.APPAREL && !p.modelInFlight(), "Completed state missing");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,20001)==null, "Duplicate callback created second offer");
        check(p.poll(20001)==null && !p.accept(a,20001), "Presentation ACK still required");
        check(p.published(a,20002) && !p.published(a,20003), "Only first real ACK starts cooldown");
        check(p.accept(a,20004) && !p.accept(a,20005), "Acceptance must be single use");
        check(p.isCurrent(a,79999) && !p.isCurrent(a,80000), "Presenter offer context must retain the strict 60-second TTL");

        for(String title:new String[]{RecordedLiveNodes.CAPE,"Dress with included belt","Handbag Print Dress",
                "连衣裙搭配项链","Dress and matching shoes","Robe","ワンピース","فستان","A7","Women's Handbags","帽子"}) {
            p=enabled(true,true); classify(p,RecordedLiveNodes.page("Unrelated Host",title,100),0);
            check(!p.visionInFlight(), "Card cannot fall back to screenshot based on words");
        }
        for(var result:new ProductTypeAnswer.Result[]{ProductTypeAnswer.Result.NON_APPAREL,
                ProductTypeAnswer.Result.UNAVAILABLE,ProductTypeAnswer.Result.TIMED_OUT}) {
            p=enabled(true,true); request=classify(p,garment(100),0);
            check(p.resolveClassification(request,result,5000)==null, "Nonpositive result created reminder");
            check(p.classificationResult()==result && !p.modelInFlight(), "Negative/failure state must stay explicit");
            check(p.poll(100000)==null && !p.visionInFlight(), "Failed classification retried or fell back to screenshot");
        }
        p=enabled(true,false); request=classify(p,garment(100),0);
        check(p.resolveClassification(request,null,5000)==null && p.classificationResult()==ProductTypeAnswer.Result.UNAVAILABLE,
            "Missing result must fail closed and release lease");

        // A valid semantic UNKNOWN keeps its state and offers only neutral image confirmation.
        p=enabled(true,false); request=classify(p,RecordedLiveNodes.page("Any Host","A7",100),0);
        a=p.resolveClassification(request,ProductTypeAnswer.parse("{\"product_type\":\"UNKNOWN\"}"),20000);
        check(a!=null && a.kind()==ObservationPolicy.Kind.TEXT && a.createdAt()==20000, "Valid UNKNOWN must allow a fresh confirmation offer");
        check(p.classificationResult()==ProductTypeAnswer.Result.UNKNOWN && !p.visionInFlight() && !p.modelInFlight(),
            "Unknown was relabeled apparel or automatically started image inference");
        check(!p.accept(a,20001) && p.published(a,20002), "Neutral confirmation must still wait for a real presentation ACK");
        check(p.classificationResult()==ProductTypeAnswer.Result.UNKNOWN && p.accept(a,20003), "Publishing or accepting unknown relabeled it as clothing");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.UNKNOWN,20004)==null && !p.accept(a,20005),
            "Neutral confirmation duplicated a callback or acceptance");
        p=enabled(true,false); request=classify(p,garment(100),0);
        check(p.resolveClassification(request,ProductTypeAnswer.parse("broken JSON"),5000)==null
            && p.classificationResult()==ProductTypeAnswer.Result.UNAVAILABLE, "Bad protocol created a neutral reminder");
        p=enabled(true,false); request=classify(p,garment(100),0);
        check(p.resolveClassification(request,ProductTypeAnswer.Result.UNKNOWN,34000)==null
            && p.classificationResult()==ProductTypeAnswer.Result.TIMED_OUT, "Expired UNKNOWN bypassed classification TTL");
        p=enabled(true,false); request=classify(p,garment(100),0); p.classificationTimedOut(request,12000);
        check(p.resolveClassification(request,ProductTypeAnswer.Result.UNKNOWN,15000)==null
            && p.classificationResult()==ProductTypeAnswer.Result.TIMED_OUT, "Unknown resurrected UI-timed-out request");
        p=enabled(true,false); request=classify(p,garment(100),0); p.observe(garment(101),5000);
        check(p.resolveClassification(request,ProductTypeAnswer.Result.UNKNOWN,6000)==null && p.classificationResult()==null,
            "Old-window UNKNOWN created a new-page reminder");

        p=enabled(true,false); request=classify(p,garment(100),0); long generation=p.generation();
        check(!p.observe(RecordedLiveNodes.parse(RecordedLiveNodes.highland()),4500), "Equivalent snapshot reset dwell");
        check(p.generation()==generation && p.isCurrent(request,4500), "Tree rebuild invalidated stable product");
        p.observe(garment(101),5000);
        check(!p.isCurrent(request,5000) && p.classificationResult()==null, "Window change retained old context/result");
        check(p.poll(9000)==null && p.modelInFlight(), "Context change falsely released HTTP lease");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,10000)==null && !p.modelInFlight(), "Late old-window result offered");
        check(p.poll(10001)!=null, "Real HTTP completion failed to release lease");
        p=enabled(true,false); request=classify(p,garment(100),0);
        p.observe(RecordedLiveNodes.page("Highland Fashion","Blue Dress",100),4500);
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,5000)==null && p.classificationResult()==null,
            "New product inherited old classification");
        request=p.poll(8500); check(request!=null && request.kind()==ObservationPolicy.Kind.CLASSIFY, "New product lost eligibility");
        p.configure(true,false,false,9000);
        check(!p.isCurrent(request,9000) && p.modelInFlight(), "Revocation must invalidate without faking HTTP completion");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,10000)==null && !p.modelInFlight(), "Revoked result offered");
        p.observe(garment(100),11000); check(p.poll(100000)==null, "Classification opt-out ignored");

        p=enabled(true,false); request=classify(p,garment(100),0);
        check(p.isCurrent(request,33999) && !p.isCurrent(request,34000), "Classification uses model TTL");
        check(p.poll(100000)==null && p.modelInFlight(), "TTL expiry pretended HTTP completed");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,100000)==null
            && p.classificationResult()==ProductTypeAnswer.Result.TIMED_OUT && !p.modelInFlight(), "Expired positive result offered");
        p=enabled(true,false); request=classify(p,garment(100),0);
        check(!p.classificationTimedOut(copied,5000) && p.classificationResult()==null, "Unrelated timeout changed current lease");
        check(p.classificationTimedOut(request,12000) && p.classificationInFlight(), "UI timeout must retain real HTTP lease");
        check(!p.isCurrent(request,12000) && p.classificationResult()==ProductTypeAnswer.Result.TIMED_OUT, "UI timeout status missing");
        check(!p.classificationTimedOut(request,13000) && p.poll(14000)==null, "Timeout allowed repeated/concurrent work");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,15000)==null
            && !p.modelInFlight() && p.classificationResult()==ProductTypeAnswer.Result.TIMED_OUT, "Late result resurrected UI timeout");
        check(!p.isCurrent(request,15001), "HTTP completion made timed-out request current again");

        p=enabled(true,false); a=apparel(p,garment(100),0);
        p.observe(RecordedLiveNodes.page("Other Host","Blue Dress",101),5000); request=p.poll(9000);
        check(request!=null, "Unpublished inference started presentation cooldown");
        a=p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,10000); p.published(a,10000);
        p.observe(RecordedLiveNodes.page("Another Host","Red Hoodie",102),11000);
        check(p.poll(15000)==null && p.poll(69999)==null, "Room change bypassed global cooldown");
        check(p.poll(70000)!=null, "Cooldown never recovered");
        p=enabled(true,false); a=apparel(p,garment(100),0); p.published(a,4000); p.accept(a,4500);
        p.observe(null,5000); p.observe(garment(101),6000);
        check(p.poll(10000)==null && p.poll(63999)==null, "Revisiting the same item must retain the global cooldown");
        check(p.poll(64000)!=null, "Revisiting the same item must not require thirty minutes");
        p=enabled(true,false); a=apparel(p,garment(100),0); p.dismiss(a,4500);
        check(!p.accept(a,4600), "Dismissed offer accepted");
        p.observe(null,5000); p.observe(garment(101),6000);
        request=p.poll(10000);
        check(request!=null, "An unshown dismissal must not suppress a revisited item");
        p.resolveClassification(request, ProductTypeAnswer.Result.NON_APPAREL,10001);
        p.observe(RecordedLiveNodes.page("Another Host","Blue Dress",102),11000);
        check(p.poll(15000)!=null, "Unpublished dismissal started global cooldown");
        p=enabled(true,false); a=apparel(p,garment(100),0); p.published(a,4000); p.invalidate();
        check(!p.isCurrent(a,5000) && p.currentPage()==null, "Lock/stop retained usable ticket");
        p.observe(garment(100),60000); check(p.poll(64000)!=null, "Revisit after cooldown must not retain old item history");

        for(String host:new String[]{"Cindy Clothing","Daily Talks","Coffee Shop","fitwearbids"}) {
            p=enabled(false,true); p.observe(room(host,100),0);
            check(p.poll(7999)==null, "Visual dwell too short");
            var vision=p.poll(8000);
            check(vision!=null && vision.kind()==ObservationPolicy.Kind.VISION && p.visionInFlight(), "Host words controlled visual eligibility");
            check(p.resolveClassification(vision,ProductTypeAnswer.Result.APPAREL,9000)==null && p.modelInFlight(), "Wrong resolver released image lease");
            a=p.resolveVision(vision,true,20000);
            check(a!=null && a.kind()==ObservationPolicy.Kind.TEXT && a.createdAt()==20000, "Positive image result lost fresh offer TTL");
            check(p.resolveVision(vision,true,21000)==null, "Duplicate image callback created two offers");
        }
        p=enabled(true,true); p.observe(room("Daily Talks",100),0); var vision=p.poll(8000);
        p.observe(garment(101),9000); check(p.poll(13000)==null && p.visionInFlight(), "Text overlapped old image request");
        check(p.resolveVision(vision,true,14000)==null && !p.modelInFlight(), "Old room image created offer after card change");
        check(p.poll(14001).kind()==ObservationPolicy.Kind.CLASSIFY, "Image completion did not release shared lease for text");
        p=enabled(true,true); request=classify(p,garment(100),0); p.observe(room("Another Host",101),5000);
        check(p.poll(13000)==null && p.classificationInFlight(), "Image overlapped old text request");
        p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,14000);
        check(p.poll(14001).kind()==ObservationPolicy.Kind.VISION, "Text completion did not release shared lease for image");
        p=enabled(false,true); p.observe(room("Daily Talks",100),0); vision=p.poll(8000);
        check(p.resolveVision(vision,false,9000)==null, "Negative image offered");
        p.observe(null,10000); p.observe(room("Daily Talks",101),70000);
        check(p.poll(78000)==null, "Same visual room bypassed dedup by window recreation");
        p.observe(room("Another Host",102),80000); check(p.poll(88000)!=null, "New visual room never became eligible");
        p=enabled(false,true); p.observe(room("Daily Talks",100),0); vision=p.poll(8000);
        p.resolveVision(vision,false,9000); p.observe(room("Another Host",101),10000);
        check(p.poll(18000)==null && p.poll(68000)!=null, "Visual interval changed");
        p=enabled(false,true); p.observe(room("Daily Talks",100),0); vision=p.poll(8000);
        p.configure(true,true,false,9000);
        check(!p.isCurrent(vision,9000) && p.resolveVision(vision,true,10000)==null, "Text consent preserved revoked image work");
        p=enabled(false,true); p.observe(room("Daily Talks",100),0); vision=p.poll(8000);
        check(p.resolveVision(vision,true,38000)==null && !p.modelInFlight(), "Expired image result offered");
        p=enabled(true,false); request=classify(p,garment(100),0); p.configure(false,true,false,5000);
        check(!p.isCurrent(request,5000) && p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,6000)==null, "Disabled observer used old result");
        check(p.poll(100000)==null && !p.isCurrent(request,3999), "Disabled/backwards timestamp accepted");
        try { new ObservationPolicy.Config(4000,3000,60000,1800000,15000,60000,30000); throw new AssertionError("Bad config accepted"); }
        catch(IllegalArgumentException expected) { assertions++; }
        presenterOfferSurvivesCardRotation();
        explicitAcceptedRetry();
        repeatedReminderPolicy();
        System.out.println(assertions+" shopping policy assertions passed");
    }
    private static ObservationPolicy.Candidate accepted(ObservationPolicy p) {
        var offer=apparel(p,garment(100),0);
        check(p.published(offer,4001)&&p.accept(offer,4500),"retry fixture requires actual presentation and acceptance");
        return offer;
    }
    private static void explicitAcceptedRetry() {
        var p=enabled(true,true);var original=accepted(p);
        check(p.poll(4501)==null&&!p.modelInFlight(),"failure does not automatically republish or start a model");
        var copy=new ObservationPolicy.Candidate(original.kind(),original.generation(),original.page(),original.createdAt());
        check(p.retryAccepted(copy,4600)==null,"a copied accepted record is not an accepted capability");
        var retry=p.retryAccepted(original,4600);
        check(retry!=null&&retry!=original&&retry.kind()==ObservationPolicy.Kind.TEXT&&retry.createdAt()==4600
            &&retry.generation()==original.generation(),"explicit retry creates one fresh consent offer despite ordinary cooldown");
        check(p.retryAccepted(original,4601)==null&&p.retryAccepted(retry,4601)==null,"double-click and unaccepted new consent cannot rearm retry");
        check(!p.published(original,4601)&&!p.accept(original,4601),"old presentation and acceptance callbacks cannot apply to new consent");
        check(!p.accept(retry,4601)&&!p.modelInFlight(),"fresh retry still requires a real presentation ACK and starts no model");
        check(p.published(retry,4602)&&p.accept(retry,4603),"fresh retry can be presented and accepted exactly once");
        check(p.retryAccepted(original,4604)==null&&p.retryAccepted(retry,4604)!=null,"only a genuinely newly accepted offer may retry another failed attempt");

        p=enabled(true,true);original=accepted(p);long generation=p.generation();
        for(LivePage next:new LivePage[]{RecordedLiveNodes.page("Highland Fashion","Red Hoodie",100),
                room("Highland Fashion",100),RecordedLiveNodes.page("Highland Fashion","Blue Blouse",100)}) {
            check(!p.observe(next,70000)&&p.generation()==generation,"same-room card churn preserves a bounded accepted retry capability");
            check(p.poll(70001)==null&&!p.modelInFlight(),"card churn cannot trigger an automatic accepted retry");
        }
        retry=p.retryAccepted(original,70002);
        check(retry!=null&&retry.page().equals(p.currentPage()),"explicit retry uses the current same-room page after the old offer TTL elapsed");

        for(LivePage next:new LivePage[]{room("Another Host",100),room("Highland Fashion",101),
                LivePage.of("com.ss.android.ugc.trill",100,"Highland Fashion",""),null}) {
            p=enabled(true,true);original=accepted(p);
            check(p.observe(next,4600)&&p.retryAccepted(original,4601)==null,"leaving the exact room, package or window invalidates explicit retry");
            p.observe(garment(100),4700);
            check(p.retryAccepted(original,4701)==null,"returning to the room cannot restore an invalidated accepted capability");
        }
        p=enabled(true,true);original=accepted(p);p.dismiss(original,4600);
        check(p.retryAccepted(original,4601)==null,"cancelling an accepted failure control clears retry");
        p=enabled(true,true);original=accepted(p);p.invalidate();p.observe(garment(100),4600);
        check(p.retryAccepted(original,4601)==null,"explicit invalidation cannot preserve accepted retry");
        p=enabled(true,true);original=accepted(p);p.configure(false,true,true,4600);
        check(p.retryAccepted(original,4601)==null,"disabled observation cannot republish accepted consent");
        p=enabled(true,true);original=apparel(p,garment(100),0);
        check(p.retryAccepted(original,4500)==null,"an unpublished/unaccepted offer has no failure-retry capability");
        check(p.published(original,4501)&&p.retryAccepted(original,4502)==null,"presentation alone is not user acceptance");
        p.dismiss(original,4503);
        check(p.retryAccepted(original,4504)==null,"dismissed pending consent cannot be retried as accepted");
        check(p.retryAccepted(null,4505)==null,"missing capability cannot retry");

        p=enabled(true,true);original=accepted(p);
        check(p.retryAccepted(original,4500+ObservationPolicy.ACCEPTED_RETRY_TTL_MS-1)!=null,"retry remains valid immediately before the acceptance deadline");
        p=enabled(true,true);original=accepted(p);
        check(p.retryAccepted(original,4500+ObservationPolicy.ACCEPTED_RETRY_TTL_MS)==null,"exact two-minute boundary expires accepted retry");
        check(p.retryAccepted(original,5000)==null,"an expired accepted capability cannot resurrect after clock rollback");
        p=enabled(true,true);original=accepted(p);
        check(p.retryAccepted(original,4499)==null&&p.retryAccepted(original,4600)==null,"clock rollback before acceptance retires the capability");
        p=enabled(true,true);original=accepted(p);
        p.observe(RecordedLiveNodes.page("Highland Fashion","Late card",100),4500+ObservationPolicy.ACCEPTED_RETRY_TTL_MS);
        check(p.retryAccepted(original,4500+ObservationPolicy.ACCEPTED_RETRY_TTL_MS)==null,"expired retry cannot pin the room generation through another card");

        p=enabled(true,true);original=accepted(p);retry=p.retryAccepted(original,4600);p.dismiss(retry,4601);
        p.observe(null,4700);p.observe(garment(101),70000);
        var request=p.poll(74000);
        check(request!=null,"a revisited failed item can be offered after the ordinary cooldown");
        p.resolveClassification(request,ProductTypeAnswer.Result.NON_APPAREL,74001);
        p.observe(RecordedLiveNodes.page("Another Host","Red Hoodie",102),75000);
        check(p.poll(79000)!=null,"explicit retry does not suppress unrelated eligible products");
    }
    private static void repeatedReminderPolicy() {
        // Acceptance, dismissal and an ignored offer all stay single-shot on the unchanged page.
        for (int outcome=0;outcome<3;outcome++) {
            var p=enabled(true,false);
            var offer=apparel(p,garment(100),0);
            check(p.published(offer,4000),"repeat fixture requires a real presentation ACK");
            if(outcome==0) check(p.accept(offer,4500),"accept repeat fixture");
            if(outcome==1) p.dismiss(offer,4500);
            check(!p.observe(garment(100),64000) && p.poll(64000)==null
                &&p.poll(1804000)==null,"the same page must not cycle reminders when cooldown or old dedup would expire");
            p.observe(null,1805000);p.observe(garment(100),1806000);
            check(p.poll(1810000)!=null,"a genuine revisit restores ordinary eligibility for every prior outcome");
        }

        // User controls do not move the real-ACK clock or retain a per-item block.
        for(boolean accept:new boolean[]{false,true}) {
            var p=enabled(true,false);var offer=apparel(p,garment(100),0);
            p.published(offer,4000);
            if(accept) check(p.accept(offer,50000),"late acceptance remains within offer TTL");
            else p.dismiss(offer,50000);
            p.observe(null,50001);p.observe(garment(101),50002);
            check(p.poll(63999)==null,"accept or dismiss must not bypass the real-ACK cooldown");
            check(p.poll(64000)!=null,"accept or dismiss must not restart or extend the real-ACK cooldown");
            check(!p.published(offer,64001)&&!p.accept(offer,64001),"old callbacks cannot consume or refresh a later attempt");
        }

        // The no-card visual cost limit is independent of reminder history and remains unchanged.
        var p=enabled(false,true);p.observe(room("Daily Talks",100),0);
        var vision=p.poll(8000);p.resolveVision(vision,false,9000);
        p.observe(null,10000);p.observe(room("Daily Talks",101),70000);
        check(p.poll(1807999)==null,"no-card repeated image analysis must still wait thirty minutes from its attempt");
        check(p.poll(1808000)!=null,"the independent visual attempt limit must expire at thirty minutes");
    }
    private static void presenterOfferSurvivesCardRotation() {
        var p=enabled(true,true);
        var offer=apparel(p,garment(100),0);
        long generation=offer.generation();
        check(p.config().offerTtlMs()==60000,"presenter offer defaults to sixty seconds");
        check(p.published(offer,4001),"real presentation ACK binds the original candidate revision");
        for(LivePage rotated:new LivePage[]{RecordedLiveNodes.page("Highland Fashion","Red Hoodie",100),
                room("Highland Fashion",100),RecordedLiveNodes.page("Highland Fashion","New arrival blouse",100)}) {
            check(!p.observe(rotated,5000),"automatic card rotation or disappearance does not retract a presenter offer");
            check(p.generation()==generation&&p.currentPage().equals(rotated),"room remains on the original revision while its current card updates");
            check(p.isCurrent(offer,5001)&&p.poll(5001)==null,"the original TEXT offer remains current without a second classification");
        }
        check(p.accept(offer,6000)&&!p.accept(offer,6001),"the original room-bound revision can still be accepted exactly once");

        p=enabled(true,true);offer=apparel(p,garment(100),0);
        check(!p.observe(room("Highland Fashion",100),4500),"an offer awaiting presentation ACK is also room-bound");
        check(p.published(offer,4600)&&p.accept(offer,4700),"card disappearance before ACK does not strand an accepted Surface revision");

        for(LivePage other:new LivePage[]{room("Another Host",100),room("Highland Fashion",101),
                LivePage.of("com.ss.android.ugc.trill",100,"Highland Fashion",""),null}) {
            p=enabled(true,true);offer=apparel(p,garment(100),0);p.published(offer,4000);
            check(p.observe(other,5000)&&!p.isCurrent(offer,5000)&&!p.accept(offer,5001),"different room/package/window or leaving the LIVE invalidates the offer");
        }
        p=enabled(true,true);offer=apparel(p,garment(100),0);p.published(offer,4000);
        p.observe(room("Highland Fashion",100),63999);
        check(p.isCurrent(offer,63999)&&!p.isCurrent(offer,64000)&&!p.accept(offer,64000),"same-room updates cannot renew the sixty-second deadline");
        check(p.observe(RecordedLiveNodes.page("Highland Fashion","Late card",100),64001)
            &&!p.isCurrent(offer,64001),"an expired offer cannot pin a later card to its old revision");
        p=enabled(true,true);offer=apparel(p,garment(100),0);p.published(offer,4000);p.dismiss(offer,4500);
        p.observe(room("Highland Fashion",100),5000);
        check(!p.accept(offer,5001),"dismissal still consumes the pending offer across card updates");
        p=enabled(true,true);offer=apparel(p,garment(100),0);p.published(offer,4000);p.configure(false,true,true,4500);
        check(!p.isCurrent(offer,4501)&&!p.accept(offer,4501),"disabling observation still invalidates a room-bound offer");

        p=enabled(true,true);var request=classify(p,garment(100),0);generation=p.generation();
        check(p.observe(RecordedLiveNodes.page("Highland Fashion","Red Hoodie",100),4500)
            &&p.generation()!=generation&&!p.isCurrent(request,4501),"unshown classification remains bound to the exact card");
        check(p.resolveClassification(request,ProductTypeAnswer.Result.APPAREL,5000)==null&&p.classificationResult()==null,
            "old classification cannot create a presenter offer for an automatically rotated card");
        p=enabled(true,true);p.observe(room("Highland Fashion",100),0);var vision=p.poll(8000);generation=p.generation();
        check(p.observe(garment(100),8500)&&p.generation()!=generation&&!p.isCurrent(vision,8501),"an in-flight visual request does not get TEXT room semantics");
        check(p.resolveVision(vision,true,9000)==null,"old visual result cannot cross a card appearance even within the same room");
    }
}
