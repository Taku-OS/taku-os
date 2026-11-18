package org.agentos.proactivity.api;

import org.json.JSONObject;

/** Public JSON wire format; implementations need not use the optional Android client. */
public final class ProactivityWire {
    public static final int VERSION=1, REQUEST=1, RESPONSE=100, MAX_BYTES=16384;
    public static final String PACKAGE="org.agentos.proactivity";
    public static final String SERVICE=PACKAGE+".ProactivityService";
    private ProactivityWire() {}
    public static long integer(JSONObject object,String key) throws Exception {
        Object value=object.get(key);
        if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.longValue())
            throw new IllegalArgumentException("Expected integer: "+key);
        return ((Number)value).longValue();
    }
    public static JSONObject parse(String payload) throws Exception {
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("Request too large");
        int depth=0;boolean quoted=false,escaped=false;
        for(char c:payload.toCharArray()) {
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='\"')quoted=false;}
            else if(c=='\"')quoted=true;
            else if(c=='{' || c=='['){if(++depth>16)throw new IllegalArgumentException("Request nested too deeply");}
            else if(c=='}' || c==']')depth--;
        }
        return new JSONObject(payload);
    }
    public static JSONObject spec(ObserverSpec s) throws Exception {
        return new JSONObject().put("id",s.id()).put("name",s.name()).put("eventType",s.eventType())
            .put("quietMs",s.quietMs()).put("cooldownMs",s.cooldownMs()).put("maxRecords",s.maxRecords())
            .put("lookbackMs",s.lookbackMs()).put("title",s.title()).put("summary",s.summary());
    }
    public static ObserverSpec spec(JSONObject p) throws Exception {
        if(p.length()!=9)throw new IllegalArgumentException("Unknown or missing rule fields");
        return new ObserverSpec(p.getString("id"),p.getString("name"),p.getString("eventType"),
            integer(p,"quietMs"),integer(p,"cooldownMs"),Math.toIntExact(integer(p,"maxRecords")),integer(p,"lookbackMs"),
            p.getString("title"),p.getString("summary"));
    }
}
