package org.agentos.lab;

import java.io.IOException;
import java.util.*;

/** Fences native execution requests across user/agent control handoffs.
 * A successful start response is not proof that its turn/started event was seen.
 */
final class WorkspaceAdmissions {
    interface Source { PhoneExecutionScreens.Ledger ledger()throws Exception; }
    static final class Ticket {
        final String method,thread,workspaceRequestId;final PhoneExecutionScreens.Admission grant;
        boolean sent,replied,started;int requestId;
        Ticket(String method,String thread,PhoneExecutionScreens.Admission grant,String workspaceRequestId){this.method=method;this.thread=thread;this.grant=grant;this.workspaceRequestId=workspaceRequestId;}
    }
    private final Source source;
    private boolean skillMaintenance;
    private final Set<Ticket> pending=new LinkedHashSet<>();
    private final Map<Integer,Ticket> sent=new HashMap<>();
    private final Map<String,PhoneExecutionScreens.Admission> realtime=new HashMap<>();
    private final Map<String,String> reservations=new HashMap<>();
    WorkspaceAdmissions(Source source){this.source=source;}
    static boolean execution(String method){return Set.of("turn/start","turn/steer","thread/realtime/start","thread/realtime/appendAudio","thread/realtime/appendText","thread/realtime/appendSpeech").contains(method);}
    synchronized Ticket queue(String method,String thread)throws Exception {
        return queue(method,thread,"");
    }
    synchronized Ticket queue(String method,String thread,String workspaceRequestId)throws Exception {
        if(!execution(method))return null;
        if(skillMaintenance)throw new IOException("Skill management is in progress. Start the task later.");
        requireReservation(thread,workspaceRequestId);
        Ticket ticket=new Ticket(method,thread,source.ledger().admission(thread),workspaceRequestId);pending.add(ticket);return ticket;
    }
    synchronized void reserve(String thread,String requestId)throws Exception {
        String current=reservations.get(thread);
        if(current!=null&&!current.equals(requestId))throw new IOException("WORKSPACE_EXECUTION_RESERVED");
        reservations.put(thread,requestId);
    }
    synchronized void releaseReservation(String thread,String requestId){reservations.remove(thread,requestId);}
    private void requireReservation(String thread,String requestId)throws Exception {
        String current=reservations.get(thread);
        if(current!=null&&!current.equals(requestId)||!requestId.isEmpty()&&!requestId.equals(current))
            throw new IOException("WORKSPACE_EXECUTION_RESERVED");
    }
    synchronized void beforeSend(int requestId,Ticket ticket)throws Exception {
        if(ticket==null)return;
        requireCurrent(ticket);
        ticket.sent=true;ticket.requestId=requestId;sent.put(requestId,ticket);
    }
    synchronized void requireCurrent(Ticket ticket)throws Exception {
        if(ticket!=null)requireReservation(ticket.thread,ticket.workspaceRequestId);
        if(ticket!=null&&(!pending.contains(ticket)||!source.ledger().admissionCurrent(ticket.grant)))throw new IOException("SCREEN_CONTROL_CHANGED");
    }
    synchronized void failedBeforeSend(Ticket ticket){
        // A failed socket write can have delivered the request. Keep it unresolved.
        if(ticket!=null&&!ticket.sent)pending.remove(ticket);
    }
    synchronized void response(int requestId,boolean success){
        Ticket ticket=sent.remove(requestId);if(ticket==null)return;ticket.replied=true;
        if(success&&ticket.method.equals("thread/realtime/start"))realtime.put(ticket.thread,ticket.grant);
        if(!success||!ticket.method.equals("turn/start")||ticket.started)pending.remove(ticket);
    }
    synchronized boolean turnStarted(String thread)throws Exception {
        Ticket ticket=null;
        for(Ticket candidate:pending)if(candidate.sent&&!candidate.started&&candidate.thread.equals(thread)&&candidate.method.equals("turn/start")){ticket=candidate;break;}
        PhoneExecutionScreens.Admission grant;
        if(ticket!=null){ticket.started=true;grant=ticket.grant;if(ticket.replied)pending.remove(ticket);}
        else {
            grant=realtime.get(thread);
            // Realtime may emit its first turn before acknowledging session start.
            if(grant==null)for(Ticket candidate:pending)if(candidate.sent&&candidate.thread.equals(thread)&&candidate.method.equals("thread/realtime/start")){grant=candidate.grant;break;}
        }
        return source.ledger().turnStarted(thread,grant);
    }
    synchronized void beginSkillMaintenance()throws IOException {
        if(skillMaintenance || !pending.isEmpty() || !realtime.isEmpty())throw new IOException("A task is running. Manage skills after it finishes.");
        skillMaintenance=true;
    }
    synchronized void endSkillMaintenance(){skillMaintenance=false;}
    synchronized int pending(String thread){int count=0;for(Ticket ticket:pending)if(ticket.thread.equals(thread))count++;return count;}
    synchronized boolean realtimeActive(String thread){return realtime.containsKey(thread);}
    synchronized void realtimeStopped(String thread){realtime.remove(thread);}
    // Delivery uncertainty survives a native transport restart; only a reconciled
    // receipt may release its reservation.
    synchronized void clear(){pending.clear();sent.clear();realtime.clear();}
}
