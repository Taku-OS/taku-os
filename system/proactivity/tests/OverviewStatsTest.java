package org.agentos.proactivity;

import java.time.*;
import java.util.*;

public final class OverviewStatsTest {
    private static int assertions;
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("dashboard assertion "+assertions);}
    private static OverviewStats.Event e(long at,String type,String id){return new OverviewStats.Event(at,type,id);}
    public static void main(String[] args){
        long now=Instant.parse("2026-10-24T02:30:00Z").toEpochMilli(),hour=OverviewStats.HOUR;
        var a=new OverviewStats.Source("a","Alpha",true,false,List.of(e(now,"published","same"),e(now-10,"published","same"),e(now,"accepted","same"),e(now+1,"published","future"),e(now-25*hour,"approved","")));
        var b=new OverviewStats.Source("b","Beta",true,true,List.of(e(now-hour,"registered",""),e(now,"published","same")));
        var c=new OverviewStats.Source("c","Gamma",false,false,List.of());
        var stats=new OverviewStats(List.of(a,b,c),now,ZoneId.of("Asia/Shanghai"));
        check(stats.active==1&&stats.pending==1&&stats.disabled==1);check(stats.activity==4);
        check(stats.published==2&&stats.accepted==1);check(stats.hourly[OverviewStats.PUBLISHED][23]==2);
        check(stats.hourly[OverviewStats.MAINTENANCE][22]==1);check(stats.count(23)==3);
        check(stats.ranking.get(0).count()==2&&stats.ranking.get(0).key().equals("a"));
        check(stats.selected(0)==23&&stats.selected(stats.start-1)==0&&stats.selected(now+hour)==23);
        var edge=new OverviewStats(List.of(new OverviewStats.Source("edge","Edge",true,false,List.of(e(stats.start,"new_kind",""),e(stats.start-1,"published","before")))),now,stats.zone);
        check(edge.activity==1&&edge.hourly[OverviewStats.OTHER][0]==1);
        var empty=new OverviewStats(List.of(),now,stats.zone);check(empty.activity==0&&empty.totalSources==0&&empty.ranking.isEmpty());
        // Half-hour zones use local hour boundaries; DST retains 24 chronological elapsed bins.
        var india=new OverviewStats(List.of(),now,ZoneId.of("Asia/Kolkata"));check(Instant.ofEpochMilli(india.at(23)).atZone(india.zone).getMinute()==0);
        var dst=new OverviewStats(List.of(),Instant.parse("2026-11-01T07:30:00Z").toEpochMilli(),ZoneId.of("America/New_York"));check(dst.at(23)-dst.at(0)==23*hour);
        System.out.println(assertions+" dashboard aggregation assertions passed");
    }
}
