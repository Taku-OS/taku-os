package org.taku.tryon.realtime;
import java.util.Set;
import org.taku.cosplay.api.CosplayContract;
public final class LegacyWireTest {
    public static void main(String[] args){
        var status=LegacyWire.status(true,true,true,true,false,"Ready");
        check(status.keySet().equals(Set.of("schema","ok","enabled","ready","keyConfigured","permissionsReady","voiceOnly","userMessage")));
        check(CosplayContract.SCHEMA.equals(status.get("schema"))&&Boolean.TRUE.equals(status.get("ok")));
        var prepared=LegacyWire.prepared("a".repeat(32),15000,"Prepared");
        check(prepared.keySet().equals(Set.of("schema","ok","ticket","expiresAtElapsedMs","userMessage")));
        check(prepared.get("expiresAtElapsedMs") instanceof Long);
        check(CosplayContract.MAX_CAPTURE_AGE_MS==120000&&CosplayContract.MAX_INVOCATION_AGE_MS==120000&&CosplayContract.TICKET_TTL_MS==15000);
        System.out.println("LegacyWireTest: 5 exact envelope/type/deadline checks passed");
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
}
