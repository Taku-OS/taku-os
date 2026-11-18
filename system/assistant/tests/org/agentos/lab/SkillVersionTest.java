package org.agentos.lab;

import java.io.File;
import org.json.JSONObject;

/** Disposable AVD: exercise two real immutable GitHub revisions without a model request. */
public final class SkillVersionTest {
    public static void main(String[] args) throws Exception {
        String name="gh-address-comments", old="e4131175b46f6aaf11d4b7b0df3b250fbfa58d5a";
        File dir=new File(AgentSkills.HOME,name), body=new File(dir,"SKILL.md");
        if(dir.exists())throw new Exception("fixture already exists");
        try(CodexAppServer server=new CodexAppServer(new File("/dev/null"),event -> {})) {
            server.initialize(); System.out.println("test: server initialized");
            System.out.println("test: installing pinned revision");
            JSONObject first=SkillCommunity.install("openai",name,old);
            JSONObject check=SkillVersion.check(body.getPath());
            if(!check.getBoolean("updateAvailable"))throw new Exception("expected newer revision");
            String latest=check.getString("latest");
            SkillVersion.upgrade(body.getPath(),latest);
            AgentSkills.refresh(server,true);
            JSONObject current=AgentSkills.find(body.getPath()).getJSONObject("provenance");
            if(!latest.equals(current.getString("commit")) || !SkillVersion.hasBackup(name))throw new Exception("upgrade failed");
            SkillVersion.rollback(body.getPath());
            AgentSkills.refresh(server,true);
            JSONObject restored=AgentSkills.find(body.getPath()).getJSONObject("provenance");
            if(!old.equals(restored.getString("commit")) || !first.getString("bodySha256").equals(restored.getString("bodySha256")))throw new Exception("rollback failed");
            if(!AgentSkills.find(body.getPath()).getBoolean("nativeRecognized"))throw new Exception("native discovery failed");
            if(args.length>0 && args[0].equals("keep")) { System.out.println("PASS: fixture retained for UI capture"); return; }
            SkillUninstall.remove(server,AgentSkills.find(body.getPath()));
            if(dir.exists()||SkillVersion.hasBackup(name))throw new Exception("uninstall did not remove backup");
            String brokenName="taku-version-recovery";
            File brokenDir=new File(AgentSkills.HOME,brokenName), brokenBody=new File(brokenDir,"SKILL.md");
            File slot=new File(AgentSkills.HOME.getParentFile(),"taku-skill-previous/"+brokenName);
            File priorDir=new File(slot,"skill"), priorBody=new File(priorDir,"SKILL.md");
            if(brokenDir.exists()||slot.exists())throw new Exception("recovery fixture already exists");
            brokenDir.mkdirs();priorDir.mkdirs();
            TaskBroker.write(brokenBody,"broken frontmatter\n");
            TaskBroker.write(priorBody,"---\nname: taku-version-recovery\ndescription: Version recovery fixture.\n---\nHello.\n");
            JSONObject broken=new JSONObject().put("name",brokenName).put("path",brokenBody.getPath()).put("repo","fixture/test")
                    .put("commit","1111111111111111111111111111111111111111").put("bodySha256",HarnessStore.hash(TaskBroker.read(brokenBody)));
            JSONObject prior=new JSONObject(broken.toString()).put("commit","0000000000000000000000000000000000000000")
                    .put("bodySha256",HarnessStore.hash(TaskBroker.read(priorBody)));
            AgentSkills.register(broken);TaskBroker.write(new File(slot,"receipt.json"),HarnessStore.canonical(prior));
            AgentSkills.refresh(server,true);JSONObject fallback=AgentSkills.find(brokenBody.getPath());
            if(fallback.getBoolean("nativeRecognized")||!fallback.optBoolean("rollbackAvailable"))throw new Exception("unrecognized skill cannot offer rollback");
            SkillVersion.rollback(brokenBody.getPath());AgentSkills.refresh(server,true);
            if(!AgentSkills.find(brokenBody.getPath()).getBoolean("nativeRecognized"))throw new Exception("rollback did not restore recognition");
            SkillUninstall.remove(server,AgentSkills.find(brokenBody.getPath()));
            System.out.println("PASS: pinned GitHub skill check, upgrade, native discovery, rollback, recovery and cleanup");
        }
        System.exit(0);
    }
}
