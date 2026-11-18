package org.agentos.lab;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

/** A turn's device clock snapshot, independent of dates inside screenshots or messages. */
final class HarnessTime {
    static JSONObject current(Instant now,ZoneId zone,long requestCreatedAt) throws Exception {
        ZonedDateTime local=now.atZone(zone);
        JSONObject result=new JSONObject()
                .put("deviceNow",local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("timeZoneId",zone.getId())
                .put("localDate",local.toLocalDate().toString());
        if(requestCreatedAt>0)result.put("requestCreatedAt",Instant.ofEpochMilli(requestCreatedAt)
                .atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return result;
    }
}
