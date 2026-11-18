package org.agentos.proactivity;

import java.util.*;
import org.agentos.proactivity.api.ObserverSpec;

/** Bounded deterministic evaluator. Caller identity and durable permission live in the host. */
final class SettledObserver {
    record Event(String id,String type,long sequence,long at,int pageRevision) {}
    record Candidate(String id,long generation,List<Event> records) {}
    private final ObserverSpec spec;
    private final ArrayDeque<Event> journal=new ArrayDeque<>();
    private long generation, lastOffer=Long.MIN_VALUE, due=Long.MAX_VALUE;
    SettledObserver(ObserverSpec spec){this.spec=spec;}
    boolean event(Event event) {
        if(!event.type().equals(spec.eventType()))throw new IllegalArgumentException("Undeclared event type");
        if(journal.stream().anyMatch(e->e.id().equals(event.id())))return false;
        if(!journal.isEmpty() && event.sequence()<=journal.peekLast().sequence())throw new IllegalArgumentException("Out of order sequence");
        journal.add(event);while(journal.size()>64 || event.at()-journal.peekFirst().at()>spec.lookbackMs())journal.removeFirst();
        generation++;due=event.at()+spec.quietMs();return true;
    }
    long due(){return due;}
    Candidate poll(long now) {
        if(now<due || due==Long.MAX_VALUE)return null;
        due=Long.MAX_VALUE;
        if(lastOffer!=Long.MIN_VALUE && now-lastOffer<spec.cooldownMs())return null;
        if(journal.isEmpty() || now-journal.peekLast().at()>120000)return null;
        List<Event> eligible=journal.stream().filter(e->now-e.at()<=spec.lookbackMs()).collect(java.util.stream.Collectors.toList());
        if(eligible.isEmpty())return null;
        return new Candidate(UUID.randomUUID().toString(),generation,
            List.copyOf(eligible.subList(Math.max(0,eligible.size()-spec.maxRecords()),eligible.size())));
    }
    void offered(long now){lastOffer=now;}
    void restoreCooldown(long now,long age){lastOffer=now-Math.min(Math.max(0,age),spec.cooldownMs());}
    boolean current(Candidate c){return c.generation()==generation;}
    void invalidate(){generation++;due=Long.MAX_VALUE;journal.clear();}
}
