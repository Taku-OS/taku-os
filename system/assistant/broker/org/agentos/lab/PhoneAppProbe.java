package org.agentos.lab;

import android.content.*;
import android.content.pm.*;
import android.os.Looper;
import android.os.UserHandle;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Short-lived PackageManager probe: no account, app-private data, or other profiles. */
public final class PhoneAppProbe {
    static final int FLAGS=PackageManager.MATCH_DISABLED_COMPONENTS;
    public static void main(String[] args) {
        int exit=1;
        try {
            if(args.length!=2||!args[0].matches("[0-9]{1,6}"))throw new IOException("INVALID_USER");
            int user=Integer.parseInt(args[0]);String exact=args[1];if(!exact.isEmpty())PhoneAppCatalog.packageName(exact);
            Looper.prepareMainLooper();
            Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context system=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
            UserHandle handle=(UserHandle)UserHandle.class.getMethod("of",int.class).invoke(null,user);
            Context context=(Context)Context.class.getMethod("createContextAsUser",UserHandle.class,int.class).invoke(system,handle,0);
            PackageManager pm=context.getPackageManager();
            List<PackageInfo> packages;
            if(exact.isEmpty())packages=pm.getInstalledPackages(FLAGS);
            else {packages=new ArrayList<>();try{packages.add(pm.getPackageInfo(exact,FLAGS));}catch(PackageManager.NameNotFoundException absent){}}
            if(packages.size()>4096)throw new IOException("APP_CATALOG_TOO_LARGE");
            Intent launchers=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            if(!exact.isEmpty())launchers.setPackage(exact);
            Map<String,List<ActivityInfo>> entries=new HashMap<>();
            for(ResolveInfo info:pm.queryIntentActivities(launchers,FLAGS))if(info.activityInfo!=null&&info.activityInfo.exported)entries.computeIfAbsent(info.activityInfo.packageName,k->new ArrayList<>()).add(info.activityInfo);
            packages.sort(Comparator.comparing(info->info.packageName));JSONArray apps=new JSONArray();
            for(PackageInfo info:packages){
                ApplicationInfo application=info.applicationInfo;if(application==null)continue;
                String pkg=info.packageName;int state=pm.getApplicationEnabledSetting(pkg);
                boolean enabled=state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT?application.enabled:state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                boolean suspended=(application.flags&ApplicationInfo.FLAG_SUSPENDED)!=0;
                List<ActivityInfo> activities=entries.getOrDefault(pkg,Collections.emptyList());String component="";
                JSONArray components=new JSONArray();
                for(ActivityInfo activity:activities){ComponentName name=new ComponentName(pkg,activity.name);int componentState=pm.getComponentEnabledSetting(name);
                    boolean componentEnabled=componentState==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT?activity.enabled:componentState==PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                    if(componentEnabled){String candidate=name.flattenToString();components.put(candidate);if(component.isEmpty()||candidate.compareTo(component)<0)component=candidate;}
                }
                String label=String.valueOf(pm.getApplicationLabel(application));if(label.length()>160)label=label.substring(0,160);
                String version=info.versionName;if(version!=null&&version.length()>160)version=version.substring(0,160);
                apps.put(new JSONObject().put("packageName",pkg).put("label",label).put("versionName",version==null?JSONObject.NULL:version)
                        .put("versionCode",info.getLongVersionCode()).put("installed",true).put("enabled",enabled).put("suspended",suspended)
                        .put("hasLauncher",!activities.isEmpty()).put("launchable",enabled&&!suspended&&!component.isEmpty()).put("launchComponent",component)
                        .put("launchComponents",components));
            }
            System.out.println("TAKU_APP_CATALOG:"+new JSONObject().put("userId",user).put("observedAt",System.currentTimeMillis()).put("apps",apps));exit=0;
        } catch(Exception error){System.out.println("APP_DISCOVERY_UNAVAILABLE");}
        System.exit(exit);
    }
}
