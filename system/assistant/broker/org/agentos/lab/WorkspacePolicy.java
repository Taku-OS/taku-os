package org.agentos.lab;

import java.io.*;
import java.util.*;
import org.json.*;

/** Device-local user policy. A damaged policy is never treated as permission. */
final class WorkspacePolicy {
    /**
     * Dedicated screens allowed at once: the only place the range and default live. The settings
     * page and agents read the range from workspaces list (limits()).
     */
    static final int MIN_ACTIVE_SCREENS = 1, MAX_ACTIVE_SCREENS = 10, DEFAULT_ACTIVE_SCREENS = 10;
    static final class Store {
        final File file;
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        Store(File file){this.file=file;}
        JSONObject defaults()throws Exception{return new JSONObject().put("schemaVersion",1).put("revision",0)
            .put("allowBackground",true).put("maxActiveScreens",DEFAULT_ACTIVE_SCREENS)
            .put("blockedPackages",new JSONArray()).put("jevCloseEnabled",false);}
        JSONObject read()throws Exception { try (CleanupWork.Held held = CleanupWork.lock(lock)) {
            if(file==null||!file.exists())return defaults();
            JSONObject value=new JSONObject(TaskBroker.read(file));
            if(value.getInt("schemaVersion")!=1)throw new IOException("INVALID_WORKSPACE_POLICY");
            if(!value.has("jevCloseEnabled"))value.put("jevCloseEnabled",false);
            validate(value);return value;
        } }
        static void validate(JSONObject value)throws Exception {
            if(!(value.get("allowBackground") instanceof Boolean))throw new IOException("INVALID_BACKGROUND_SETTING");
            if(!(value.get("jevCloseEnabled") instanceof Boolean))throw new IOException("INVALID_JEV_CLOSE_SETTING");
            Object count=value.get("maxActiveScreens");
            if(!(count instanceof Integer)||((Integer)count)<MIN_ACTIVE_SCREENS||((Integer)count)>MAX_ACTIVE_SCREENS)throw new IOException("INVALID_WORKSPACE_LIMIT");
            JSONArray packages=value.getJSONArray("blockedPackages");
            if(packages.length()>512)throw new IOException("TOO_MANY_BLOCKED_APPS");
            Set<String> seen=new HashSet<>();
            for(int i=0;i<packages.length();i++){
                Object item=packages.get(i);if(!(item instanceof String))throw new IOException("INVALID_APP_PACKAGE");
                PhoneExecutionScreens.packageName((String)item);
                if(!seen.add((String)item))throw new IOException("DUPLICATE_APP_PACKAGE");
            }
        }
        JSONObject configure(JSONObject patch)throws Exception { try (CleanupWork.Held held = CleanupWork.lock(lock)) {
            if(patch==null)throw new IOException("MISSING_WORKSPACE_PATCH");
            JSONObject value=read();
            for(Iterator<String> keys=patch.keys();keys.hasNext();){String key=keys.next();
                if(!Set.of("allowBackground","maxActiveScreens","blockedPackages","jevCloseEnabled").contains(key))throw new IOException("UNKNOWN_WORKSPACE_SETTING");
                value.put(key,patch.get(key));
            }
            validate(value);value.put("revision",value.getLong("revision")+1);
            if(file==null)throw new IOException("WORKSPACE_POLICY_STORE_UNAVAILABLE");
            file.getParentFile().mkdirs();TaskBroker.write(file,value.toString());return new JSONObject(value.toString());
        } }
    }
    static Store store=new Store(new File(TaskBroker.ROOT,"workspace-policy.json"));
    static JSONObject snapshot()throws Exception{return store.read();}
    /** The ranges configure accepts, as workspaces list reports them. */
    static JSONObject limits() throws Exception {
        JSONObject screens = new JSONObject().put("min", MIN_ACTIVE_SCREENS).put("max", MAX_ACTIVE_SCREENS);
        return new JSONObject().put("maxActiveScreens", screens);
    }
    static JSONObject configure(JSONObject patch)throws Exception{return store.configure(patch);}
    static Set<String> blockedPackages(JSONObject policy)throws Exception {
        Set<String> packages=new LinkedHashSet<>();
        JSONArray denied=policy.getJSONArray("blockedPackages");
        for(int i=0;i<denied.length();i++)packages.add(denied.getString(i));
        return packages;
    }
    static boolean isAppAllowed(String pkg)throws Exception {
        PhoneExecutionScreens.packageName(pkg);
        JSONArray denied=snapshot().getJSONArray("blockedPackages");
        for(int i=0;i<denied.length();i++)if(pkg.equals(denied.getString(i)))return false;
        return true;
    }
    static void requireAppAllowed(String pkg)throws Exception {if(!isAppAllowed(pkg))throw new IOException("APP_NOT_ALLOWED");}
}
