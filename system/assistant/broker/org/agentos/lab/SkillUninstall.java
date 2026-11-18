package org.agentos.lab;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import org.json.*;

/** Removes only an installation owned by the community installer, never arbitrary skill paths. */
final class SkillUninstall {
    private SkillUninstall() {}

    static void remove(CodexAppServer server, JSONObject skill) throws Exception {
        String name=skill.getString("name"), path=skill.getString("path");
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}") || name.equals("taku-app-development"))
            throw new IOException("Built-in skills cannot be uninstalled");
        File receipt=new File(HarnessContext.store().root,"installed-skills/"+name+".json");
        if(!receipt.isFile())throw new IOException("Only community-installed skills can be uninstalled");
        JSONObject origin=new JSONObject(TaskBroker.read(receipt));
        if(origin.optString("repo").equals("assistant-apk") || !origin.optString("repo").matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IOException("Built-in or non-community skills cannot be uninstalled");
        File directory=new File(AgentSkills.HOME,name), instructions=new File(directory,"SKILL.md");
        if(!path.equals(instructions.getPath()) || !path.equals(origin.optString("path"))
                || !directory.getCanonicalPath().equals(directory.getAbsolutePath())
                || !instructions.getCanonicalPath().equals(instructions.getAbsolutePath()) || !directory.isDirectory())
            throw new IOException("Skill path or provenance does not match; no files were deleted");
        File journals=journals();
        File journal=new File(journals,name+".json");
        if(journal.exists())throw new IOException("This skill has an unfinished uninstall. Retry");
        JSONObject intent=new JSONObject().put("name",name).put("path",path).put("repo",origin.getString("repo"))
                .put("disabled",skill.optBoolean("nativeRecognized") && !skill.optBoolean("enabled"))
                .put("iconSha256",skill.optString("iconSha256"));
        TaskBroker.write(journal,HarnessStore.canonical(intent));
        finish(server,journal,intent);
    }

    private static File journals() throws Exception {
        File dir=new File(HarnessContext.store().root,"skill-uninstall-journal");
        if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("Could not create the uninstall journal directory");
        android.system.Os.chmod(dir.getPath(),0700);return dir;
    }
    private static File detached(String name) throws Exception {
        File base=new File(AgentSkills.HOME.getParentFile(),"taku-skill-uninstall");
        if(!base.mkdirs()&&!base.isDirectory())throw new IOException("Could not create the uninstall staging directory");
        android.system.Os.chmod(base.getPath(),0700);return new File(base,name+".detached");
    }
    /** Complete a previously requested uninstall; each step can be repeated after process death. */
    static void recover(CodexAppServer server) throws Exception {
        File[] files=journals().listFiles();if(files==null)throw new IOException("Could not read uninstall journals");
        java.util.Arrays.sort(files);
        for(File file:files)if(file.getName().endsWith(".json"))finish(server,file,new JSONObject(TaskBroker.read(file)));
    }
    private static void finish(CodexAppServer server,File journal,JSONObject intent) throws Exception {
        String name=intent.getString("name"),path=intent.getString("path"),repo=intent.getString("repo");
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}") || name.equals("taku-app-development")
                || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || !journal.getName().equals(name+".json")
                || !path.equals(new File(new File(AgentSkills.HOME,name),"SKILL.md").getPath()))
            throw new IOException("Invalid uninstall journal; inspection is required");
        File receipt=new File(HarnessContext.store().root,"installed-skills/"+name+".json");
        if(receipt.exists()) {
            JSONObject origin=new JSONObject(TaskBroker.read(receipt));
            if(!name.equals(origin.optString("name")) || !path.equals(origin.optString("path")) || !repo.equals(origin.optString("repo")))
                throw new IOException("Uninstall journal does not match provenance");
        }
        File directory=new File(AgentSkills.HOME,name),detached=detached(name);
        if(!directory.getCanonicalPath().equals(directory.getAbsolutePath()) || !detached.getCanonicalPath().equals(detached.getAbsolutePath()))
            throw new IOException("Invalid uninstall path");
        if(!receipt.exists() && directory.exists())throw new IOException("The skill directory reappeared during uninstall; inspection is required");
        if(directory.exists() && detached.exists())throw new IOException("Uninstall directory conflict; inspection is required");
        if(!directory.exists()&&!detached.exists()&&receipt.exists())throw new IOException("Uninstall directory is missing; inspection is required");
        if(intent.optBoolean("disabled"))server.call("skills/config/write",new JSONObject().put("path",path).put("enabled",true));
        if(directory.exists() && !directory.renameTo(detached))throw new IOException("Could not stage the skill. Retry uninstalling");
        JSONArray after=AgentSkills.refresh(server,true);
        for(int i=0;i<after.length();i++)if(path.equals(after.getJSONObject(i).optString("path")) && after.getJSONObject(i).optBoolean("nativeRecognized"))
            throw new IOException("The native runtime still recognizes this skill");
        clearSelections(path);
        if(receipt.exists()&&!receipt.delete())throw new IOException("Could not remove the provenance record");
        if(detached.exists())deleteTree(detached.toPath());
        SkillVersion.removeBackup(name);
        File catalogs=new File(HarnessContext.store().root,"community-catalogs");
        String prefix=HarnessStore.hash(repo)+"-";
        File[] archives=catalogs.listFiles();
        if(archives!=null)for(File file:archives)if(file.getName().startsWith(prefix) && !file.delete())
            throw new IOException("Skill removed, but community cache cleanup failed");
        String icon=intent.optString("iconSha256");
        if(icon.matches("[a-f0-9]{64}")) {
            File file=new File(HarnessContext.store().root,"skill-icons/"+icon+".image");
            if(file.exists()&&!file.delete())throw new IOException("Skill removed, but icon cache cleanup failed");
        }
        if(!journal.delete())throw new IOException("Could not clean up the uninstall journal");
    }

    private static void clearSelections(String path) throws Exception {
        HarnessStore store=HarnessContext.store();
        File[] threads=new File(store.root,"threads").listFiles();
        if(threads==null)return;
        for(File folder:threads) {
            String id=folder.getName();
            if(!folder.isDirectory() || !id.matches("[a-f0-9-]{36}"))continue;
            JSONObject state=store.state(id);JSONArray old=state.optJSONArray("selectedSkills");
            if(old==null)continue;
            JSONArray next=new JSONArray();
            for(int i=0;i<old.length();i++)if(!path.equals(old.getString(i)))next.put(old.getString(i));
            if(next.length()!=old.length()){state.put("selectedSkills",next);store.state(id,state);}
        }
    }

    static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException { Files.delete(file);return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error) throws IOException {
                if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;
            }
        });
    }
}
