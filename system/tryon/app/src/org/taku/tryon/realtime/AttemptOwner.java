// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** One attempt's preparation and cleanup handshake. Fast start/stop calls linearize under one lock. */
final class AttemptOwner<T> {
    interface Stop<T> { void stop(T value); }
    static final class Task { private boolean done; }
    private final Stop<T> stop;
    private int tasks;
    private boolean closing,cameraClosed=true,cloudClosed=true;
    private T resource;
    private Runnable cancellation;
    AttemptOwner(Stop<T> stop){this.stop=stop;}
    synchronized Task queue(){if(closing)return null;tasks++;return new Task();}
    synchronized boolean begin(Task task){return task!=null&&!task.done&&!closing;}
    synchronized void done(Task task){if(task!=null&&!task.done){task.done=true;tasks--;}}
    synchronized boolean cancellation(Runnable value){
        if(closing){value.run();return false;}
        cancellation=value;return true;
    }
    synchronized boolean start(T value,Runnable start){
        if(resource!=null)throw new IllegalStateException("transport_already_owned");
        resource=value;cloudClosed=false;
        if(closing){stop.stop(value);return false;}
        start.run();return true;
    }
    synchronized void cameraOpening(){if(closing)throw new IllegalStateException("attempt_closed");cameraClosed=false;}
    synchronized void cameraClosed(){cameraClosed=true;}
    synchronized void cloudClosed(boolean confirmed){if(resource!=null)cloudClosed=confirmed;}
    synchronized void close(){
        if(closing)return;closing=true;
        if(cancellation!=null)cancellation.run();
        if(resource!=null)stop.stop(resource);
    }
    synchronized boolean closed(){return closing&&tasks==0&&cameraClosed&&cloudClosed;}
}
