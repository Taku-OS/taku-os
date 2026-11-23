// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** Coalesces passive LIVE reads only. Never represents source verification or authorizes an action. */
public final class LiveFrameReadThrottle {
    public static final long INTERVAL_MS=700;
    public enum Decision { READ, SKIP, CANCEL }
    private long lastReadAt=-1;
    public void reset(){lastReadAt=-1;}
    public void readCompleted(long now){lastReadAt=now;}
    public Decision passive(long now,boolean enabled,boolean unlocked,boolean active,boolean windowChanged) {
        if(!enabled||!unlocked||!active)return Decision.CANCEL;
        return windowChanged||lastReadAt<0||now<lastReadAt||now-lastReadAt>=INTERVAL_MS?Decision.READ:Decision.SKIP;
    }
}
