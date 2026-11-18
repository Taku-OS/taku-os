package org.agentos.proactivity;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.agentos.proactivity.api.*;

/** Main-thread registry. Publish a new in-memory state only after AtomicFile commits. */
final class RegistryStore {
    private final AtomicFile file;
    private JSONObject root;
    RegistryStore(Context context) throws Exception {
        file=new AtomicFile(new File(context.getFilesDir(),"proactivity-v1.json"));
        try {
            if(file.getBaseFile().length()>262144)throw new IOException("Registry too large");
            try(InputStream input=file.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1){if(bytes.size()+count>262144)throw new IOException("Registry too large");bytes.write(buffer,0,count);}
                root=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            }
            if(root.getInt("version")!=1)throw new IOException("Unsupported registry version");
            root.getJSONObject("items");root.getLong("nextRevision");
        } catch(FileNotFoundException absent) {
            if(file.getBaseFile().exists())throw absent;
            root=new JSONObject().put("version",1).put("nextRevision",1).put("items",new JSONObject());
        }
    }
    static java.util.List<String> keys(JSONObject object) {
        java.util.List<String> result=new java.util.ArrayList<>();object.keys().forEachRemaining(result::add);return result;
    }
    JSONObject item(String key) throws Exception {
        JSONObject row=root.getJSONObject("items").optJSONObject(key);
        if(row==null)throw new IllegalArgumentException("Observer is not registered");
        return new JSONObject(row.toString());
    }
    JSONObject items() throws Exception {return new JSONObject(root.getJSONObject("items").toString());}
    private void commit(JSONObject next) throws Exception {
        byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144)throw new IOException("Registry storage budget exceeded");
        FileOutputStream output=null;
        try {output=file.startWrite();output.write(bytes);file.finishWrite(output);root=next;}
        catch(Exception error){if(output!=null)file.failWrite(output);throw error;}
    }
    private interface Change {void apply(JSONObject next,JSONObject row) throws Exception;}
    private void change(String key,Change operation) throws Exception {
        JSONObject next=new JSONObject(root.toString());JSONObject row=next.getJSONObject("items").getJSONObject(key);
        operation.apply(next,row);commit(next);
    }
    JSONObject register(String pkg,String signer,long codeVersion,ObserverSpec spec) throws Exception {
        if(!spec.id().startsWith(pkg+".") || !spec.eventType().startsWith(pkg+"."))
            throw new SecurityException("IDs must use the calling package namespace");
        String key=pkg+"/"+spec.id();JSONObject next=new JSONObject(root.toString()),items=next.getJSONObject("items");
        JSONObject row=items.optJSONObject(key);
        if(row==null) {
            int count=0;for(String k:keys(items))if(k.startsWith(pkg+"/"))count++;
            if(items.length()>=32 || count>=4)throw new IllegalArgumentException("Observer capacity exceeded");
            row=new JSONObject().put("package",pkg).put("signer",signer).put("id",spec.id())
                .put("approved",false).put("enabled",false).put("activeRevision",0)
                .put("pendingRevision",0).put("history",new JSONArray()).put("trace",new JSONArray())
                .put("lastOfferWall",0);items.put(key,row);
        }
        if(!row.getString("signer").equals(signer))throw new SecurityException("Registered signer changed");
        JSONArray history=row.getJSONArray("history");JSONObject encoded=ProactivityWire.spec(spec);
        JSONObject registered=row.optJSONObject("registration");
        if(registered!=null && registered.getLong("codeVersion")==codeVersion
                && ProactivityWire.spec(registered.getJSONObject("spec")).equals(spec))return item(key);
        row.put("registration",new JSONObject().put("codeVersion",codeVersion).put("spec",encoded));
        long revision=next.getLong("nextRevision");next.put("nextRevision",revision+1);
        history.put(new JSONObject().put("revision",revision).put("codeVersion",codeVersion).put("spec",encoded));
        while(history.length()>8)history.remove(history.getJSONObject(0).getLong("revision")==row.getLong("activeRevision")?1:0);
        row.put("pendingRevision",revision);addTrace(row,"registered",new JSONObject().put("revision",revision));
        commit(next);return item(key);
    }
    static JSONObject revision(JSONObject row,long revision) throws Exception {
        JSONArray history=row.getJSONArray("history");
        for(int i=0;i<history.length();i++)if(history.getJSONObject(i).getLong("revision")==revision)return history.getJSONObject(i);
        throw new IllegalStateException("Revision no longer available");
    }
    static ObserverSpec activeSpec(JSONObject row) throws Exception {
        return ProactivityWire.spec(revision(row,row.getLong("activeRevision")).getJSONObject("spec"));
    }
    static boolean active(JSONObject row) {
        return row.optBoolean("approved") && row.optBoolean("enabled") && row.optLong("activeRevision")>0 && row.optLong("pendingRevision")==0;
    }
    void approve(String key,long expectedRevision) throws Exception {
        change(key,(next,row)->{
            long pending=row.getLong("pendingRevision");
            if((pending>0?pending:row.getLong("activeRevision"))!=expectedRevision)throw new IllegalStateException("Policy updated. Review the latest version before confirming");
            if(pending>0)row.put("activeRevision",pending);
            if(row.getLong("activeRevision")==0)throw new IllegalStateException("No revision");
            row.put("pendingRevision",0).put("approved",true).put("enabled",true);
            addTrace(row,"approved",new JSONObject().put("revision",row.getLong("activeRevision")));
        });
    }
    void disable(String key) throws Exception {change(key,(next,row)->{row.put("enabled",false);addTrace(row,"disabled",new JSONObject());});}
    void editPolicy(String key,long expectedRevision,ObserverSpec spec)throws Exception {
        change(key,(next,row)->{
            if(row.getLong("activeRevision")!=expectedRevision || row.getLong("pendingRevision")!=0)
                throw new IllegalStateException("Policy changed. Reopen it before editing");
            ObserverSpec before=activeSpec(row);
            if(!before.id().equals(spec.id()) || !before.eventType().equals(spec.eventType()) || !before.name().equals(spec.name())
                    || !before.title().equals(spec.title()) || !before.summary().equals(spec.summary()))
                throw new IllegalArgumentException("Only timing and context limits are editable");
            if(before.equals(spec))return;
            long revision=next.getLong("nextRevision");next.put("nextRevision",revision+1);
            JSONArray history=row.getJSONArray("history");
            history.put(new JSONObject().put("revision",revision).put("codeVersion",RegistryStore.revision(row,expectedRevision).getLong("codeVersion"))
                .put("spec",ProactivityWire.spec(spec)).put("author","user"));while(history.length()>8)history.remove(0);
            row.put("activeRevision",revision);addTrace(row,"policy_edited",new JSONObject().put("revision",revision).put("previousRevision",expectedRevision));
        });
    }
    void remove(String key) throws Exception {JSONObject next=new JSONObject(root.toString());next.getJSONObject("items").remove(key);commit(next);}
    void rollback(String key,long expectedRevision) throws Exception {
        change(key,(next,row)->{
            if(row.getLong("activeRevision")!=expectedRevision)throw new IllegalStateException("Policy updated. Review it again before rolling back");
            JSONArray history=row.getJSONArray("history");JSONObject previous=null;
            for(int i=0;i<history.length();i++) {
                JSONObject h=history.getJSONObject(i);
                if(h.getLong("revision")==row.getLong("activeRevision"))break;
                previous=h;
            }
            if(previous==null)throw new IllegalStateException("No policy version is available for rollback");
            long revision=next.getLong("nextRevision");next.put("nextRevision",revision+1);
            JSONObject restored=new JSONObject(previous.toString()).put("revision",revision).put("rollbackOf",previous.getLong("revision"));
            history.put(restored);while(history.length()>8)history.remove(0);
            row.put("activeRevision",revision).put("pendingRevision",0);
            addTrace(row,"rolled_back",new JSONObject().put("revision",revision).put("restoredFrom",restored.getLong("rollbackOf")));
        });
    }
    void trace(String key,String type,JSONObject details) throws Exception {change(key,(next,row)->addTrace(row,type,details));}
    void offered(String key,JSONObject details) throws Exception {
        change(key,(next,row)->{row.put("lastOfferWall",System.currentTimeMillis());addTrace(row,"published",details);});
    }
    private static void addTrace(JSONObject row,String type,JSONObject details) throws Exception {
        JSONArray trace=row.getJSONArray("trace");trace.put(new JSONObject().put("type",type)
            .put("at",System.currentTimeMillis()).put("details",details));while(trace.length()>16)trace.remove(0);
    }
}
