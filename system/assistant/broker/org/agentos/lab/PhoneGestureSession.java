package org.agentos.lab;

import java.io.IOException;
import java.util.function.LongSupplier;
import java.util.HashSet;
import java.util.Set;

/** One real pointer stream, owned by one native turn and bounded by inactivity. */
final class PhoneGestureSession {
    private final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();
    private CleanupWork.Held lock() { return CleanupWork.lock(stateLock); }
    boolean lockHeld() { return stateLock.isHeldByCurrentThread(); }

    static final long HOLD_MS=15000;
    interface Sink { void emit(int display,long downAt,long at,String phase,int x,int y,float pressure) throws Exception; }
    record State(String owner,int display,int x,int y,long downAt,long expiresAt) {}
    private final LongSupplier clock;
    private final Sink sink;
    private final Set<String> activeOwners=new HashSet<>();
    private final Set<String> endedOwners=new HashSet<>();
    private State held;
    PhoneGestureSession(LongSupplier clock,Sink sink) { this.clock=clock;this.sink=sink; }
    static void coordinates(int x,int y) throws IOException {
        if(x<0 || x>=720 || y<0 || y>=1280)throw new IOException("Coordinates are outside the independent screen");
    }
    State state() { try (CleanupWork.Held held1 = lock()) { return held; } }
    void startOwner(String owner) { try (CleanupWork.Held held2 = lock()) { if(owner==null || owner.isBlank())throw new IllegalArgumentException("Missing owner");if(!endedOwners.contains(owner))activeOwners.add(owner); } }
    void endOwner(String owner) throws Exception { try (CleanupWork.Held held3 = lock()) { endedOwners.add(owner);activeOwners.remove(owner);cancelOwner(owner); } }
    void close() throws Exception { try (CleanupWork.Held held4 = lock()) { endedOwners.addAll(activeOwners);activeOwners.clear();cancel(); } }
    void touch(String owner,int display,String phase,int x,int y) throws Exception { try (CleanupWork.Held held5 = lock()) {
        coordinates(x,y);
        if(owner==null || owner.isBlank() || display<=0)throw new IOException("The gesture has no execution owner");
        if(!activeOwners.contains(owner))throw new IOException("The execution turn has ended; the late gesture was rejected");
        if(!phase.matches("DOWN|MOVE|UP|CANCEL"))throw new IOException("Unknown touch phase");
        expire();
        long now=clock.getAsLong();
        if(phase.equals("DOWN")) {
            if(held!=null)throw new IOException("A gesture is already held. Release or cancel it first");
            held=new State(owner,display,x,y,now,now+HOLD_MS);
        } else if(held==null || !held.owner.equals(owner) || held.display!=display) {
            throw new IOException("This turn has no valid held gesture. Observe the screen again");
        }
        State previous=held;
        try { sink.emit(display,previous.downAt,now,phase,x,y,phase.equals("UP")||phase.equals("CANCEL")?0f:1f); }
        catch(Exception error) {
            // An uncertain injection must never allow a second turn to acquire this pointer.
            held=previous;
            try { cancel(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        held=phase.equals("UP")||phase.equals("CANCEL")?null:new State(owner,display,x,y,previous.downAt,now+HOLD_MS);
    } }
    void requireIdle() throws Exception { try (CleanupWork.Held held6 = lock()) { expire();if(held!=null)throw new IOException("Release or cancel the current gesture first"); } }
    void expire() throws Exception { try (CleanupWork.Held held7 = lock()) { if(held!=null && clock.getAsLong()>=held.expiresAt)cancel(); } }
    void cancelThread(String thread) throws Exception { try (CleanupWork.Held held8 = lock()) {
        if(held!=null && held.owner.startsWith(thread+"\n"))cancel();
    } }
    void cancelOwner(String owner) throws Exception { try (CleanupWork.Held held9 = lock()) { if(held!=null && held.owner.equals(owner))cancel(); } }
    void cancel() throws Exception { try (CleanupWork.Held held10 = lock()) {
        if(held==null)return;
        State active=held;
        sink.emit(active.display,active.downAt,clock.getAsLong(),"CANCEL",active.x,active.y,0f);
        held=null;
    } }
}
