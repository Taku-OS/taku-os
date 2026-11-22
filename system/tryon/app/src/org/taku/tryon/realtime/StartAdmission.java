// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
/** Reserves one generation until its Service is destroyed, including a cancelled pending launch. */
final class StartAdmission {
    private long sequence,current;
    private boolean cancelled;
    synchronized long reserve(){if(current!=0)return 0;current=++sequence;cancelled=false;return current;}
    synchronized boolean busy(){return current!=0;}
    synchronized long current(){return current;}
    synchronized void cancel(){if(current!=0)cancelled=true;}
    synchronized boolean cancel(long generation){if(generation==0||current!=generation)return false;cancelled=true;return true;}
    synchronized boolean accepted(long generation){return generation!=0&&current==generation&&!cancelled;}
    synchronized void release(long generation){if(current==generation){current=0;cancelled=false;}}
}
