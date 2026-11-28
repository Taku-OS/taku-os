package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.zip.*;

/** Public GitHub community adapter. Resolves an immutable commit and installs files, never runs them. */
final class SkillCommunity {
    static JSONObject source(String id,String title,String repo,String path) throws Exception {
        return new JSONObject().put("id",id).put("title",title).put("repo",repo).put("path",path).put("ref","main");
    }
    static JSONArray sources() throws Exception {
        File file=new File(HarnessContext.store().root,"skill-sources.json");
        if(file.exists())return new JSONArray(TaskBroker.read(file));
        return new JSONArray().put(source("openai","OpenAI Skills","openai/skills","skills/.curated"))
                .put(source("anthropic","Anthropic community skills","anthropics/skills","skills"));
    }
    static JSONObject source(String id) throws Exception {
        JSONArray all=sources();for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))return all.getJSONObject(i);
        throw new IOException("Unknown skill source");
    }
    static void add(String repo,String path,boolean privateRepo) throws Exception {
        if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||repo.startsWith("./")||repo.startsWith("../")||repo.endsWith("/.")||repo.endsWith("/..")||path.contains("..")||path.startsWith("/")
                ||!path.matches("[A-Za-z0-9_./-]*"))throw new IOException("Use a public owner/repo and relative skill path");
        JSONArray all=sources();String id=HarnessStore.hash(repo+":"+path).substring(0,12);
        for(int i=0;i<all.length();i++)if(all.getJSONObject(i).optString("id").equals(id)) {
            all.getJSONObject(i).put("private",privateRepo);
            TaskBroker.write(new File(HarnessContext.store().root,"skill-sources.json"),all.toString());return;
        }
        if(all.length()>=10)throw new IOException("Source limit reached");
        all.put(source(id,repo,repo,path).put("private",privateRepo));TaskBroker.write(new File(HarnessContext.store().root,"skill-sources.json"),all.toString());
    }
    private static String encoded(String value) throws Exception {return URLEncoder.encode(value,"UTF-8").replace("+","%20");}
    private static byte[] fetch(JSONObject source,String location,int limit) throws Exception {
        return fetch(source,location,limit,"application/vnd.github+json");
    }
    private static final class CommunityError extends IOException { CommunityError(String message) { super(message); } }
    private static byte[] fetch(JSONObject source,String location,int limit,String accept) throws Exception {
        String token=source.optBoolean("private")?SkillCredentials.get(source.getString("repo")):null;
        try { return fetchWithToken(source,location,limit,accept,token); }
        catch(CommunityError safe) { throw new IOException(safe.getMessage()); }
        catch(Exception failure) { throw new IOException("GitHub network request failed. Check your network and proxy"); }
    }
    private static byte[] fetchWithToken(JSONObject source,String location,int limit,String accept,String token) throws Exception {
        URL url=new URL(location);
        for(int redirect=0;redirect<4;redirect++) {
            if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)||!java.util.Arrays.asList("api.github.com","codeload.github.com","github.com","raw.githubusercontent.com").contains(url.getHost()))
                throw new CommunityError("Unsupported community host");
            HttpURLConnection connection=PhoneNetwork.openConnection(url);
            connection.setConnectTimeout(12000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Taku-Skills/1");connection.setRequestProperty("Accept",accept);
            if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
            int code=connection.getResponseCode();
            if(code>=300&&code<400){String next=connection.getHeaderField("Location");connection.disconnect();url=new URL(url,next);continue;}
            if(code!=200){connection.disconnect();if(source.optBoolean("private")&&(code==401||code==403||code==404))throw new CommunityError("Cannot access the private repository. Check GitHub credentials and repository read permissions");throw new CommunityError("Community HTTP "+code);}
            try(InputStream input=connection.getInputStream()) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>limit)throw new CommunityError("Community package too large");}
                return bytes.toByteArray();
            } finally {connection.disconnect();}
        }
        throw new CommunityError("Community redirect limit");
    }
    private static String archiveRoot(ZipFile archive,JSONObject source,String sha) throws Exception {
        String[] repo=source.getString("repo").split("/");
        String api=repo[0]+"-"+repo[1]+"-"+sha.substring(0,7)+"/";
        String direct=repo[1]+"-"+sha+"/";
        java.util.Enumeration<? extends ZipEntry> entries=archive.entries();
        while(entries.hasMoreElements()) {
            String name=entries.nextElement().getName();int slash=name.indexOf('/');
            if(slash<0)continue;
            String root=name.substring(0,slash+1);
            if(root.equalsIgnoreCase(api)||root.equalsIgnoreCase(direct))return root;
        }
        throw new IOException("Community archive root does not match the commit");
    }
    private static JSONObject json(JSONObject source,String url) throws Exception {return new JSONObject(new String(fetch(source,url,1048576),java.nio.charset.StandardCharsets.UTF_8));}
    static String commit(JSONObject source) throws Exception {
        String sha=json(source,"https://api.github.com/repos/"+source.getString("repo")+"/commits/"+encoded(source.getString("ref"))).getString("sha");
        if(!sha.matches("[a-f0-9]{40}"))throw new IOException("Invalid community revision");return sha;
    }
    static JSONObject catalog(String id) throws Exception {
        JSONObject source=source(id);String sha=commit(source);
        String path=source.getString("path");
        JSONArray entries=new JSONArray(new String(fetch(source,"https://api.github.com/repos/"+source.getString("repo")+"/contents/"+path+"?ref="+sha,1048576),java.nio.charset.StandardCharsets.UTF_8));
        JSONArray result=new JSONArray();
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);String name=entry.optString("name");
            if(entry.optString("type").equals("dir")&&name.matches("[a-z0-9][a-z0-9-]{0,63}"))result.put(new JSONObject().put("name",name).put("sourceId",id).put("commit",sha));
        }
        // One bounded, pinned archive supplies real descriptions without one network call per row.
        try {
            File folder=new File(HarnessContext.store().root,"community-catalogs");folder.mkdirs();
            File cached=new File(folder,HarnessStore.hash(source.getString("repo"))+"-"+sha+".zip");
            if(!cached.isFile()) {
                byte[] bytes=fetch(source,"https://api.github.com/repos/"+source.getString("repo")+"/zipball/"+sha,33554432);
                File temporary=new File(folder,cached.getName()+".partial");
                try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
                if(!temporary.renameTo(cached))throw new IOException("Catalog cache activation failed");
            }
            try(ZipFile archive=new ZipFile(cached)) {
                String root=archiveRoot(archive,source,sha)+(path.isEmpty()?"":path+"/");
                for(int i=0;i<result.length();i++) {
                    JSONObject skill=result.getJSONObject(i);
                    SkillIcons.archived(skill,archive,root+skill.getString("name")+"/");
                    ZipEntry entry=archive.getEntry(root+skill.getString("name")+"/SKILL.md");
                    if(entry==null||entry.getSize()>131072)continue;
                    try(InputStream input=archive.getInputStream(entry)) {
                        ByteArrayOutputStream content=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                        while((n=input.read(buffer))!=-1){content.write(buffer,0,n);if(content.size()>131072)throw new IOException("Skill metadata limit");}
                        JSONObject metadata=AgentSkills.metadata(content.toString("UTF-8"));
                        if(metadata.getString("name").equals(skill.getString("name")))skill.put("description",metadata.getString("description"));
                    } catch(Exception unavailable) { /* Name-only entry remains usable; detail retries exact metadata. */ }
                }
            }
        } catch(Exception unavailable) { /* Browsing names remains available when bulk metadata cannot load. */ }
        return new JSONObject().put("source",source).put("commit",sha).put("skills",result);
    }
    static JSONObject detail(String sourceId,String name,String sha) throws Exception {
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}")||!sha.matches("[a-f0-9]{40}"))throw new IOException("Invalid skill selection");
        JSONObject source=source(sourceId);
        String path=(source.getString("path").isEmpty()?"":source.getString("path")+"/")+name+"/SKILL.md";
        String text=new String(fetch(source,"https://api.github.com/repos/"+source.getString("repo")+"/contents/"+path+"?ref="+sha,131072,"application/vnd.github.raw+json"),java.nio.charset.StandardCharsets.UTF_8);
        JSONObject result=AgentSkills.metadata(text);
        if(!name.equals(result.getString("name")))throw new IOException("Skill folder/name mismatch");
        File cached=new File(new File(HarnessContext.store().root,"community-catalogs"),HarnessStore.hash(source.getString("repo"))+"-"+sha+".zip");
        if(cached.isFile())try(ZipFile archive=new ZipFile(cached)) {
            String prefix=archiveRoot(archive,source,sha)+(source.getString("path").isEmpty()?"":source.getString("path")+"/")+name+"/";
            SkillIcons.archived(result,archive,prefix);
        } catch(IOException unavailable) { /* Optional logo cache must not block skill details. */ }
        return result.put("sourceId",sourceId).put("repo",source.getString("repo"))
                .put("commit",sha).put("instructions",text).put("bodySha256",HarnessStore.hash(text));
    }
    static JSONObject install(String sourceId,String name,String sha) throws Exception {
        File destination=new File(AgentSkills.HOME,name);
        if(destination.exists())throw new IOException("Skill already installed; existing files preserved");
        File stage=AgentSkills.staging();
        try {
            JSONObject receipt=prepare(sourceId,name,sha,stage);
            AgentSkills.HOME.mkdirs();
            if(!stage.renameTo(destination))throw new IOException("Atomic skill install failed");
            try { AgentSkills.register(receipt); }
            catch(Exception failure) { SkillUninstall.deleteTree(destination.toPath()); throw failure; }
            return receipt;
        } finally { if(stage.exists())SkillUninstall.deleteTree(stage.toPath()); }
    }
    static JSONObject prepare(String sourceId,String name,String sha,File stage) throws Exception {
        if(!name.matches("[a-z0-9][a-z0-9-]{0,63}")||!sha.matches("[a-f0-9]{40}"))throw new IOException("Invalid skill selection");
        JSONObject source=source(sourceId);
        byte[] zip=fetch(source,"https://api.github.com/repos/"+source.getString("repo")+"/zipball/"+sha,33554432);
        File destination=new File(AgentSkills.HOME,name);
        long bytes=0;int files=0;boolean scripts=false,mcp=false;
        File archive=new File(stage.getParentFile(),stage.getName()+".zip");
        try {
        try(FileOutputStream output=new FileOutputStream(archive)){output.write(zip);output.getFD().sync();}
        try(ZipFile packageZip=new ZipFile(archive)) {
            String prefix=archiveRoot(packageZip,source,sha)+(source.getString("path").isEmpty()?"":source.getString("path")+"/")+name+"/";
            java.util.Enumeration<? extends ZipEntry> entries=packageZip.entries();
            java.util.Set<String> names=new java.util.HashSet<>();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(!entry.getName().startsWith(prefix)||entry.isDirectory())continue;
                String relative=entry.getName().substring(prefix.length());
                if(relative.startsWith("/")||relative.contains("\\")||java.util.Arrays.asList(relative.split("/")).contains("..")||!names.add(relative))throw new IOException("Invalid archive path");
                if(relative.matches("(?i).*(^|/)(auth\\.json|credentials[^/]*|\\.env[^/]*|[^/]*\\.(pk8|pem|key|keystore))$"))throw new IOException("Credential-shaped files are not skill resources");
                if(++files>500)throw new IOException("Skill file limit");
                File file=new File(stage,relative);
                if(!file.getCanonicalPath().startsWith(stage.getCanonicalPath()+"/"))throw new IOException("Archive path escapes staging");
                file.getParentFile().mkdirs();
                try(InputStream input=packageZip.getInputStream(entry);FileOutputStream output=new FileOutputStream(file)) {
                    byte[] buffer=new byte[8192];int n;long size=0;
                    while((n=input.read(buffer))!=-1){bytes+=n;size+=n;if(bytes>20971520||size>8388608)throw new IOException("Skill expanded size limit");output.write(buffer,0,n);}
                }
                scripts|=relative.startsWith("scripts/");mcp|=relative.toLowerCase(java.util.Locale.ROOT).contains("mcp");
            }
        }
        File instructions=new File(stage,"SKILL.md");if(!instructions.isFile())throw new IOException("Selected folder has no SKILL.md");
        JSONObject metadata=AgentSkills.metadata(instructions);
        if(!metadata.getString("name").equals(name))throw new IOException("Skill folder/name mismatch");
        JSONObject installed=new JSONObject().put("name",name).put("path",new File(destination,"SKILL.md").getPath())
                .put("repo",source.getString("repo")).put("sourceId",sourceId).put("sourcePath",source.getString("path")).put("commit",sha).put("archiveSha256",HarnessStore.hash(zip))
                .put("bodySha256",HarnessStore.hash(TaskBroker.read(instructions)))
                .put("hasScripts",scripts).put("externalToolsMayBeRequired",mcp||scripts).put("installedAt",System.currentTimeMillis());
        return installed;
        } finally {
            if(archive.exists()&&!archive.delete())throw new IOException("Cannot remove temporary skill archive");
        }
    }
}
