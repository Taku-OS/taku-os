package org.taku.tryon.realtime;
import java.util.Map;
import org.taku.cosplay.api.CosplayContract;
/** Exact legacy envelopes shared by Android adapter and deterministic host checks. */
final class LegacyWire {
    static Map<String,Object> status(boolean enabled,boolean ready,boolean key,boolean permissions,boolean voiceOnly,String message){
        return Map.of("schema",CosplayContract.SCHEMA,"ok",true,"enabled",enabled,"ready",ready,
                "keyConfigured",key,"permissionsReady",permissions,"voiceOnly",voiceOnly,"userMessage",message);
    }
    static Map<String,Object> prepared(String ticket,long expires,String message){
        return Map.of("schema",CosplayContract.SCHEMA,"ok",true,"ticket",ticket,"expiresAtElapsedMs",expires,"userMessage",message);
    }
}
