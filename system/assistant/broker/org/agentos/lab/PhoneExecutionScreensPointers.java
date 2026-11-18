package org.agentos.lab;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;

/** Pointer ownership is independent across execution screens, including reused Android display ids. */
final class PhoneExecutionScreensPointers {
    private final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();
    private CleanupWork.Held lock() { return CleanupWork.lock(stateLock); }
    boolean lockHeld() { return stateLock.isHeldByCurrentThread(); }

    interface Live { boolean current(PhoneExecutionScreens.Screen screen)throws Exception; }
    private static final class Entry {
        final PhoneExecutionScreens.Screen screen;PhoneGestureSession pointer;volatile boolean retired;
        Entry(PhoneExecutionScreens.Screen screen){this.screen=screen;}
    }
    private final Map<Integer,Entry> entries=new HashMap<>();
    private final Set<String> activeOwners=new HashSet<>();
    private final LongSupplier clock;
    private final PhoneGestureSession.Sink sink;
    private final Live live;
    PhoneExecutionScreensPointers(LongSupplier clock,PhoneGestureSession.Sink sink,Live live){this.clock=clock;this.sink=sink;this.live=live;}
    void prepare(PhoneExecutionScreens.Screen screen)throws Exception { try (CleanupWork.Held held1 = lock()) {
        if(screen.displayId<=0)throw new IOException("SCREEN_DISPLAY_REQUIRED");
        Entry previous=entries.get(screen.displayId);
        if(previous!=null&&!previous.retired&&previous.screen.screenKey.equals(screen.screenKey)&&previous.screen.generation.equals(screen.generation))return;
        // A delayed timeout/cancellation from the old host must never reach a replacement display.
        if(previous!=null){previous.retired=true;previous.pointer.close();}
        Entry entry=new Entry(screen);
        PhoneGestureSession pointer=new PhoneGestureSession(clock,(display,downAt,at,phase,x,y,pressure)->{
            if(entry.retired){if(phase.equals("CANCEL"))return;throw new IOException("SCREEN_GENERATION_CHANGED");}
            boolean current;
            try{current=live.current(screen);}catch(Exception error){
                if(CleanupWork.active()){
                    CleanupWork.rethrowTimeout(error);
                    throw new IOException("SCREEN_GENERATION_UNVERIFIED",error);
                }
                entry.retired=true;
                if(phase.equals("CANCEL"))return;
                throw new IOException("SCREEN_GENERATION_UNVERIFIED",error);
            }
            if(!current){
                entry.retired=true;
                if(phase.equals("CANCEL"))return;
                throw new IOException("SCREEN_GENERATION_CHANGED");
            }
            sink.emit(display,downAt,at,phase,x,y,pressure);
        });
        entry.pointer=pointer;
        for(String owner:activeOwners)pointer.startOwner(owner);
        entries.put(screen.displayId,entry);
    } }
    PhoneGestureSession pointer(int display)throws IOException { try (CleanupWork.Held held2 = lock()) {
        Entry entry=entries.get(display);if(entry==null)throw new IOException("SCREEN_GESTURE_NOT_PREPARED");return entry.pointer;
    } }
    PhoneExecutionScreens.Screen screen(int display)throws IOException { try (CleanupWork.Held held3 = lock()) {
        Entry entry=entries.get(display);if(entry==null||entry.retired)throw new IOException("SCREEN_GESTURE_NOT_PREPARED");return entry.screen;
    } }
    List<PhoneGestureSession> snapshot(){ try (CleanupWork.Held held4 = lock()) {List<PhoneGestureSession> result=new ArrayList<>();for(Entry entry:entries.values())result.add(entry.pointer);return result;} }
    void startOwner(String owner){ try (CleanupWork.Held held5 = lock()) {activeOwners.add(owner);for(Entry entry:entries.values())entry.pointer.startOwner(owner);} }
    void endOwner(String owner)throws Exception { try (CleanupWork.Held held6 = lock()) {activeOwners.remove(owner);for(Entry entry:entries.values())entry.pointer.endOwner(owner);} }
    void close()throws Exception { try (CleanupWork.Held held7 = lock()) {activeOwners.clear();Exception first=null;for(Entry entry:entries.values())try{entry.pointer.close();}catch(Exception error){if(first==null)first=error;else first.addSuppressed(error);}if(first!=null)throw first;} }
}
