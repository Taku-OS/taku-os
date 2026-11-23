// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

/** One explicit Try on action, bounded sampling and analysis, one handoff; no product-card dependency. */
public final class LiveFrameCapturePolicy {
    public static final long DEADLINE_MS=20_000;
    public static final int MAX_SAMPLES=3;
    public enum Stage { WAITING_CAPTURE, CAPTURING, ANALYZING, WAITING_SELECTION, SELECTED_ANALYZING, CROPPING, HANDED_OFF, CANCELLED }
    private final LivePage source;
    private final long startedAt, deadlineAt;
    private final int pageRevision;
    private Stage stage=Stage.WAITING_CAPTURE;
    private int samplesCaptured;
    private boolean analysisClaimed, selectionAnalysisClaimed, samplingStopped;
    public LiveFrameCapturePolicy(LivePage source, int pageRevision, long now) {
        if (source == null || pageRevision < 0 || now < 0 || now > Long.MAX_VALUE - DEADLINE_MS)
            throw new IllegalArgumentException("Invalid live action");
        this.source = source; this.pageRevision = pageRevision; startedAt = now;
        deadlineAt = now + DEADLINE_MS;
    }
    public LivePage source(){return source;}
    public int pageRevision(){return pageRevision;}
    public long deadlineAt(){return deadlineAt;}
    public synchronized Stage stage(){return stage;}
    public synchronized int samplesCaptured(){return samplesCaptured;}
    public synchronized boolean selectionAnalysisClaimed(){return selectionAnalysisClaimed;}
    public synchronized boolean isActive(long now){
        if(stage==Stage.CANCELLED||stage==Stage.HANDED_OFF)return false;
        if(now<startedAt||now>=deadlineAt){stage=Stage.CANCELLED;return false;}return true;
    }
    /** A product-title, price or card visibility change does not change the live room. */
    public synchronized boolean observe(LivePage current,long now){
        if(!isActive(now))return false;
        if(current==null||source.windowId()!=current.windowId()||!source.packageName().equals(current.packageName())
                ||!source.hostKey().equals(current.hostKey())){cancel();return false;}return true;
    }
    /** The original entry point claims only the first source frame. */
    public synchronized boolean claimScreenshot(long now){if(!isActive(now)||stage!=Stage.WAITING_CAPTURE)return false;stage=Stage.CAPTURING;return true;}
    public synchronized boolean claimAdditionalScreenshot(long now){
        if(!isActive(now)||stage!=Stage.ANALYZING||analysisClaimed||samplingStopped||samplesCaptured<1||samplesCaptured>=MAX_SAMPLES)return false;
        stage=Stage.CAPTURING;return true;
    }
    /** A failed optional frame may fall back to the earlier image, but cannot trigger another sampling loop. */
    public synchronized boolean skipAdditionalScreenshot(long now){
        if(!isActive(now)||stage!=Stage.CAPTURING||samplesCaptured<1||analysisClaimed||selectionAnalysisClaimed)return false;
        samplingStopped=true;stage=Stage.ANALYZING;return true;
    }
    public synchronized boolean screenshotReady(long now){
        if(!isActive(now)||stage!=Stage.CAPTURING)return false;
        samplesCaptured++;stage=Stage.ANALYZING;return true;
    }
    public synchronized boolean claimAnalysis(long now){if(!isActive(now)||stage!=Stage.ANALYZING||analysisClaimed)return false;analysisClaimed=true;return true;}
    /** The caller must wait for the automatic request to finish before offering a selection. */
    public synchronized boolean waitForSelection(long now){
        if(!isActive(now)||stage!=Stage.ANALYZING||!analysisClaimed)return false;
        stage=Stage.WAITING_SELECTION;return true;
    }
    /** The service verifies the real user tap and callback identity; this only claims its bounded work. */
    public synchronized boolean claimSelectedAnalysis(int x,int y,long now){
        if(!isActive(now)||stage!=Stage.WAITING_SELECTION||selectionAnalysisClaimed||!validPoint(x,y))return false;
        selectionAnalysisClaimed=true;stage=Stage.SELECTED_ANALYZING;return true;
    }
    /** A new explicit attempt may continue a caller-verified frozen selection without claiming a new screenshot. */
    public synchronized boolean claimRetainedSelection(int x,int y,long now){
        if(!isActive(now)||stage!=Stage.WAITING_CAPTURE||samplesCaptured!=0||analysisClaimed
                ||selectionAnalysisClaimed||!validPoint(x,y))return false;
        selectionAnalysisClaimed=true;stage=Stage.SELECTED_ANALYZING;return true;
    }
    public synchronized boolean analysisReady(LiveFrameTarget target,long now){
        if(!isActive(now)||target==null||!(stage==Stage.ANALYZING&&analysisClaimed
                ||stage==Stage.SELECTED_ANALYZING&&selectionAnalysisClaimed))return false;
        stage=Stage.CROPPING;return true;
    }
    public synchronized boolean complete(long now){if(!isActive(now)||stage!=Stage.CROPPING)return false;stage=Stage.HANDED_OFF;return true;}
    public synchronized void cancel(){stage=Stage.CANCELLED;}
    private static boolean validPoint(int x,int y){return x>=0&&x<=1000&&y>=0&&y<=1000;}
}
