package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Broker access to native audio ownership; thread identity is injected by the native tool runner. */
final class AudioBridge {
    static JSONObject control(JSONObject input) throws Exception {
        JSONObject params=new JSONObject(input.toString());params.remove("op");
        String action=params.getString("action");
        if(!"capabilities".equals(action)&&!"command".equals(action))throw new IOException("UNSUPPORTED_AUDIO_ACTION");
        if("command".equals(action)) {
            String thread=params.getString("threadId");
            if(!thread.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("INVALID_THREAD_ID");
            if(!params.has("matterId"))params.put("matterId",thread+"/"+params.getString("key"));
            if(!params.getString("matterId").startsWith(thread+"/"))throw new IOException("AUDIO_MATTER_MISMATCH");
            params.remove("key");
        }
        String output=command("/system/bin/content","call","--user","0","--uri","content://org.agentos.assistant.agent-gateway","--method","nativeAudio","--arg",params.toString());
        int start=output.indexOf("result="),end=output.lastIndexOf("}]");
        if(start<0||end<start)throw new IOException("AUDIO_GATEWAY_UNAVAILABLE");
        JSONObject envelope=new JSONObject(output.substring(start+7,end).trim());
        if(envelope.has("resultUri")) {
            String uri=envelope.getString("resultUri");
            if(!uri.startsWith("content://org.agentos.assistant.agent-gateway/result/"))throw new IOException("INVALID_AUDIO_RESULT_URI");
            envelope=new JSONObject(command("/system/bin/content","read","--user","0","--uri",uri));
        }
        if(!envelope.optBoolean("ok"))throw new IOException(envelope.optString("code","AUDIO_GATEWAY_UNAVAILABLE"));
        return envelope.getJSONObject("result");
    }
    private static String command(String... args) throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        FutureTask<String> reading=new FutureTask<>(()->{
            try(InputStream input=process.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1){output.write(buffer,0,count);if(output.size()>600000)throw new IOException("AUDIO_RESULT_TOO_LARGE");}
                return output.toString("UTF-8");
            }
        });
        Thread worker=new Thread(reading,"audio-provider-read");worker.setDaemon(true);worker.start();
        try {
            if(!process.waitFor(120,TimeUnit.SECONDS))throw new IOException("AUDIO_GATEWAY_TIMEOUT");
            String output=reading.get(2,TimeUnit.SECONDS);
            if(process.exitValue()!=0)throw new IOException("AUDIO_GATEWAY_DENIED");return output;
        } finally {process.destroyForcibly();}
    }
    static JSONObject definition() throws Exception {
        JSONObject properties=new JSONObject().put("action",enumeration("capabilities","command"))
                .put("key",PhoneNativeTools.string(64)).put("requestId",PhoneNativeTools.string(36))
                .put("operation",enumeration("start","pause","resume","stop","status","summarize"))
                .put("sessionId",PhoneNativeTools.string(36)).put("purpose",enumeration("DICTATION","MEDIA_TRANSLATE"))
                .put("language",enumeration("zh-CN","en-US","ja-JP","ko-KR"))
                .put("profileId",enumeration("zipformer","fal-kotoba","fal-grok","fal-whisper","local-hybrid"))
                .put("targetLanguage",enumeration("zh-CN","en-US","ja-JP","ko-KR"))
                .put("targetPackage",PhoneNativeTools.string(200)).put("recordAudio",new JSONObject().put("type","boolean"));
        return new JSONObject().put("type","function").put("name","audio")
                .put("description","Inspect actual installed Audio Engine capabilities or control an explicitly authorized meeting microphone / active app media caption session for an existing matter key. command requires key, stable UUID requestId and operation. start uses DICTATION (Chinese/English local recognition, real microphone recording default true) or MEDIA_TRANSLATE (active targetPackage playback only; targetLanguage required; cloud profiles require the user's configured provider). Other operations require native sessionId returned as source.resourceId. Pause finishes capture and saves its recording chunk; resume opens a new engine generation associated with the same matter. Stop saves final audio; status does not invent completion. summarize requires an explicitly requested, stopped DICTATION session with recognized final source text; it uses the configured authorized system text model, returns validated segment/quote evidence and nullable owner/due, and declares bounded partial coverage. It preserves the complete transcript and recordings. Caption timestamps are recognition-observed offsets, not word alignment. Arbitrary audio/video file ingestion and engine playback recording export are unavailable. Never claim captions, recording success, translation or summary before actual source output. No provider credentials are written by this tool.")
                .put("inputSchema",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray().put("action")).put("additionalProperties",false));
    }
    private static JSONObject enumeration(String... values) throws Exception {return new JSONObject().put("type","string").put("enum",new JSONArray(values));}
}
