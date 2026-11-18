package org.agentos.lab;

import java.io.*;
import org.json.*;

/** Version swaps are confined to an installer-owned skill and its original source. */
final class SkillVersion {
    private static File receipt(String name) throws Exception { return new File(HarnessContext.store().root,"installed-skills/"+name+".json"); }
    private static File backups() { return new File(AgentSkills.HOME.getParentFile(),"taku-skill-previous"); }
    private static JSONObject origin(String path) throws Exception {
        File body=new File(path), dir=body.getParentFile();
        String name=dir==null?"":dir.getName();
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}") || name.equals("taku-app-development")
                || !body.getPath().equals(new File(new File(AgentSkills.HOME,name),"SKILL.md").getPath())
                || !body.isFile() || !body.getCanonicalPath().equals(body.getAbsolutePath()))
            throw new IOException("Only installed community skills can be managed");
        File file=receipt(name);
        if(!file.isFile())throw new IOException("Skill provenance record does not exist");
        JSONObject value=new JSONObject(TaskBroker.read(file));
        if(!path.equals(value.optString("path")) || !name.equals(value.optString("name")) || !value.optString("repo").matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IOException("Skill path or provenance does not match");
        if(!HarnessStore.hash(TaskBroker.read(body)).equals(value.optString("bodySha256")))throw new IOException("Skill instructions were modified locally and were not overwritten");
        return value;
    }
    private static JSONObject source(JSONObject origin) throws Exception {
        JSONArray all=SkillCommunity.sources();JSONObject found=null;
        for(int i=0;i<all.length();i++) {
            JSONObject item=all.getJSONObject(i);
            if(!item.optString("repo").equals(origin.optString("repo")))continue;
            if(origin.has("sourceId") && !item.optString("id").equals(origin.optString("sourceId")))continue;
            if(origin.has("sourcePath") && !item.optString("path").equals(origin.optString("sourcePath")))continue;
            if(found!=null)throw new IOException("The original source is not unique. Resolve the sources first");
            found=item;
        }
        if(found==null)throw new IOException("The original source no longer exists or has changed");
        return found;
    }
    static JSONObject check(String path) throws Exception {
        JSONObject old=origin(path), source=source(old);String latest=SkillCommunity.commit(source);
        return new JSONObject().put("current",old.optString("commit")).put("latest",latest)
                .put("updateAvailable",!latest.equals(old.optString("commit")))
                .put("rollbackAvailable",new File(backups(),old.getString("name")+"/receipt.json").isFile());
    }
    static void upgrade(String path,String expected) throws Exception {
        JSONObject old=origin(path), source=source(old);String latest=SkillCommunity.commit(source);
        if(!latest.equals(expected))throw new IOException("The source has a newer commit. Check for updates again");
        if(latest.equals(old.optString("commit")))throw new IOException("Already at the current commit");
        File stage=AgentSkills.staging();
        try { JSONObject next=SkillCommunity.prepare(source.getString("id"),old.getString("name"),latest,stage); swap(old,next,stage); }
        finally { if(stage.exists())SkillUninstall.deleteTree(stage.toPath()); }
    }
    static void rollback(String path) throws Exception {
        JSONObject old=origin(path);File slot=new File(backups(),old.getString("name"));
        File prior=new File(slot,"skill"), record=new File(slot,"receipt.json");
        if(!prior.isDirectory()||!record.isFile())throw new IOException("No version is available to roll back to");
        JSONObject next=new JSONObject(TaskBroker.read(record));
        if(!next.optString("name").equals(old.optString("name"))||!next.optString("repo").equals(old.optString("repo"))
                ||!next.optString("path").equals(path)||!HarnessStore.hash(TaskBroker.read(new File(prior,"SKILL.md"))).equals(next.optString("bodySha256")))
            throw new IOException("Previous version record does not match; no rollback was performed");
        File stage=AgentSkills.staging();
        if(!stage.delete()||!prior.renameTo(stage))throw new IOException("Could not stage the previous version");
        try { swap(old,next,stage); }
        catch(Exception failure) { if(stage.exists()&&!prior.exists())stage.renameTo(prior); throw failure; }
    }
    private static void swap(JSONObject old,JSONObject next,File stage) throws Exception {
        String name=old.getString("name");File active=new File(AgentSkills.HOME,name),base=backups();
        if(!base.mkdirs()&&!base.isDirectory())throw new IOException("Could not create the version directory");
        android.system.Os.chmod(base.getPath(),0700);
        File slot=new File(base,name), replacement=AgentSkills.staging(), parked=AgentSkills.staging();
        if(!parked.delete())throw new IOException("Could not stage the version directory");
        boolean parkedOld=false, movedActive=false, installedSlot=false, activated=false;
        try {
            TaskBroker.write(new File(replacement,"receipt.json"),HarnessStore.canonical(old));
            if(!active.renameTo(new File(replacement,"skill")))throw new IOException("Could not stage the current version");
            movedActive=true;
            if(slot.exists()) {
                if(!slot.renameTo(parked))throw new IOException("Could not preserve the old backup");
                parkedOld=true;
            }
            if(!replacement.renameTo(slot))throw new IOException("Could not save the previous version");
            installedSlot=true;
            if(!stage.renameTo(active))throw new IOException("Could not activate the target version");
            activated=true;
            TaskBroker.write(receipt(name),HarnessStore.canonical(next));
        } catch(Exception failure) {
            if(activated && !active.renameTo(stage))throw new IOException("Version switch failed and the target could not be restored",failure);
            File saved=installedSlot?new File(slot,"skill"):new File(replacement,"skill");
            if(movedActive && !saved.renameTo(active))throw new IOException("Version switch failed and the original could not be restored",failure);
            if(installedSlot)SkillUninstall.deleteTree(slot.toPath());
            if(parkedOld && !parked.renameTo(slot))throw new IOException("Version switch failed and the backup could not be restored",failure);
            throw failure;
        } finally {
            if(replacement.exists())SkillUninstall.deleteTree(replacement.toPath());
        }
        if(parkedOld)SkillUninstall.deleteTree(parked.toPath());
    }
    static boolean hasBackup(String name) { return new File(backups(),name+"/receipt.json").isFile(); }
    static void removeBackup(String name) throws Exception {
        File slot=new File(backups(),name);if(slot.exists())SkillUninstall.deleteTree(slot.toPath());
    }
}
