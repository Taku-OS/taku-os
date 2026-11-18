package org.agentos.lab;

import java.io.File;
import java.util.*;
import org.json.*;

/** Online test for a disposable emulator: install one pinned public skill, then remove it. */
public final class SkillCommunityUninstallTest {
    private static Set<String> stagingFiles() {
        File dir=new File(AgentSkills.HOME.getParentFile(),"taku-skill-staging");
        String[] names=dir.list();return names==null?new HashSet<>():new HashSet<>(Arrays.asList(names));
    }
    public static void main(String[] args) throws Exception {
        String name="gh-address-comments";
        File folder=new File(AgentSkills.HOME,name),body=new File(folder,"SKILL.md");
        if(folder.exists())throw new Exception("fixture destination already exists");
        Set<String> previous=stagingFiles();
        try(CodexAppServer server=new CodexAppServer(new File("/dev/null"),event -> {})) {
            server.initialize();String sha=SkillCommunity.commit(SkillCommunity.source("openai"));
            JSONObject receipt=SkillCommunity.install("openai",name,sha);
            if(!receipt.getString("commit").equals(sha)||!receipt.getString("archiveSha256").matches("[a-f0-9]{64}"))throw new Exception("missing provenance");
            AgentSkills.refresh(server,true);
            if(!AgentSkills.find(body.getPath()).getBoolean("nativeRecognized"))throw new Exception("native discovery failed");
            AgentSkills.control(server,"skills/community/uninstall",new JSONObject().put("path",body.getPath()));
            if(folder.exists() || !previous.equals(stagingFiles()))throw new Exception("installation or temporary files remain");
            System.out.println("PASS: public GitHub pinned install, native discovery, uninstall, staging cleanup; commit="+sha);
        }
        System.exit(0); // Android's HTTP connection pool may keep a standalone app_process alive.
    }
}
