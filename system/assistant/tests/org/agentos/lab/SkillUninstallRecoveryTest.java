package org.agentos.lab;

import java.io.File;
import org.json.*;

/** Simulates broker death at durable uninstall boundaries on the disposable AVD. */
public final class SkillUninstallRecoveryTest {
    static void check(boolean value,String message)throws Exception {if(!value)throw new Exception(message);}
    public static void main(String[] args)throws Exception {
        String name="taku-recovery-fixture", thread="22222222-2222-2222-2222-222222222222";
        File dir=new File(AgentSkills.HOME,name), body=new File(dir,"SKILL.md"), root=HarnessContext.store().root;
        File journalDir=new File(root,"skill-uninstall-journal"), journal=new File(journalDir,name+".json");
        File detached=new File(AgentSkills.HOME.getParentFile(),"taku-skill-uninstall/"+name+".detached"), receipt=new File(root,"installed-skills/"+name+".json");
        journalDir.mkdirs();detached.getParentFile().mkdirs();
        try(CodexAppServer server=new CodexAppServer(new File("/dev/null"),event->{})) {
            server.initialize();SkillUninstall.recover(server);
            check(!dir.exists()&&!journal.exists()&&!detached.exists()&&!receipt.exists(),"fixture already exists");
            for(int phase=0;phase<3;phase++) {
                dir.mkdirs();TaskBroker.write(body,"---\nname: "+name+"\ndescription: Recovery fixture.\n---\nTest.\n");
                JSONObject origin=new JSONObject().put("name",name).put("path",body.getPath()).put("repo","fixture/test");AgentSkills.register(origin);
                HarnessContext.store().state(thread,new JSONObject().put("selectedSkills",new JSONArray().put(body.getPath())));
                if(phase==1)server.call("skills/config/write",new JSONObject().put("path",body.getPath()).put("enabled",false));
                TaskBroker.write(journal,new JSONObject(origin.toString()).put("disabled",phase==1).toString());
                if(phase>=1)check(dir.renameTo(detached),"detach fixture");
                if(phase==2)check(receipt.delete(),"remove fixture receipt");
                SkillUninstall.recover(server);SkillUninstall.recover(server);
                check(!dir.exists()&&!detached.exists()&&!journal.exists()&&!receipt.exists(),"recovery left files at phase "+phase);
                check(HarnessContext.store().state(thread).getJSONArray("selectedSkills").length()==0,"selection remains");
            }
            dir.mkdirs();TaskBroker.write(body,"new install");
            JSONObject conflict=new JSONObject().put("name",name).put("path",body.getPath()).put("repo","fixture/test");
            TaskBroker.write(journal,conflict.toString());
            boolean rejected=false;try {SkillUninstall.recover(server);}catch(Exception expected){rejected=true;}
            check(rejected&&body.exists()&&journal.exists(),"reappeared directory must not be deleted");
            SkillUninstall.deleteTree(dir.toPath());check(journal.delete(),"remove conflict fixture");
            System.out.println("PASS: repeated recovery before detach, after detach, after receipt removal, and conflict protection");
        }
    }
}
