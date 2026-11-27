package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Native skill discovery/configuration; managed installs have immutable provenance receipts. */
final class AgentSkills {
    static final File HOME=new File("/data/local/agentos/home/.codex/skills");
    private static JSONArray discovered=new JSONArray();
    static File staging() throws Exception {
        File parent=new File(HOME.getParentFile(),"taku-skill-staging");parent.mkdirs();
        android.system.Os.chmod(parent.getPath(),0700);
        File stage=new File(parent,UUID.randomUUID().toString());
        if(!stage.mkdir())throw new IOException("Skill staging unavailable");return stage;
    }
    static JSONObject metadata(File file) throws Exception {
        return metadata(TaskBroker.read(file));
    }
    static JSONObject metadata(String text) throws Exception {
        if(!text.startsWith("---\n")&&!text.startsWith("---\r\n"))throw new IOException("Skill YAML frontmatter missing");
        String[] lines=text.split("\r?\n");String name="",description="";boolean descriptionBlock=false;
        for(int i=1;i<lines.length&&!lines[i].equals("---");i++) {
            String line=lines[i];
            if(line.startsWith("name:"))name=scalar(line.substring(5).trim());
            if(line.startsWith("description:")) {
                description=scalar(line.substring(12).trim());descriptionBlock=description.matches("[>|][-+]?");
                if(descriptionBlock)description="";
            } else if(descriptionBlock&&line.startsWith(" "))description+=(description.isEmpty()?"":" ")+line.trim();
            else if(!line.startsWith(" "))descriptionBlock=false;
        }
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}")||description.isEmpty())throw new IOException("Skill name/description invalid");
        return new JSONObject().put("name",name).put("description",description);
    }
    private static String scalar(String value) {
        if(value.length()>1&&((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))return value.substring(1,value.length()-1);
        return value;
    }
    static void register(JSONObject value) throws Exception {
        File folder=new File(HarnessContext.store().root,"installed-skills");folder.mkdirs();
        File receipt=new File(folder,value.getString("name")+".json");
        if(receipt.exists())throw new IOException("Skill provenance receipt already exists");
        TaskBroker.write(receipt,HarnessStore.canonical(value));
    }
    static synchronized void ensureBuiltin() throws Exception {
        for(String name:new String[]{"taku-app-development","taku-system-ai-settings","taku-open-access","taku-plugin-creator","taku-figma-motion"})
            ensureBuiltin(name);
        retireBuiltin("taku-panels");
    }
    private static void ensureBuiltin(String name) throws Exception {
        File directory=new File(HOME,name);
        if(directory.exists()) {
            if(name.equals(BuiltinSkillUpdate.NAME)) {
                BuiltinSkillUpdate.ensure(new File(directory,"SKILL.md"),
                        new File(HarnessContext.store().root,"installed-skills/"+name+".json"),
                        HarnessCatalog.asset("assets/skills/"+name+"/SKILL.md"));
            } else upgradeBuiltin(name,directory);
            return;
        }
        File stage=staging();
        copyBundle(name,stage);
        if(!new File(stage,"SKILL.md").isFile())throw new IOException("Builtin development skill missing");
        HOME.mkdirs();if(!stage.renameTo(directory))throw new IOException("Builtin skill activation failed");
        reconcileBuiltinReceipt(name,directory);
    }
    /** Bundled instructions replace only a body the managed receipt still proves we wrote. */
    private static void upgradeBuiltin(String name,File directory) throws Exception {
        File instructions=new File(directory,"SKILL.md");if(!instructions.isFile())return;
        String bundled=bundled(name,"SKILL.md");if(bundled==null)return;
        String text=TaskBroker.read(instructions);
        if(text.equals(bundled)) { reconcileBuiltinReceipt(name,directory);return; }
        if(!ownedBuiltin(name,instructions.getPath(),text))return;
        copyBundle(name,directory);
        reconcileBuiltinReceipt(name,directory);
    }
    /** Remove a shipped skill the current APK no longer bundles; a custom copy stays untouched. */
    private static void retireBuiltin(String name) throws Exception {
        File directory=new File(HOME,name),provenance=builtinReceipt(name);
        if(!directory.isDirectory()) {
            if(provenance.isFile()&&"assistant-apk".equals(new JSONObject(TaskBroker.read(provenance)).optString("repo")))provenance.delete();
            return;
        }
        File instructions=new File(directory,"SKILL.md");if(!instructions.isFile())return;
        if(!ownedBuiltin(name,instructions.getPath(),TaskBroker.read(instructions)))return;
        File parent=new File(HOME.getParentFile(),"taku-skill-staging");parent.mkdirs();
        File retired=new File(parent,name+".retired-"+UUID.randomUUID());
        if(!directory.renameTo(retired))throw new IOException("Builtin skill retirement failed");
        if(provenance.isFile()&&!provenance.delete()) {
            if(!retired.renameTo(directory))throw new IOException("Builtin skill retirement failed");
            throw new IOException("Builtin skill retirement failed");
        }
        SkillUninstall.deleteTree(retired.toPath());
    }
    private static boolean ownedBuiltin(String name,String path,String text) throws Exception {
        File provenance=builtinReceipt(name);if(!provenance.isFile())return false;
        JSONObject value=new JSONObject(TaskBroker.read(provenance));
        return "assistant-apk".equals(value.optString("repo"))&&path.equals(value.optString("path"))
                &&HarnessStore.hash(text).equals(value.optString("bodySha256"));
    }
    private static File builtinReceipt(String name) throws Exception {
        return new File(HarnessContext.store().root,"installed-skills/"+name+".json");
    }
    private static String bundled(String name,String file) throws Exception {
        try(ZipFile apk=new ZipFile(HarnessCatalog.apk())) {
            ZipEntry entry=apk.getEntry("assets/skills/"+name+"/"+file);if(entry==null)return null;
            try(InputStream input=apk.getInputStream(entry)){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        }
    }
    private static void copyBundle(String name,File directory) throws Exception {
        try(ZipFile apk=new ZipFile(HarnessCatalog.apk())) {
            Enumeration<? extends ZipEntry> entries=apk.entries();String prefix="assets/skills/"+name+"/";
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();if(!entry.getName().startsWith(prefix)||entry.isDirectory())continue;
                File file=new File(directory,entry.getName().substring(prefix.length()));
                if(!file.getCanonicalPath().startsWith(directory.getCanonicalPath()+"/"))throw new IOException("Invalid builtin skill asset path: "+name);
                file.getParentFile().mkdirs();
                try(InputStream input=apk.getInputStream(entry)){TaskBroker.write(file,new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            }
        }
    }
    private static void reconcileBuiltinReceipt(String name,File directory) throws Exception {
        File instructions=new File(directory,"SKILL.md");if(!instructions.isFile())return;
        String bundled=bundled(name,"SKILL.md");if(bundled==null)throw new IOException("Builtin skill missing");
        String text=TaskBroker.read(instructions);if(!text.equals(bundled))return; // Preserve an independently modified skill.
        File folder=new File(HarnessContext.store().root,"installed-skills");folder.mkdirs();File receipt=new File(folder,name+".json");
        if(receipt.exists()) {JSONObject old=new JSONObject(TaskBroker.read(receipt));if(!"assistant-apk".equals(old.optString("repo"))||!instructions.getPath().equals(old.optString("path")))return;}
        String desired=HarnessStore.canonical(new JSONObject().put("name",name).put("path",instructions.getPath())
                .put("repo","assistant-apk").put("commit","builtin-1").put("bodySha256",HarnessStore.hash(text)));
        if(!receipt.exists()||!desired.equals(TaskBroker.read(receipt)))TaskBroker.write(receipt,desired);
    }
    static JSONArray refresh(CodexAppServer server,boolean force) throws Exception {
        ensureBuiltin();
        File builtin=new File(HOME,BuiltinSkillUpdate.NAME+"/SKILL.md");
        String digest=builtin.isFile()&&builtin.getCanonicalPath().equals(builtin.getAbsolutePath())?HarnessStore.hash(TaskBroker.read(builtin)):"", prior="";
        for(int i=0;i<discovered.length();i++)if(builtin.getPath().equals(discovered.getJSONObject(i).optString("path")))prior=discovered.getJSONObject(i).optString("bodySha256");
        JSONObject response=server.call("skills/list",new JSONObject().put("cwds",new JSONArray().put("/data/local/agentos/work")).put("forceReload",force||!digest.equals(prior)));
        JSONArray all=response.getJSONArray("data"),result=new JSONArray();
        for(int i=0;i<all.length();i++) {
            JSONArray skills=all.getJSONObject(i).optJSONArray("skills");if(skills==null)continue;
            for(int j=0;j<skills.length();j++) {
                JSONObject original=skills.getJSONObject(j);String path=original.optString("path");
                JSONObject row=new JSONObject().put("name",original.optString("name")).put("description",original.optString("description"))
                        .put("path",path).put("enabled",original.optBoolean("enabled",true)).put("nativeRecognized",true);
                SkillIcons.installed(row,new File(path).getParentFile(),original.optJSONObject("interface"));
                if(new File(path).isFile()&&new File(path).getCanonicalPath().startsWith(HOME.getCanonicalPath()+"/"))row.put("bodySha256",HarnessStore.hash(TaskBroker.read(new File(path))));
                File receipt=new File(HarnessContext.store().root,"installed-skills/"+row.getString("name")+".json");
                if(receipt.isFile())row.put("provenance",new JSONObject(TaskBroker.read(receipt))).put("managed",true).put("rollbackAvailable",SkillVersion.hasBackup(row.getString("name")));
                else row.put("managed",false);
                result.put(row);
            }
        }
        File receipts=new File(HarnessContext.store().root,"installed-skills");File[] managed=receipts.listFiles();
        if(managed!=null)for(File receipt:managed)if(receipt.getName().endsWith(".json")) {
            JSONObject provenance=new JSONObject(TaskBroker.read(receipt));boolean found=false;
            for(int i=0;i<result.length();i++)found|=result.getJSONObject(i).optString("name").equals(provenance.getString("name"));
            if(!found)result.put(new JSONObject().put("name",provenance.getString("name")).put("path",provenance.getString("path"))
                    .put("description","Downloaded, but not yet recognized by the runtime. Check the skill format and dependencies.").put("nativeRecognized",false).put("enabled",false).put("managed",true).put("rollbackAvailable",SkillVersion.hasBackup(provenance.getString("name"))).put("provenance",provenance));
        }
        for(int i=0;i<result.length();i++) {
            JSONObject row=result.getJSONObject(i), origin=row.optJSONObject("provenance");
            if(origin!=null&&BuiltinSkillUpdate.NAME.equals(row.optString("name"))&&origin.has("builtinUpdate"))
                row.put("builtinUpdate",origin.getJSONObject("builtinUpdate"));
        }
        List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<result.length();i++)ordered.add(result.getJSONObject(i));
        ordered.sort(Comparator.comparing(row->row.optString("path")));result=new JSONArray();for(JSONObject row:ordered)result.put(row);
        discovered=result;return result;
    }
    static JSONArray manifest() throws Exception {
        JSONArray output=new JSONArray();
        for(int i=0;i<discovered.length();i++) {
            JSONObject row=discovered.getJSONObject(i);
            JSONObject item=new JSONObject().put("name",row.getString("name"))
                    .put("description",row.optString("description"))
                    .put("enabled",row.getBoolean("enabled")).put("path",row.getString("path"))
                    .put("bodySha256",row.optString("bodySha256"));
            if(row.has("builtinUpdate"))item.put("builtinUpdate",row.getJSONObject("builtinUpdate"));
            output.put(item);
        }
        return output;
    }
    static JSONObject find(String path) throws Exception {
        for(int i=0;i<discovered.length();i++)if(discovered.getJSONObject(i).optString("path").equals(path))return discovered.getJSONObject(i);
        throw new IOException("Skill not discovered by native runtime");
    }
    static JSONArray selected(String thread) throws Exception {
        JSONArray paths=HarnessContext.store().state(thread).optJSONArray("selectedSkills");if(paths==null)return new JSONArray();
        JSONArray result=new JSONArray();
        for(int i=0;i<paths.length();i++) {
            // A skill can disappear outside this UI; an old selection must not block a turn.
            try { JSONObject skill=find(paths.getString(i));if(skill.optBoolean("nativeRecognized")&&skill.getBoolean("enabled"))result.put(skill); }
            catch(IOException missing) { /* Stale selections are ignored. */ }
        }
        return result;
    }
    static JSONArray selectedManifest(String thread) throws Exception {
        JSONArray full=selected(thread),result=new JSONArray();
        for(int i=0;i<full.length();i++) {
            JSONObject skill=full.getJSONObject(i);
            result.put(new JSONObject().put("name",skill.getString("name")).put("path",skill.getString("path"))
                    .put("bodySha256",skill.optString("bodySha256")));
        }
        return result;
    }
    static JSONObject control(CodexAppServer server,String method,JSONObject params) throws Exception {
        SkillUninstall.recover(server);
        if(!Arrays.asList("skills/read","skills/community/catalog","skills/community/detail","skills/community/add","skills/community/install","skills/community/uninstall","skills/community/check-update","skills/community/upgrade","skills/community/rollback","skills/set-enabled","skills/select").contains(method))throw new IOException("Unsupported skill operation");
        if(method.equals("skills/community/catalog"))return SkillCommunity.catalog(params.getString("sourceId"));
        if(method.equals("skills/community/detail"))return SkillCommunity.detail(params.getString("sourceId"),params.getString("name"),params.getString("commit"));
        if(method.equals("skills/community/add")) {SkillCommunity.add(params.getString("repo"),params.optString("path"),params.optBoolean("private"));return new JSONObject().put("sources",SkillCommunity.sources());}
        if(method.equals("skills/community/install")) {
            JSONObject receipt=SkillCommunity.install(params.getString("sourceId"),params.getString("name"),params.getString("commit"));
            return new JSONObject().put("installed",receipt).put("skills",refresh(server,true));
        }
        if(method.equals("skills/community/check-update"))return SkillVersion.check(params.getString("path"));
        if(method.equals("skills/community/upgrade") || method.equals("skills/community/rollback")) {
            String path=params.getString("path");
            if(method.endsWith("upgrade"))SkillVersion.upgrade(path,params.getString("commit"));
            else SkillVersion.rollback(path);
            return new JSONObject().put("skills",refresh(server,true));
        }
        if(method.equals("skills/community/uninstall")) {
            refresh(server,true);
            JSONObject skill=find(params.getString("path"));
            SkillUninstall.remove(server,skill);
            return new JSONObject().put("skills",refresh(server,true));
        }
        if(method.equals("skills/set-enabled")) {
            refresh(server,false);JSONObject skill=find(params.getString("path"));
            server.call("skills/config/write",new JSONObject().put("path",skill.getString("path")).put("enabled",params.getBoolean("enabled")));
            return new JSONObject().put("skills",refresh(server,true));
        }
        if(method.equals("skills/select")) {
            refresh(server,false);JSONObject skill=find(params.getString("path"));if(!skill.getBoolean("enabled"))throw new IOException("Skill disabled");
            String thread=params.getString("threadId");JSONObject state=HarnessContext.store().state(thread);
            JSONArray paths=state.optJSONArray("selectedSkills");if(paths==null)paths=new JSONArray();
            JSONArray next=new JSONArray();boolean exists=false;
            for(int i=0;i<paths.length();i++)if(paths.getString(i).equals(skill.getString("path")))exists=true;else next.put(paths.getString(i));
            if(!exists)next.put(skill.getString("path"));if(next.length()>4)throw new IOException("Select at most four skills per turn");
            state.put("selectedSkills",next);HarnessContext.store().state(thread,state);
            return new JSONObject().put("selected",next);
        }
        return new JSONObject().put("skills",refresh(server,params.optBoolean("forceReload",false)))
                .put("sources",SkillCommunity.sources()).put("selected",params.optString("threadId").matches("[a-f0-9-]{36}")?selected(params.getString("threadId")):new JSONArray());
    }
    static JSONObject readWhileRunning(String thread) throws Exception {
        return new JSONObject().put("skills",discovered).put("sources",SkillCommunity.sources())
                .put("selected",thread.matches("[a-f0-9-]{36}")?selected(thread):new JSONArray()).put("catalogFrozenForActiveTurn",true);
    }
}
