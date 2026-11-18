package org.agentos.lab;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.json.JSONObject;

/** Fixed real-world clock boundaries, round-tripped through the JSON sent to a model. */
public final class HarnessTimeTest {
    private static int checks;
    private static void check(boolean value,String reason) {
        checks++;if(!value)throw new AssertionError(reason);
    }
    private static JSONObject serialized(String instant,String zone,long submittedAt) throws Exception {
        return new JSONObject(HarnessTime.current(Instant.parse(instant),ZoneId.of(zone),submittedAt).toString());
    }
    public static void main(String[] args) throws Exception {
        Instant clock=Instant.parse("2025-01-01T16:30:00Z");
        long submitted=Instant.parse("2024-12-30T15:00:00Z").toEpochMilli();
        JSONObject shanghai=serialized(clock.toString(),"Asia/Shanghai",submitted);
        check(shanghai.getString("deviceNow").equals("2025-01-02T00:30:00+08:00"),"CST must cross the UTC date boundary");
        check(shanghai.getString("localDate").equals("2025-01-02"),"Local date is not the UTC date");
        check(shanghai.getString("timeZoneId").equals("Asia/Shanghai"),"Retain the IANA zone, not only its offset");
        check(OffsetDateTime.parse(shanghai.getString("deviceNow")).toInstant().equals(clock),"Serialized clock must preserve the exact instant");
        check(shanghai.getString("requestCreatedAt").equals("2024-12-30T23:00:00+08:00"),"Old submission time must remain separate from now");
        check(!shanghai.has("messageDate")&&!shanghai.has("captureDate"),"Never fabricate the screenshot's source date");
        JSONObject utc=serialized(clock.toString(),"UTC",0);
        check(utc.getString("localDate").equals("2025-01-01"),"Same instant in UTC is still the previous date");
        check(!utc.has("requestCreatedAt"),"Missing submission time must not become Unix epoch or now");
        JSONObject summer=serialized("2025-11-02T05:30:00Z","America/New_York",0);
        JSONObject winter=serialized("2025-11-02T06:30:00Z","America/New_York",0);
        check(summer.getString("deviceNow").equals("2025-11-02T01:30:00-04:00"),"First repeated DST hour needs its daylight offset");
        check(winter.getString("deviceNow").equals("2025-11-02T01:30:00-05:00"),"Second repeated DST hour needs its standard offset");
        check(summer.getString("localDate").equals(winter.getString("localDate")),"DST changes the offset without changing this local date");
        JSONObject after=serialized("2025-01-02T16:30:00Z","Asia/Shanghai",submitted);
        check(after.getString("localDate").equals("2025-01-03"),"A later turn must refresh the date without changing the request");
        check(after.getString("requestCreatedAt").equals(shanghai.getString("requestCreatedAt")),"Clock refresh must not rewrite submission time");
        System.out.println("HarnessTimeTest: "+checks+" checks passed");
    }
}
