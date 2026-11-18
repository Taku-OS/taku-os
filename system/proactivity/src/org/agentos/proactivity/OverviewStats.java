package org.agentos.proactivity;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only projection of the existing registry/trace, never a new telemetry store. */
final class OverviewStats {
    static final long HOUR=3600000L;
    static final int MAINTENANCE=0,PUBLISHED=1,ACCEPTED=2,OTHER=3;
    record Event(long at,String type,String candidateId) {}
    record Source(String key,String name,boolean active,boolean pending,List<Event> events) {}
    record Ranked(String key,String name,int count) {}
    private record Scoped(String key,String name,Event event) {}
    final long start,now;
    final ZoneId zone;
    final int[][] hourly=new int[4][24];
    final int active,pending,disabled,totalSources;
    final int activity,published,accepted;
    final List<Ranked> ranking;
    OverviewStats(List<Source> sources,long now,ZoneId zone) {
        this.now=now;this.zone=zone;
        start=Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()-23*HOUR;
        int enabled=0,waiting=0;List<Scoped> events=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();
        for(Source source:sources){
            if(source.pending())waiting++;else if(source.active())enabled++;
            counts.put(source.key(),0);
            for(Event event:source.events())if(event.at()>=start&&event.at()<=now)events.add(new Scoped(source.key(),source.name(),event));
        }
        active=enabled;pending=waiting;totalSources=sources.size();disabled=totalSources-active-pending;
        events.sort(Comparator.comparingLong(e->e.event.at()));Set<String> seen=new HashSet<>();int total=0,offers=0,views=0;
        for(Scoped scoped:events){
            Event event=scoped.event();
            // A repeated display ACK must not inflate a chart; observer scopes stay independent.
            if(event.candidateId()!=null&&!event.candidateId().isEmpty()
                    &&!seen.add(scoped.key()+"\n"+event.type()+"\n"+event.candidateId()))continue;
            int hour=(int)((event.at()-start)/HOUR),category=category(event.type());
            hourly[category][hour]++;total++;if(category==PUBLISHED)offers++;if(category==ACCEPTED)views++;
            counts.compute(scoped.key(),(key,count)->count+1);
        }
        activity=total;published=offers;accepted=views;
        List<Ranked> sorted=new ArrayList<>();for(Source source:sources)sorted.add(new Ranked(source.key(),source.name(),counts.get(source.key())));
        sorted.sort(Comparator.comparingInt(Ranked::count).reversed().thenComparing(Ranked::key));ranking=List.copyOf(sorted);
    }
    static int category(String type){return switch(type){
        case "registered","approved","disabled","rolled_back","policy_edited"->MAINTENANCE;
        case "published"->PUBLISHED;case "accepted"->ACCEPTED;default->OTHER;
    };}
    int count(int hour){int count=0;for(int[] row:hourly)count+=row[hour];return count;}
    int selected(long wall){return wall<=0?23:(int)Math.max(0,Math.min(23,(wall-start)/HOUR));}
    long at(int hour){return start+hour*HOUR;}
}
