// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/**
 * A short continuity allowance while a user-requested model call is running.
 * WAIT is never proof of the room and must never authorize capture or handoff.
 * Callers retain their strict fresh-page checks at those two boundaries.
 */
public final class LiveFrameContinuity {
    public static final long MISSING_GRACE_MS=1500;
    public enum Result { VERIFIED, WAIT, CANCEL }
    private final LivePage source;
    private final long startedAt,deadlineAt;
    private long lastObservedAt,missingSince=-1;
    private boolean cancelled;

    public LiveFrameContinuity(LivePage source,long startedAt,long deadlineAt) {
        if(source==null||startedAt<0||deadlineAt<=startedAt
                ||deadlineAt-startedAt>LiveFrameCapturePolicy.DEADLINE_MS)
            throw new IllegalArgumentException("Invalid live continuity window");
        this.source=source;this.startedAt=startedAt;this.deadlineAt=deadlineAt;lastObservedAt=startedAt;
    }

    /** Metadata is the sole app root's identity, not an inferred foreground package. */
    public synchronized Result observe(LivePage page,String reason,int windowId,String packageName,long now) {
        if(cancelled)return Result.CANCEL;
        if(now<startedAt||now<lastObservedAt||now>=deadlineAt)return cancel();
        lastObservedAt=now;
        if(page!=null) {
            if(!"live_room".equals(reason)||!sameWindow(page.windowId(),page.packageName())
                    ||!sameWindow(windowId,packageName)||!source.hostKey().equals(page.hostKey()))return cancel();
            missingSince=-1;
            return Result.VERIFIED;
        }
        if(!sameWindow(windowId,packageName)
                ||!("not_live_room".equals(reason)||"app_not_focused".equals(reason)))return cancel();
        if(missingSince<0)missingSince=now;
        return now-missingSince<MISSING_GRACE_MS?Result.WAIT:cancel();
    }
    private boolean sameWindow(int windowId,String packageName) {
        return source.windowId()==windowId&&source.packageName().equals(packageName);
    }
    private Result cancel(){cancelled=true;return Result.CANCEL;}
}
