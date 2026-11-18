package org.agentos.lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Root-only device storage. Credentials travel only over the authenticated local socket. */
final class SkillCredentials {
    private static final File ROOT=new File("/data/local/agentos/private/github");
    private static String repo(String value) throws IOException {
        if(!value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||value.startsWith("./")||value.startsWith("../")||value.endsWith("/.")||value.endsWith("/.."))throw new IOException("Repository must use the owner/repo format");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
    private static File file(String repo) throws Exception { return new File(ROOT,HarnessStore.hash(repo(repo))+".token"); }
    static JSONObject control(JSONObject request) throws Exception {
        String repository=repo(request.getString("repo")), action=request.getString("action");
        File token=file(repository);
        if(action.equals("status"))return new JSONObject().put("configured",token.isFile());
        if(action.equals("remove")) {
            if(token.exists()&&!token.delete())throw new IOException("Could not delete repository credentials");
            return new JSONObject().put("configured",false);
        }
        if(!action.equals("set"))throw new IOException("Unsupported credential operation");
        String value=request.getString("token");
        if(value.length()<8||value.length()>1024||!value.matches("[\\x21-\\x7e]+"))throw new IOException("Invalid GitHub credential format");
        if(!ROOT.mkdirs()&&!ROOT.isDirectory())throw new IOException("Device credential directory is unavailable");
        android.system.Os.chmod(ROOT.getParentFile().getPath(),0700);
        android.system.Os.chmod(ROOT.getPath(),0700);
        File temporary=new File(ROOT,token.getName()+".tmp");
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)) {
                android.system.Os.chmod(temporary.getPath(),0600);
                out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
            if(!temporary.renameTo(token))throw new IOException("Could not save repository credentials");
        } finally { if(temporary.exists())temporary.delete(); }
        return new JSONObject().put("configured",true);
    }
    static String get(String repository) throws Exception {
        File file=file(repository);if(!file.isFile())throw new IOException("No GitHub credentials are configured for this private repository. Add them in source settings");
        String value=TaskBroker.read(file);
        if(value.length()<8||value.length()>1024||!value.matches("[\\x21-\\x7e]+"))throw new IOException("Invalid GitHub credentials. Configure them again");
        return value;
    }
}
