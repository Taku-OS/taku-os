package org.agentos.lab;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Registered phone tool: real saved post -> device download/decoder -> actual model image inputs. */
final class NotebookVideoBridge {
    static JSONObject read(JSONObject args) throws Exception {
        if(args.length()!=1||!(args.opt("id") instanceof String)||!args.getString("id").matches("[a-f0-9]{64}"))
            throw new IOException("VIDEO_REFERENCE_INVALID");
        int user = PhoneAppCatalog.currentUser();
        String id=args.getString("id"), job=UUID.randomUUID().toString();
        File root=NotebookVideoReader.ROOT, dir=NotebookVideoReader.directory(job);
        var privateDirectory=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(root.toPath(),privateDirectory);
        if(!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("VIDEO_STORAGE_UNAVAILABLE");
        Files.createDirectory(dir.toPath(),privateDirectory);
        try {
            runWorker(id, job, user);
            NotebookBridge.requireCurrentUser(user);
            JSONObject response = toModelResult(id, dir, user);
            NotebookBridge.requireCurrentUser(user);
            return response;
        } finally {
            try {SkillUninstall.deleteTree(dir.toPath());}
            catch(IOException cleanupFailure) {android.util.Log.w("NotebookVideoBridge","VIDEO_TEMP_CLEANUP_FAILED");}
        }
    }

    private static void runWorker(String id, String job, int user) throws Exception {
        var builder=new ProcessBuilder("/system/bin/env",
            "CLASSPATH=/system_ext/framework/agentos-task-broker.jar","/system/bin/app_process","/system/bin",
            "org.agentos.lab.NotebookVideoReader",id,job,Integer.toString(user));
        var worker=builder.redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
        try {
            if(!worker.waitFor(45,TimeUnit.SECONDS))throw new IOException("VIDEO_WORKER_TIMEOUT");
            if(worker.exitValue()!=0)throw new IOException("VIDEO_WORKER_EXIT_"+worker.exitValue());
        } finally {
            if(worker.isAlive())worker.destroyForcibly();
        }
    }

    private static JSONObject toModelResult(String id, File dir, int user) throws Exception {
        File receipt=new File(dir,"result.json");
        if(!receipt.isFile())throw new IOException("VIDEO_RESULT_MISSING");
        JSONObject result=new JSONObject(TaskBroker.read(receipt));
        if(!result.optBoolean("ok"))throw new IOException(result.optString("code","VIDEO_UNAVAILABLE"));
        JSONArray sheets=result.getJSONArray("sheets");
        if(sheets.length()<1||sheets.length()>2||!id.equals(result.optString("referenceId"))
                || result.optInt("androidUserId", -1) != user
                ||!NotebookVideoSource.postId(result.optString("sourceUrl")).equals(result.optString("postId")))
            throw new IOException("VIDEO_RESULT_INVALID");
        result.remove("androidUserId");
        JSONObject response=PhoneNativeTools.text(result.toString());JSONArray content=response.getJSONArray("contentItems");
        for(int i=0;i<sheets.length();i++) {
            JSONObject sheet=sheets.getJSONObject(i);String file=sheet.getString("file");
            if(!file.matches("contact-[01]\\.jpg"))throw new IOException("VIDEO_RESULT_INVALID");
            File image=new File(dir,file);if(image.length()<12||image.length()>2*1024*1024)throw new IOException("VIDEO_RESULT_INVALID");
            byte[] bytes=Files.readAllBytes(image.toPath());
            if(!NotebookVideoSource.sha(bytes).equals(sheet.getString("sha256")))throw new IOException("VIDEO_FRAME_HASH_MISMATCH");
            content.put(new JSONObject().put("type","inputImage").put("imageUrl","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes)));
        }
        return response;
    }
}
