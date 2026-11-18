package org.agentos.lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Public app metadata for this device's current Android user, never account contents. */
final class PhoneAppCatalog {
    static final int MAX_PAGE=50, SUMMARY_SIZE=12;
    interface Source { JSONObject read(String exactPackage)throws Exception; }
    interface Policy { JSONObject snapshot()throws Exception; boolean allowed(String pkg)throws Exception; }
    static final Source DEVICE=PhoneAppCatalog::probe;
    static final Policy POLICY=new Policy(){
        public JSONObject snapshot()throws Exception{return WorkspacePolicy.snapshot();}
        public boolean allowed(String pkg)throws Exception{return WorkspacePolicy.isAppAllowed(pkg);}
    };
    static void packageName(String value)throws IOException {
        if(value==null||value.length()>200||!value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IOException("INVALID_APP_PACKAGE");
    }
    static int currentUser()throws Exception {
        String value=TaskBroker.command("/system/bin/am","get-current-user").trim();
        if(!value.matches("[0-9]{1,6}"))throw new IOException("CURRENT_ANDROID_USER_UNAVAILABLE");
        return Integer.parseInt(value);
    }
    static JSONObject query(JSONObject args)throws Exception{return query(DEVICE,POLICY,args);}
    static int integer(JSONObject args,String key,int fallback)throws IOException {
        if(!args.has(key))return fallback;Object value=args.opt(key);
        if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()!=((Number)value).intValue())throw new IOException("INVALID_APP_QUERY");
        return ((Number)value).intValue();
    }
    static JSONObject query(Source source,Policy policy,JSONObject args)throws Exception {
        if(args.has("query")&&!(args.opt("query") instanceof String))throw new IOException("INVALID_APP_QUERY");
        String supplied=args.optString("query","");int offset=integer(args,"offset",0),limit=integer(args,"limit",20);
        if(supplied.length()>200||supplied.chars().anyMatch(c->Character.isISOControl(c))||offset<0||offset>4096||limit<1||limit>MAX_PAGE)throw new IOException("INVALID_APP_QUERY");
        String query=supplied.trim();
        JSONObject snapshot=source.read("");JSONObject settings=policy.snapshot();
        JSONArray raw=snapshot.getJSONArray("apps");List<JSONObject> matches=new ArrayList<>();String needle=query.toLowerCase(Locale.ROOT);
        for(int i=0;i<raw.length();i++){
            JSONObject app=raw.getJSONObject(i);String pkg=app.getString("packageName");
            boolean exact=pkg.equals(query);
            if(!exact&&!app.optBoolean("hasLauncher"))continue;
            if(!query.isEmpty()&&!exact&&!pkg.toLowerCase(Locale.ROOT).contains(needle)&&!app.getString("label").toLowerCase(Locale.ROOT).contains(needle))continue;
            matches.add(withPolicy(app,policy,settings));
        }
        matches.sort(Comparator.comparing((JSONObject a)->a.optString("label").toLowerCase(Locale.ROOT)).thenComparing(a->a.optString("packageName")));
        JSONArray page=new JSONArray();for(int i=offset;i<Math.min(matches.size(),offset+limit);i++)page.put(matches.get(i));
        return envelope(snapshot).put("query",query).put("offset",offset).put("limit",limit).put("total",matches.size()).put("apps",page)
                .put("nextOffset",offset+page.length()<matches.size()?offset+page.length():JSONObject.NULL)
                .put("backgroundPolicy",settings).put("backgroundAllowedMeaning","Eligible for a new background allocation; capacity and runtime compatibility are checked separately.")
                .put("visibility","Current-user launchable-app catalog including disabled launcher apps; an exact package-name query can include a package without a launcher.");
    }
    static JSONObject withPolicy(JSONObject original,Policy policy,JSONObject settings)throws Exception {
        JSONObject app=new JSONObject(original.toString());String pkg=app.getString("packageName");boolean supported=true;
        try{packageName(pkg);}catch(IOException unsupported){supported=false;}
        boolean allowed=supported&&policy.allowed(pkg);
        return app.put("agentAllowed",allowed).put("backgroundAllowed",allowed&&settings.getBoolean("allowBackground"))
                .put("loginState","unknown").put("backgroundCompatibility","unknown").put("businessCapabilities","unknown");
    }
    static JSONObject envelope(JSONObject snapshot)throws Exception {
        return new JSONObject().put("deviceScope","this_android_device").put("userId",snapshot.getInt("userId"))
                .put("observedAt",snapshot.getLong("observedAt")).put("catalogRevision",revision(snapshot));
    }
    static String revision(JSONObject snapshot)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((snapshot.getInt("userId")+"\n"+snapshot.getJSONArray("apps")).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static JSONObject summary()throws Exception {
        JSONObject result=query(new JSONObject().put("limit",SUMMARY_SIZE));
        result.put("discoveryTool","taku_phone.apps").put("refreshPolicy","Refreshed at task preparation and every catalog query; app launch rechecks the selected package and current user.")
                .put("planningInstruction","Before selecting apps for device actions, use this device/user catalog and query the relevant names or packages. This is a partial catalog, not an exhaustive list. Installed does not prove login, background compatibility or a business capability. Do not infer them.");
        return result;
    }
    static JSONObject requireLaunchable(String pkg)throws Exception {
        return requireLaunchable(DEVICE,POLICY,pkg);
    }
    static JSONObject requireLaunchable(Source source,Policy policy,String pkg)throws Exception {
        packageName(pkg);
        if(!policy.allowed(pkg))throw new IOException("APP_NOT_ALLOWED");
        JSONObject snapshot=source.read(pkg);JSONArray apps=snapshot.getJSONArray("apps");JSONObject app=null;
        for(int i=0;i<apps.length();i++)if(pkg.equals(apps.getJSONObject(i).optString("packageName")))app=apps.getJSONObject(i);
        if(app==null||!app.optBoolean("installed"))throw new IOException("APP_NOT_INSTALLED");
        if(!app.optBoolean("enabled"))throw new IOException("APP_DISABLED");
        if(app.optBoolean("suspended"))throw new IOException("APP_SUSPENDED");
        if(!app.optBoolean("launchable")||app.optString("launchComponent").isEmpty())throw new IOException("APP_NOT_LAUNCHABLE");
        String component=app.getString("launchComponent");
        if(!component.startsWith(pkg+"/")||!component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))throw new IOException("INVALID_APP_COMPONENT");
        // Authorization can change while PackageManager is being read.
        if(!policy.allowed(pkg))throw new IOException("APP_NOT_ALLOWED");
        return new JSONObject(app.toString()).put("userId",snapshot.getInt("userId")).put("observedAt",snapshot.getLong("observedAt"));
    }
    static JSONObject probe(String exactPackage)throws Exception {
        if(!exactPackage.isEmpty())packageName(exactPackage);
        int user=currentUser();
        ProcessBuilder builder=new ProcessBuilder("/system/bin/app_process","/system/bin","org.agentos.lab.PhoneAppProbe",Integer.toString(user),exactPackage);
        builder.environment().put("CLASSPATH",System.getProperty("java.class.path"));
        java.lang.Process helper=builder.redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"app-catalog-output");t.setDaemon(true);return t;});
        Future<String> output=reader.submit(()->{try(InputStream in=helper.getInputStream()){byte[] bytes=in.readNBytes(2097153);if(bytes.length>2097152)throw new IOException("APP_CATALOG_TOO_LARGE");return new String(bytes,StandardCharsets.UTF_8);}});
        try {
            if(!helper.waitFor(8,TimeUnit.SECONDS))throw new IOException("APP_DISCOVERY_TIMEOUT");
            String value=output.get(1,TimeUnit.SECONDS).trim();
            if(helper.exitValue()!=0)throw new IOException("APP_DISCOVERY_UNAVAILABLE");
            // app_process can prepend framework diagnostics. Only accept the unique result marker.
            String marker="TAKU_APP_CATALOG:";int at=value.indexOf(marker);
            if(at<0||value.indexOf(marker,at+marker.length())>=0)throw new IOException("APP_DISCOVERY_INVALID_RESULT");
            JSONObject result=new JSONObject(value.substring(at+marker.length()));
            if(result.getInt("userId")!=user||currentUser()!=user)throw new IOException("ANDROID_USER_CHANGED");
            return result;
        } finally {if(helper.isAlive())helper.destroyForcibly();output.cancel(true);reader.shutdownNow();}
    }
}
