// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.api;

/** One bounded credential input. Old timeouts/finalizers never own a newer write. */
final class CredentialWriteGate {
    static final long TIMEOUT_MS=30_000;
    static final class Lease {
        final long generation,deadline;
        private boolean committing,cancelled;
        private Lease(long generation,long deadline){this.generation=generation;this.deadline=deadline;}
    }
    private long generation;
    private Lease current;
    private boolean closed;

    synchronized Lease acquire(long now){
        if(closed||current!=null||now<0||now>Long.MAX_VALUE-TIMEOUT_MS)return null;
        current=new Lease(++generation,now+TIMEOUT_MS);return current;
    }
    synchronized boolean reading(Lease lease){return lease!=null&&!closed&&current==lease&&!lease.cancelled&&!lease.committing;}
    /** Commit admission is atomic with expiry. Keep the gate until synchronous storage returns. */
    synchronized boolean beginCommit(Lease lease,long now){
        if(!reading(lease)||now<0||now>=lease.deadline)return false;
        lease.committing=true;return true;
    }
    /** Invalidate first; the owner closes descriptors before releasing this generation. */
    synchronized boolean expire(Lease lease,long now){
        if(!reading(lease)||now<lease.deadline)return false;
        lease.cancelled=true;return true;
    }
    synchronized void release(Lease lease){if(current==lease)current=null;}
    synchronized void shutdown(){closed=true;if(current!=null&&!current.committing)current.cancelled=true;}
}
