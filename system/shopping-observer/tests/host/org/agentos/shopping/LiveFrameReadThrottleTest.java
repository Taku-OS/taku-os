// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class LiveFrameReadThrottleTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static LiveFrameReadThrottle.Decision passive(LiveFrameReadThrottle gate,long now,boolean urgent) {
        return gate.passive(now,true,true,true,urgent);
    }
    public static void main(String[] args) {
        var gate=new LiveFrameReadThrottle();
        check(passive(gate,1000,false)==LiveFrameReadThrottle.Decision.READ,"first passive observation must read");
        gate.readCompleted(1000);
        int reads=1;
        // A content callback, including refresh(true), and a tick share one cooldown.
        for(long now=1001;now<=3000;now++)if(passive(gate,now,false)==LiveFrameReadThrottle.Decision.READ){reads++;gate.readCompleted(now);}
        check(reads==3,"two thousand comment/tick callbacks do not add two thousand full snapshots");
        check(passive(gate,3001,true)==LiveFrameReadThrottle.Decision.READ,"a window change reads immediately inside cooldown");
        gate.readCompleted(3001);
        check(passive(gate,3002,false)==LiveFrameReadThrottle.Decision.SKIP,"window urgency does not permanently disable comment throttling");
        check(passive(gate,3700,false)==LiveFrameReadThrottle.Decision.SKIP,"cooldown uses completed read time");
        check(passive(gate,3701,false)==LiveFrameReadThrottle.Decision.READ,"exact 700ms boundary allows a passive read");
        // Strong capture/handoff reads record completion but never ask the passive scheduler for permission.
        gate.readCompleted(3800);
        check(passive(gate,3801,false)==LiveFrameReadThrottle.Decision.SKIP,"a strong read also prevents an immediately redundant passive read");
        check(gate.passive(3801,false,true,true,false)==LiveFrameReadThrottle.Decision.CANCEL,"disabled state is immediate despite cooldown");
        check(gate.passive(3801,true,false,true,false)==LiveFrameReadThrottle.Decision.CANCEL,"lock state is immediate despite cooldown");
        LivePage page=LivePage.of("com.zhiliaoapp.musically",100,"Presenter","Shirt");
        var session=new LiveFrameCapturePolicy(page,1,1000);
        gate.readCompleted(20999);
        check(session.isActive(20999)&&passive(gate,20999,false)==LiveFrameReadThrottle.Decision.SKIP,"active session can skip redundant passive read before its deadline");
        check(gate.passive(21000,true,true,session.isActive(21000),false)==LiveFrameReadThrottle.Decision.CANCEL,"hard twenty-second deadline is never postponed to the next allowed snapshot");
        check(gate.passive(21001,true,true,session.isActive(21001),true)==LiveFrameReadThrottle.Decision.CANCEL,"structural urgency cannot resurrect an expired session");
        check(passive(gate,20000,false)==LiveFrameReadThrottle.Decision.READ,"clock rollback requires a fresh check rather than indefinite cooldown");
        gate.reset();
        check(passive(gate,21001,false)==LiveFrameReadThrottle.Decision.READ,"new explicit session does not inherit prior cooldown");
        System.out.println(assertions+" live frame read throttle assertions passed");
    }
}
