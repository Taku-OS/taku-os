package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Local creator artifacts; plugin installation still uses the native plugin RPC. */
final class PluginCreator {
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
    private static void noLinks(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for (Iterator<Path> it=paths.iterator();it.hasNext();) {
                Path p=it.next();
                if (Files.isSymbolicLink(p) || (!Files.isDirectory(p) && !Files.isRegularFile(p)))
                    throw new IOException("Unsupported file or symlink: "+p);
            }
        }
    }
    static JSONObject validate(Path dir) throws Exception {
        dir=dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) throw new IOException("Plugin directory missing or symlinked");
        noLinks(dir);
        JSONObject m=new JSONObject(read(dir.resolve(".codex-plugin/plugin.json")));
        String name=m.getString("name"), version=m.getString("version");
        if (!name.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IOException("Invalid plugin name");
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+([-.][A-Za-z0-9.-]+)?")) throw new IOException("Invalid version");
        if (m.optString("description").trim().isEmpty()) throw new IOException("Description required");
        JSONObject taku=m.getJSONObject("taku");
        for (String key:new String[]{"capabilities","permissions"}) {
            JSONArray a=taku.getJSONArray(key);
            for(int i=0;i<a.length();i++) if (!(a.get(i) instanceof String) || a.getString(i).trim().isEmpty()) throw new IOException("Invalid "+key);
        }
        boolean hasContent=false;
        for(String key:new String[]{"skills","mcpServers"}) if(m.has(key)) {
            Object value=m.get(key);
            if (!(value instanceof String)) throw new IOException(key+" must be a relative path");
            String raw=(String)value; Path relative=Paths.get(raw), target=dir.resolve(relative).normalize();
            if(relative.isAbsolute() || !target.startsWith(dir) || !Files.exists(target)) throw new IOException("Invalid "+key+" path");
            if(key.equals("skills")) {
                if(!Files.isDirectory(target))throw new IOException("skills must point to a directory");
                boolean found=false;
                try(java.util.stream.Stream<Path> children=Files.list(target)) {
                    for(Iterator<Path> it=children.iterator();it.hasNext();) {
                        Path child=it.next();if(!Files.isDirectory(child))continue;
                        Path skill=child.resolve("SKILL.md");if(!Files.isRegularFile(skill))throw new IOException("Skill missing SKILL.md: "+child);
                        AgentSkills.metadata(read(skill));found=true;
                    }
                }
                if(!found)throw new IOException("No skills found");
            } else {
                if(!Files.isRegularFile(target))throw new IOException("mcpServers must point to a JSON file");
                new JSONObject(read(target)).getJSONObject("mcpServers");
            }
            hasContent=true;
        }
        if(!hasContent) throw new IOException("Declare skills or mcpServers");
        return new JSONObject().put("valid",true).put("name",name).put("version",version).put("manifest",m);
    }
    private static void copyTree(Path src, Path dest) throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(src)) {
            for(Iterator<Path> it=paths.iterator();it.hasNext();) {
                Path p=it.next(), target=dest.resolve(src.relativize(p));
                if(Files.isDirectory(p)) Files.createDirectories(target);
                else { Files.copy(p,target); if(Files.isExecutable(p.toFile().toPath())) target.toFile().setExecutable(true,false); }
            }
        }
    }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }
    // ZipOutputStream omits Unix modes; preserve executable scripts for community installs.
    private static void preserveZipModes(Path archive,Path plugin,String name) throws IOException {
        byte[] data=Files.readAllBytes(archive);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int end=-1;for(int i=data.length-22;i>=Math.max(0,data.length-65557);i--)if(b.getInt(i)==0x06054b50) {end=i;break;}
        if(end<0)throw new IOException("ZIP directory missing");
        int count=Short.toUnsignedInt(b.getShort(end+10)), pos=b.getInt(end+16);
        if(count==65535 || pos<0)throw new IOException("ZIP64 packages are not supported");
        for(int i=0;i<count;i++) {
            if(pos<0 || pos+46>data.length || b.getInt(pos)!=0x02014b50)throw new IOException("Invalid ZIP directory");
            int n=Short.toUnsignedInt(b.getShort(pos+28)), extra=Short.toUnsignedInt(b.getShort(pos+30)), comment=Short.toUnsignedInt(b.getShort(pos+32));
            if(pos+46+n+extra+comment>data.length)throw new IOException("Invalid ZIP entry");
            String entry=new String(data,pos+46,n,StandardCharsets.UTF_8), prefix=name+"/";
            if(!entry.startsWith(prefix))throw new IOException("Invalid ZIP entry path");
            Path original=plugin.resolve(entry.substring(prefix.length())).normalize();
            if(!original.startsWith(plugin))throw new IOException("Invalid ZIP entry path");
            data[pos+5]=3; // made by Unix
            b.putInt(pos+38,(Files.isExecutable(original)?0100755:0100644)<<16);
            pos+=46+n+extra+comment;
        }
        Files.write(archive,data,StandardOpenOption.TRUNCATE_EXISTING);
    }
    static JSONObject pack(Path source, Path output) throws Exception {
        source=source.toAbsolutePath().normalize();output=output.toAbsolutePath().normalize();
        JSONObject v=validate(source), m=v.getJSONObject("manifest");String name=v.getString("name"),version=v.getString("version");
        if(output.startsWith(source) || source.startsWith(output)) throw new IOException("Output and source must be separate");
        if(Files.exists(output)) throw new IOException("Output already exists: "+output);
        Files.createDirectories(output);
        Path plugin=output.resolve("plugin"), archive=output.resolve(name+"-"+version+".zip");
        copyTree(source,plugin);
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW)); java.util.stream.Stream<Path> paths=Files.walk(plugin)) {
            for(Iterator<Path> it=paths.sorted().iterator();it.hasNext();) {
                Path p=it.next(); if(!Files.isRegularFile(p))continue;
                zip.putNextEntry(new ZipEntry(name+"/"+plugin.relativize(p).toString().replace(File.separatorChar,'/')));
                Files.copy(p,zip);zip.closeEntry();
            }
        }
        preserveZipModes(archive,plugin,name);
        JSONObject item=new JSONObject().put("name",name).put("source",new JSONObject().put("source","local").put("path","./plugin"))
            .put("policy",new JSONObject().put("installation","AVAILABLE").put("authentication","ON_INSTALL")).put("category","Productivity");
        Path market=output.resolve(".agents/plugins/marketplace.json");
        write(market,new JSONObject().put("name","taku-creator-local").put("plugins",new JSONArray().put(item)).toString(2)+"\n");
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        Path index=output.resolve("index/"+name+"/"+version+".json");
        JSONObject record=new JSONObject().put("name",name).put("version",version).put("description",m.getString("description"))
            .put("package","packages/"+archive.getFileName()).put("sha256",sha).put("taku",m.getJSONObject("taku"));
        if(m.has("author"))record.put("author",m.get("author"));
        write(index,record.toString(2)+"\n");
        return new JSONObject().put("name",name).put("version",version).put("plugin",plugin.toString()).put("marketplace",market.toString())
            .put("package",archive.toString()).put("index",index.toString()).put("sha256",sha);
    }
    static JSONObject catalogAdd(Path packed) throws Exception {
        Path workspace=Paths.get("/data/local/agentos/work").toRealPath();
        Path plugin=packed.toRealPath().resolve("plugin").toRealPath();
        if(!plugin.startsWith(workspace)) throw new IOException("Packed plugin must be inside the workspace");
        JSONObject v=validate(plugin);String name=v.getString("name");
        Path catalog=workspace.resolve(".agents/plugins/marketplace.json");
        JSONObject market;
        if(Files.exists(catalog)) market=new JSONObject(read(catalog));
        else market=new JSONObject().put("name","taku-creator-local").put("plugins",new JSONArray());
        String marketName=market.getString("name");
        if(!marketName.matches("[A-Za-z0-9_.-]+"))throw new IOException("Invalid marketplace name");
        JSONArray plugins=market.getJSONArray("plugins");
        for(int i=0;i<plugins.length();i++) if(name.equals(plugins.getJSONObject(i).optString("name")))
            throw new IOException("Plugin name already exists in workspace catalog");
        String relative="./"+workspace.relativize(plugin).toString().replace(File.separatorChar,'/');
        plugins.put(new JSONObject().put("name",name).put("source",new JSONObject().put("source","local").put("path",relative))
                .put("policy",new JSONObject().put("installation","AVAILABLE").put("authentication","ON_INSTALL")).put("category","Productivity"));
        Files.createDirectories(catalog.getParent());
        Path temp=Files.createTempFile(catalog.getParent(),".taku-catalog-",".tmp");
        try {Files.write(temp,(market.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));Files.move(temp,catalog,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally {Files.deleteIfExists(temp);}
        return new JSONObject().put("marketplace",catalog.toString()).put("name",name).put("id",name+"@"+marketName);
    }
    private static String command(File cwd,String... command) throws Exception {
        Process p=new ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try { byte[] buf=new byte[4096];int n;while((n=p.getInputStream().read(buf))!=-1)output.write(buf,0,n); } catch(IOException ignored) {}});reader.start();
        if(!p.waitFor(60,TimeUnit.SECONDS)) {p.destroyForcibly();reader.join();throw new IOException("Command timed out: "+command[0]);}
        reader.join();String result=output.toString("UTF-8").trim();
        if(p.exitValue()!=0)throw new IOException(command[0]+" failed: "+result);
        return result;
    }
    static JSONObject publish(Path source,Path output,String repo) throws Exception {
        JSONObject result=pack(source,output);result.put("published",false);
        if(repo==null || repo.isEmpty()) repo="Taku-OS/taku-os-plugins";
        if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return result.put("reason","Invalid GitHub repository; artifacts are local");
        Path checkout=null;
        try {
            String owner=command(null,"gh","api","user","--jq",".login");
            command(null,"gh","repo","fork",repo,"--clone=false");
            checkout=Files.createTempDirectory("taku-plugin-publish-");
            String fork=owner+"/"+repo.substring(repo.indexOf('/')+1);
            command(null,"gh","repo","clone",fork,checkout.toString());
            File cwd=checkout.toFile(); String branch="plugin/"+result.getString("name")+"-"+UUID.randomUUID().toString().substring(0,8);
            command(cwd,"git","fetch","https://github.com/"+repo+".git","main");
            command(cwd,"git","checkout","--detach","FETCH_HEAD");
            command(cwd,"git","checkout","-b",branch);
            Path pkg=Paths.get(result.getString("package")), idx=Paths.get(result.getString("index"));
            Path destPkg=checkout.resolve("packages").resolve(pkg.getFileName()), destIdx=checkout.resolve("index").resolve(result.getString("name")).resolve(idx.getFileName());
            Files.createDirectories(destPkg.getParent());Files.createDirectories(destIdx.getParent());Files.copy(pkg,destPkg);Files.copy(idx,destIdx);
            command(cwd,"git","add","packages/"+pkg.getFileName(),"index/"+result.getString("name")+"/"+idx.getFileName());
            command(cwd,"git","commit","-m","Add plugin "+result.getString("name")+" "+result.getString("version"));
            command(cwd,"git","push","origin",branch);
            result.put("branch",branch);
            String url=command(cwd,"gh","pr","create","--repo",repo,"--head",owner+":"+branch,"--title","Add plugin "+result.getString("name")+" "+result.getString("version"),"--body","Community plugin package and index entry.");
            return result.put("published",true).put("pr",url);
        } catch(Exception e) { return result.put("reason","Community submission incomplete; artifacts are local. Use the authenticated host publisher: "+e.getMessage()); }
        finally { if(checkout!=null) { try(java.util.stream.Stream<Path> paths=Files.walk(checkout)) { for(Iterator<Path> it=paths.sorted(Comparator.reverseOrder()).iterator();it.hasNext();)Files.deleteIfExists(it.next()); } catch(IOException ignored) {} } }
    }
}
