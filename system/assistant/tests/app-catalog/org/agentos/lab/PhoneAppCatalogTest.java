package org.agentos.lab;

import java.io.IOException;
import java.util.*;
import org.json.*;

public final class PhoneAppCatalogTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Checked {void run()throws Exception;}
    static void rejects(String code,Checked action)throws Exception{try{action.run();throw new AssertionError("Expected "+code);}catch(IOException e){check(e.getMessage().contains(code),"Unexpected failure: "+e);}}
    static JSONObject app(String pkg,String label,boolean enabled,boolean launcher)throws Exception{
        return new JSONObject().put("packageName",pkg).put("label",label).put("versionName","1.0").put("versionCode",1)
                .put("installed",true).put("enabled",enabled).put("suspended",false).put("hasLauncher",launcher)
                .put("launchable",enabled&&launcher).put("launchComponent",launcher?pkg+"/"+pkg+".Main":"");
    }
    static final class Source implements PhoneAppCatalog.Source {
        final List<JSONObject> apps=new ArrayList<>();int reads,user=0;String exact="";
        public JSONObject read(String packageName)throws Exception{reads++;exact=packageName;JSONArray copy=new JSONArray();for(JSONObject app:apps)if(packageName.isEmpty()||packageName.equals(app.optString("packageName")))copy.put(new JSONObject(app.toString()));return new JSONObject().put("userId",user).put("observedAt",reads).put("apps",copy);}
    }
    static final class Policy implements PhoneAppCatalog.Policy {
        boolean enabled=true;Set<String> denied=new HashSet<>();int allowedCalls,revokeAfter=Integer.MAX_VALUE;
        public JSONObject snapshot(){return new JSONObject().put("allowBackground",enabled).put("maxActiveScreens",3).put("blockedPackages",new JSONArray(denied));}
        public boolean allowed(String pkg){return ++allowedCalls<=revokeAfter&&!denied.contains(pkg);}
    }
    static JSONObject query(Source source,Policy policy,String text,int offset,int limit)throws Exception{return PhoneAppCatalog.query(source,policy,new JSONObject().put("query",text).put("offset",offset).put("limit",limit));}
    public static void main(String[] args)throws Exception{
        Source source=new Source();Policy policy=new Policy();
        source.apps.add(app("org.example.zulu","Zulu",true,true));source.apps.add(app("org.example.maps","高德地图",true,true));
        source.apps.add(app("org.example.chat","微信",false,true));source.apps.add(app("org.example.alpha","alpha",true,true));
        source.apps.add(app("org.example.service","Service",true,false));
        JSONObject page=query(source,policy,"",0,2);JSONArray entries=page.getJSONArray("apps");
        check(page.getInt("total")==4,"hide non-launcher packages from ordinary listing");
        check(entries.getJSONObject(0).getString("label").equals("alpha"),"stable case-insensitive label sort");
        check(entries.getJSONObject(1).getString("label").equals("Zulu"),"ordered page");
        check(page.getInt("nextOffset")==2,"bounded pagination cursor");
        check(page.getString("deviceScope").equals("this_android_device")&&page.getInt("userId")==0,"explicit device and user scope");
        JSONObject last=query(source,policy,"",2,2);check(last.isNull("nextOffset"),"last page has no cursor");
        check(query(source,policy,"",999,2).getJSONArray("apps").length()==0,"offset beyond end returns empty");
        check(query(source,policy,"高德",0,10).getJSONArray("apps").length()==1,"Chinese name search");
        check(query(source,policy,"ZULU",0,10).getJSONArray("apps").length()==1,"case-insensitive name search");
        check(query(source,policy,"example.maps",0,10).getJSONArray("apps").length()==1,"package substring search");
        check(query(source,policy,"missing",0,10).getInt("total")==0,"missing app is not invented");
        JSONObject service=query(source,policy,"org.example.service",0,10).getJSONArray("apps").getJSONObject(0);
        check(service.getBoolean("installed")&&!service.getBoolean("hasLauncher")&&!service.getBoolean("launchable"),"exact package reveals no-launcher status");
        JSONObject disabled=query(source,policy,"微信",0,10).getJSONArray("apps").getJSONObject(0);
        check(!disabled.getBoolean("enabled")&&!disabled.getBoolean("launchable"),"disabled launcher remains visible with accurate state");
        check(disabled.getString("loginState").equals("unknown")&&disabled.getString("backgroundCompatibility").equals("unknown")&&disabled.getString("businessCapabilities").equals("unknown"),"do not infer account or business readiness");
        policy.denied.add("org.example.maps");JSONObject denied=query(source,policy,"高德",0,10).getJSONArray("apps").getJSONObject(0);
        check(!denied.getBoolean("agentAllowed")&&!denied.getBoolean("backgroundAllowed"),"denied app remains discoverable but disallowed");
        source.apps.add(app("android","Android System",true,false));
        check(!query(source,policy,"android",0,10).getJSONArray("apps").getJSONObject(0).getBoolean("agentAllowed"),"platform package without supported app namespace remains read-only metadata");
        policy.enabled=false;JSONObject paused=query(source,policy,"Zulu",0,10).getJSONArray("apps").getJSONObject(0);
        check(paused.getBoolean("agentAllowed")&&!paused.getBoolean("backgroundAllowed"),"new-allocation pause is distinct from app authorization");
        check(PhoneAppCatalog.requireLaunchable(source,policy,"org.example.zulu").getInt("userId")==0,"existing authorized lease can launch while new allocations paused");
        rejects("APP_NOT_ALLOWED",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.maps"));
        rejects("APP_NOT_INSTALLED",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.absent"));
        rejects("APP_DISABLED",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.chat"));
        rejects("APP_NOT_LAUNCHABLE",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.service"));
        source.apps.get(0).put("suspended",true);rejects("APP_SUSPENDED",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.zulu"));source.apps.get(0).put("suspended",false);
        String before=query(source,policy,"",0,20).getString("catalogRevision");source.apps.get(2).put("enabled",true).put("launchable",true);
        JSONObject refreshed=query(source,policy,"微信",0,10);check(refreshed.getJSONArray("apps").getJSONObject(0).getBoolean("enabled"),"changed enabled state is refreshed on next query");
        check(!before.equals(refreshed.getString("catalogRevision")),"metadata changes invalidate revision");
        source.apps.add(app("org.example.newapp","New",true,true));check(query(source,policy,"New",0,10).getInt("total")==1,"installation appears without restarting broker");
        source.apps.remove(0);rejects("APP_NOT_INSTALLED",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.zulu"));
        check(source.exact.equals("org.example.zulu"),"launch checks only the selected package, not the full catalog");
        source.user=10;check(query(source,policy,"",0,20).getInt("userId")==10,"user switches change scope rather than merge catalogs");
        JSONObject launch=PhoneAppCatalog.requireLaunchable(source,policy,"org.example.chat");check(launch.getInt("userId")==10,"launch result carries verified current user");
        source.apps.get(0).put("launchComponent","org.malicious/.Main");policy.denied.clear();rejects("INVALID_APP_COMPONENT",()->PhoneAppCatalog.requireLaunchable(source,policy,"org.example.maps"));
        Policy revoked=new Policy();revoked.revokeAfter=1;rejects("APP_NOT_ALLOWED",()->PhoneAppCatalog.requireLaunchable(source,revoked,"org.example.chat"));
        for(String invalid:new String[]{"", "org.example;reboot", "org/example", "org.example\nother", "--user"})rejects("INVALID_APP_PACKAGE",()->PhoneAppCatalog.requireLaunchable(source,policy,invalid));
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("limit",0),new JSONObject().put("limit",51),new JSONObject().put("offset",-1),new JSONObject().put("offset",4097),new JSONObject().put("query","x\n"),new JSONObject().put("query",false),new JSONObject().put("limit",1.5),new JSONObject().put("limit","5")})rejects("INVALID_APP_QUERY",()->PhoneAppCatalog.query(source,policy,invalid));
        System.out.println("PhoneAppCatalogTest: "+checks+" checks passed");
    }
}

// JVM-only adapters: production policy and Android process calls are tested separately.
class WorkspacePolicy {static JSONObject snapshot(){return new JSONObject().put("allowBackground",true);}static boolean isAppAllowed(String pkg){return true;}}
class TaskBroker {static String command(String... args)throws IOException{throw new IOException("NO_DEVICE_IN_HOST_TEST");}}
