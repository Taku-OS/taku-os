// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Metadata may preserve an existing hint; it never verifies a page or authorizes capture. */
public final class OfferOcclusionGuard {
    public record Evidence(int windowId,String packageName,boolean focusedFullscreen,
            boolean sameRoomAnchors,int ownHints,boolean unknownOverlay) {}
    public enum Acceptance { WAIT, READY, REJECT }
    private OfferOcclusionGuard() {}
    public static boolean retain(ObservationPolicy.Candidate offer,boolean current,String reason,
            boolean verifiedPage,Evidence evidence,long now,long ttlMs) {
        return !verifiedPage && "not_live_room".equals(reason)
            && matches(offer,current,evidence,now,ttlMs) && evidence.ownHints()==1;
    }
    /** A real accept may wait briefly for detachment/cache recovery, but only a fresh page is READY. */
    public static Acceptance acceptance(ObservationPolicy.Candidate offer,boolean current,String reason,
            boolean verifiedPage,Evidence evidence,long now,long deadline,long ttlMs) {
        if(now>=deadline || !fresh(offer,current,now,ttlMs))return Acceptance.REJECT;
        // A normal verified LIVE read is authoritative, just as in the original accept path.
        // Optional hint metadata may delay a known attached hint; it cannot veto that read.
        // READY still requires beginExplicit's independent strict read and policy.accept.
        if(verifiedPage && "live_room".equals(reason))
            return matches(offer,current,evidence,now,ttlMs) && evidence.ownHints()==1
                ? Acceptance.WAIT : Acceptance.READY;
        if(!"not_live_room".equals(reason) || !matches(offer,current,evidence,now,ttlMs))return Acceptance.REJECT;
        return Acceptance.WAIT;
    }
    private static boolean fresh(ObservationPolicy.Candidate offer,boolean current,long now,long ttlMs) {
        return offer!=null && offer.kind()==ObservationPolicy.Kind.TEXT && current && offer.page()!=null
            && ttlMs>0 && now>=offer.createdAt() && now-offer.createdAt()<ttlMs;
    }
    private static boolean matches(ObservationPolicy.Candidate offer,boolean current,Evidence evidence,long now,long ttlMs) {
        if(!fresh(offer,current,now,ttlMs) || evidence==null)return false;
        LivePage source=offer.page();
        return source!=null && evidence.windowId()==source.windowId()
            && source.packageName().equals(evidence.packageName()) && evidence.focusedFullscreen()
            && evidence.sameRoomAnchors() && evidence.ownHints()>=0 && evidence.ownHints()<=1 && !evidence.unknownOverlay();
    }
}
