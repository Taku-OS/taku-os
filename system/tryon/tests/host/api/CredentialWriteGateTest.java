// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.api;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Production lease admission with fake monotonic time and a real expiry/commit race. */
public final class CredentialWriteGateTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
    public static void main(String[] args)throws Exception{
        CredentialWriteGate gate=new CredentialWriteGate();
        check(gate.acquire(-1)==null,"negative input clock rejected");
        check(gate.acquire(Long.MAX_VALUE-CredentialWriteGate.TIMEOUT_MS+1)==null,"deadline overflow rejected");
        check(!gate.reading(null)&&!gate.beginCommit(null,1000),"missing lease never owns a write");
        CredentialWriteGate.Lease first=gate.acquire(1000);
        check(first!=null&&first.deadline==31000,"one 30-second input lease");
        check(gate.acquire(1001)==null,"second credential input rejected while occupied");
        check(!gate.expire(first,30999),"early timeout cannot cancel current input");
        check(!gate.beginCommit(first,31000),"EOF at deadline cannot authorize a credential mutation");
        check(gate.expire(first,31000)&&!gate.reading(first),"timeout invalidates input before closure");
        check(gate.acquire(31001)==null,"descriptor closure still owns gate until release");
        check(!gate.beginCommit(first,31001),"timed-out reader never commits late");
        gate.release(first);CredentialWriteGate.Lease second=gate.acquire(31001);
        check(second!=null&&second.generation>first.generation,"new input receives a distinct generation");
        gate.release(first);
        check(gate.reading(second)&&gate.acquire(31002)==null,"old finalizer cannot clear new generation");
        check(!gate.expire(first,62000),"old timeout cannot cancel new input");
        check(gate.beginCommit(second,31002),"complete current input can enter authoritative storage");
        check(!gate.beginCommit(second,31003),"one storage admission per input");
        check(!gate.expire(second,62000)&&gate.acquire(62000)==null,"an admitted synchronous commit retains exclusive ownership");
        gate.release(second);check(gate.acquire(62001)!=null,"storage completion releases capacity");

        CredentialWriteGate closed=new CredentialWriteGate();CredentialWriteGate.Lease pending=closed.acquire(1000);closed.shutdown();
        check(!closed.reading(pending)&&!closed.beginCommit(pending,1001),"shutdown invalidates pending reader");
        closed.release(pending);check(closed.acquire(1002)==null,"shutdown provider cannot reopen a new writer");
        CredentialWriteGate committing=new CredentialWriteGate();CredentialWriteGate.Lease owned=committing.acquire(1000);
        check(committing.beginCommit(owned,1001),"commit lease established before shutdown");
        committing.shutdown();committing.release(owned);check(committing.acquire(1002)==null,"destroy never makes room for another write");

        for(int i=0;i<100;i++){
            CredentialWriteGate racing=new CredentialWriteGate();CredentialWriteGate.Lease lease=racing.acquire(1000);
            CountDownLatch start=new CountDownLatch(1);AtomicBoolean commit=new AtomicBoolean(),expired=new AtomicBoolean();
            Thread writer=new Thread(()->{await(start);commit.set(racing.beginCommit(lease,30999));});
            Thread timer=new Thread(()->{await(start);expired.set(racing.expire(lease,31000));});
            writer.start();timer.start();start.countDown();writer.join();timer.join();
            check(commit.get()!=expired.get(),"commit/timeout race has exactly one winner");
            if(expired.get())check(!racing.beginCommit(lease,31001),"expired race loser cannot mutate a key");
            check(racing.acquire(31001)==null,"race retains gate until descriptor/storage cleanup");
            racing.release(lease);CredentialWriteGate.Lease fresh=racing.acquire(31002);racing.release(lease);
            check(fresh!=null&&racing.reading(fresh),"race loser cleanup cannot invalidate fresh writer");
        }
        System.out.println("CredentialWriteGateTest: "+checks+" checks passed");
    }
}
