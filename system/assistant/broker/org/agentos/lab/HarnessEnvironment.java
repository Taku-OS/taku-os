package org.agentos.lab;

import org.json.*;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.DateTimeException;

/** Explicit public environment facts only; no serial numbers, auth files, config or home scans. */
final class HarnessEnvironment {
    private static String property(String key) throws Exception {
        return TaskBroker.command("/system/bin/getprop",key).trim();
    }
    private static ZoneId deviceTimeZone() throws Exception {
        String id=property("persist.sys.timezone");
        if(!id.isEmpty())try { return ZoneId.of(id); } catch(DateTimeException invalid) { /* Use Android's default below. */ }
        return ZoneId.systemDefault();
    }
    static JSONObject collect(JSONObject request) throws Exception {
        String lineage=property("ro.lineage.version");
        JSONObject os=new JSONObject().put("family","Android").put("distribution",lineage.isEmpty()?"AOSP":"LineageOS")
                .put("release",property("ro.build.version.release")).put("api",property("ro.build.version.sdk"))
                .put("abi",property("ro.product.cpu.abi")).put("model",property("ro.product.model"))
                .put("device",property("ro.product.device")).put("buildType",property("ro.build.type"))
                .put("imageChannel",property("ro.taku.image.channel")).put("emulator","1".equals(property("ro.kernel.qemu")));
        JSONObject capabilities=new JSONObject().put("runtimeUid",android.os.Process.myUid())
                .put("globalSelinux",TaskBroker.command("/system/bin/getenforce").trim())
                .put("backgroundDisplayTool",new File("/system_ext/bin/phone-display").canExecute())
                .put("nativeCompilerPrepared",new File("/data/local/agentos/light-app-lab/toolchain/ecj.jar").isFile()
                        &&new File("/data/local/agentos/light-app-lab/build-native.sh").isFile())
                .put("lightAppKitPrepared",new File("/data/local/agentos/light-app-lab/free-app-kit/runtime/esbuild").canExecute())
                .put("productionIsolationVerified",false);
        JSONObject current=HarnessTime.current(Instant.now(),deviceTimeZone(),request.optLong("createdAt",0))
                .put("backgroundDisplayId",request.optString("displayId"))
                .put("foreground",request.optString("foreground"))
                .put("includeScreenshot",!request.has("conversationId")||request.optBoolean("includeScreenshot",false))
                .put("responseStyle",request.optString("responseStyle","concise"))
                .put("outputMode",request.has("conversationId")?"conversation":"task-schema");
        JSONObject result=new JSONObject().put("os",os).put("capabilities",capabilities).put("current",current);
        if(!request.optBoolean("omitAppCatalog")){
            try{result.put("apps",PhoneAppCatalog.summary().put("available",true));}
            catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}
            catch(Exception unavailable){result.put("apps",new JSONObject().put("available",false).put("deviceScope","this_android_device")
                    .put("discoveryTool","taku_phone.apps").put("reason","APP_DISCOVERY_UNAVAILABLE")
                    .put("instruction","Read actual apps with taku_phone.apps before app planning; do not guess installed packages or inspect another device."));}
        }
        return result;
    }
}
