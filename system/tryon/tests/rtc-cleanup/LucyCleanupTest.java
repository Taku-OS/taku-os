// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.Context;
import android.os.Handler;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
/** Actual Lucy adapter terminate path with deterministic Android actor fixtures; never drains startup/JNI/network. */
public final class LucyCleanupTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        for(FalRealtimeProfile profile:FalRealtimeProfile.values()){
            fixture(profile,0);fixture(profile,1);fixture(profile,2);
        }
        System.out.println("LucyCleanupTest: "+checks+" actual RTC adapter cleanup/gate checks passed; fake actors, no JNI/network/model");
    }
    static void fixture(FalRealtimeProfile profile,int kind)throws Exception{
        AttemptOwner<RealtimeCallTransport> owner=new AttemptOwner<>(RealtimeCallTransport::stop);
        AtomicInteger cleanups=new AtomicInteger(),stale=new AtomicInteger();
        CallTryOnRtc rtc=new CallTryOnRtc(new Context(),new CallTryOnRtc.Listener(){
            public void onState(String s){}
            public void onRemoteFrame(byte[] jpeg,int w,int h){throw new AssertionError();}
            public void onFailure(String code){}
            public void onCleanup(boolean confirmed){check(confirmed);cleanups.incrementAndGet();owner.cloudClosed(confirmed);}
        },profile);
        Field field=CallTryOnRtc.class.getDeclaredField("control");field.setAccessible(true);Handler actor=(Handler)field.get(rtc);
        actor.post(stale::incrementAndGet);
        owner.start(rtc,()->{if(kind==1)rtc.start("synthetic-local-token","test prompt",null);if(kind==2)rtc.start("invalid token with spaces","test prompt",null);});
        owner.close();check(!owner.closed());check(cleanups.get()==0);
        actor.drain();check(owner.closed());check(cleanups.get()==1);check(stale.get()==0);
        rtc.stop();rtc.start("unused","test prompt",null);actor.drain();check(cleanups.get()==1);check(actor.hasQuit());
    }
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
}
