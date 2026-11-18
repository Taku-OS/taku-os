package org.agentos.lab;

import java.io.File;
import org.json.*;

/** Run only on a fresh dedicated emulator with the pinned Codex runtime and assistant installed. */
public final class SkillUninstallTest {
    private static void check(boolean value,String message) throws Exception {
        if(!value)throw new Exception(message);
    }
    public static void main(String[] args) throws Exception {
        String name="taku-uninstall-fixture",thread="11111111-1111-1111-1111-111111111111";
        File folder=new File(AgentSkills.HOME,name), body=new File(folder,"SKILL.md");
        check(!folder.exists(),"fixture already exists");
        folder.mkdirs();TaskBroker.write(body,"---\nname: taku-uninstall-fixture\ndescription: Dedicated uninstall test fixture.\n---\nTest only.\n");
        JSONObject origin=new JSONObject().put("name",name).put("path",body.getPath()).put("repo","fixture/test")
                .put("commit","0000000000000000000000000000000000000000");
        AgentSkills.register(origin);
        HarnessStore store=HarnessContext.store();
        store.state(thread,new JSONObject().put("selectedSkills",new JSONArray().put(body.getPath())));
        File cacheDir=new File(store.root,"community-catalogs");cacheDir.mkdirs();
        File cached=new File(cacheDir,HarnessStore.hash("fixture/test")+"-fixture.zip");TaskBroker.write(cached,"fixture");
        try(CodexAppServer server=new CodexAppServer(new File("/dev/null"),event -> {})) {
            server.initialize();
            AgentSkills.refresh(server,true);
            JSONObject before=AgentSkills.find(body.getPath());
            check(before.optBoolean("nativeRecognized"),"native runtime did not recognize fixture");
            check(AgentSkills.selected(thread).length()==1,"selection missing");
            boolean rejected=false;
            try { SkillUninstall.remove(server,AgentSkills.find(new File(AgentSkills.HOME,"taku-app-development/SKILL.md").getPath())); }
            catch(Exception expected) { rejected=true; }
            check(rejected,"built-in deletion was accepted");
            server.call("skills/config/write",new JSONObject().put("path",body.getPath()).put("enabled",false));
            AgentSkills.refresh(server,true);before=AgentSkills.find(body.getPath());
            check(!before.getBoolean("enabled"),"fixture was not disabled");
            AgentSkills.control(server,"skills/community/uninstall",new JSONObject().put("path",body.getPath()));
            JSONArray after=AgentSkills.refresh(server,true);
            for(int i=0;i<after.length();i++)check(!body.getPath().equals(after.getJSONObject(i).optString("path")),"fixture still discovered");
            check(!folder.exists()&&!cached.exists(),"files or cache remain");
            check(!new File(store.root,"installed-skills/"+name+".json").exists(),"receipt remains");
            check(store.state(thread).getJSONArray("selectedSkills").length()==0,"selection remains");
            check(new File(AgentSkills.HOME,"taku-app-development/SKILL.md").isFile(),"built-in missing");
            folder.mkdirs();TaskBroker.write(body,"---\nname: taku-uninstall-fixture\ndescription: Dedicated uninstall test fixture.\n---\nTest only.\n");
            AgentSkills.register(origin);AgentSkills.refresh(server,true);
            check(AgentSkills.find(body.getPath()).getBoolean("enabled"),"reinstall inherited disabled setting");
            AgentSkills.control(server,"skills/community/uninstall",new JSONObject().put("path",body.getPath()));
            System.out.println("PASS: native discovery and removal, selection, cache and built-in protection");
        }
    }
}
