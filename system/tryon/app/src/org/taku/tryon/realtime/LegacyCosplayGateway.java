// Versioned Assistant character-preview handoff into the native app controller.
package org.taku.tryon.realtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.taku.cosplay.api.CosplayContract;

/** Exact local voice Bundle protocol; generation uses the same native controller as the GUI. */
public final class LegacyCosplayGateway {
    private static final Set<String> METHODS=Set.of(CosplayContract.STATUS,CosplayContract.MODEL_STATUS,
            CosplayContract.PREPARE,CosplayContract.CANCEL,CosplayContract.CONFIGURE,CosplayContract.REPORT_VOICE);
    private LegacyCosplayGateway(){}
    public static boolean handles(String method){return METHODS.contains(method);}
    public static Bundle call(Context context,String method,String arg,Bundle extras,String callingPackage){
        int uid=Binder.getCallingUid();
        boolean mutation=CosplayContract.PREPARE.equals(method)||CosplayContract.CANCEL.equals(method)||CosplayContract.REPORT_VOICE.equals(method);
        if(mutation&&!trustedAssistant(context,uid,callingPackage))return response(false,"This caller cannot prepare voice character preview.");
        long identity=Binder.clearCallingIdentity();
        try {
            if(arg!=null&&!arg.isEmpty())return response(false,"Use the documented voice fields.");
            if(CosplayContract.STATUS.equals(method)||CosplayContract.MODEL_STATUS.equals(method)){
                if(extras!=null&&!extras.isEmpty()&&!(extras.keySet().equals(Set.of("schema"))&&CosplayContract.SCHEMA.equals(extras.get("schema"))))
                    return response(false,"Use the documented voice fields.");
                return state(context,CosplayContract.MODEL_STATUS.equals(method));
            }
            if(CosplayContract.CONFIGURE.equals(method))return configure(context,extras);
            if(CosplayContract.REPORT_VOICE.equals(method))return response(false,"Voice metrics are not stored by this app.");
            if(CosplayContract.CANCEL.equals(method)){
                if(extras==null||!extras.keySet().equals(Set.of("schema","ticket"))||!CosplayContract.SCHEMA.equals(extras.get("schema"))
                        ||!(extras.get("ticket") instanceof String)||!CosplayPolicy.validTicket(extras.getString("ticket")))
                    return response(false,"Invalid character cancellation.");
                CosplayRequestStore.discard(extras.getString("ticket"));return response(true,"Pending character preview cancelled.");
            }
            return prepare(context,extras);
        }catch(RuntimeException|OutOfMemoryError invalid){return response(false,"Character preview is unavailable. Return to TikTok and ask again.");}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    private static android.content.SharedPreferences prefs(Context context){return context.getSharedPreferences("cosplay-settings",Context.MODE_PRIVATE);}
    static boolean ready(Context context){return state(context,false).getBoolean("ready");}
    static boolean enabled(Context context){return prefs(context).getBoolean("enabled",false);}
    static boolean voiceOnly(Context context){return prefs(context).getBoolean("voiceOnly",true);}
    private static boolean permissions(Context context){return context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
            &&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED&&Settings.canDrawOverlays(context);}
    private static Bundle state(Context context,boolean metadata){
        boolean enabled=enabled(context),voiceOnly=voiceOnly(context),key=TryOnController.configuration(context,TryOnController.CHARACTER).hasKey(context);
        boolean permissions=permissions(context),busy=CallTryOnService.busy();
        String message=!enabled?"Turn on voice character preview first.":busy?"Close the previous camera preview and wait for cleanup.":voiceOnly?"Voice recognition is ready; camera preview is paused.":!key?"Save the selected character model key first.":!permissions?"Allow camera, notifications and display over other apps first.":"Character preview is ready.";
        Bundle result=envelope(LegacyWire.status(enabled,enabled&&!busy&&(voiceOnly||key&&permissions),key,permissions,voiceOnly,message));
        if(metadata){RealtimeCallConfiguration config=TryOnController.configuration(context,TryOnController.CHARACTER);
            result.putString("speechSettingsScope",CosplayContract.SPEECH_SETTINGS_SCOPE);
            result.putString("speechSettingsActivity",CosplayContract.SPEECH_SETTINGS_PACKAGE+"/"+CosplayContract.SPEECH_SETTINGS_ACTIVITY);
            result.putString("modelLabel",config.provider==RealtimeCallConfiguration.Provider.LUCY?"Lucy 2.6":"XMax");
            result.putString("provider",config.providerId());result.putString("environment",config.environment);
            result.putString("effectScope","outfit_accessories");result.putBoolean("preserveFaceAndHairRequested",true);}
        return result;
    }
    private static Bundle configure(Context context,Bundle values){
        if(values!=null&&values.containsKey("speechLanguage"))return response(false,"Voice language is managed by Taku Audio Assistant settings.");
        if(values==null||!CosplayContract.SCHEMA.equals(values.get("schema"))||values.size()<2
                ||!Set.of("schema","enabled","voiceOnly","provider","environment").containsAll(values.keySet()))return response(false,"Invalid voice settings.");
        for(String key:values.keySet())if((key.equals("enabled")||key.equals("voiceOnly"))?!(values.get(key) instanceof Boolean):!(values.get(key) instanceof String))return response(false,"Invalid voice settings.");
        if(CallTryOnService.busy())return response(false,"Stop the previous preview before changing settings.");
        Bundle config=new Bundle();config.putString("mode",TryOnController.CHARACTER);
        if(values.containsKey("provider"))config.putString("provider",values.getString("provider"));
        if(values.containsKey("environment"))config.putString("region",values.getString("environment"));
        Bundle configured=TryOnController.configure(context,config);if(!configured.getBoolean("ok"))return response(false,"Invalid character model settings.");
        android.content.SharedPreferences.Editor edit=prefs(context).edit();
        if(values.containsKey("enabled"))edit.putBoolean("enabled",values.getBoolean("enabled"));
        if(values.containsKey("voiceOnly"))edit.putBoolean("voiceOnly",values.getBoolean("voiceOnly"));
        return edit.commit()?state(context,true):response(false,"Could not save voice settings.");
    }
    private static Bundle prepare(Context context,Bundle extras){
        byte[] owned=null;
        try {
            if(voiceOnly(context))return response(false,"Voice recognition only; camera preview is paused.");
            if(extras==null||!extras.keySet().equals(Set.of("schema","jpeg","capturedAtElapsedMs","invokedAtElapsedMs","command","sourcePackage","requestId")))return response(false,"Invalid voice request.");
            Object raw=extras.get("jpeg");if(!(raw instanceof byte[])||((byte[])raw).length>CosplayContract.MAX_JPEG_BYTES)return response(false,"Invalid reference image.");
            owned=((byte[])raw).clone();Map<String,Object> fields=new HashMap<>();
            for(String name:extras.keySet())fields.put(name,"jpeg".equals(name)?owned:extras.get(name));
            CosplayPolicy.Request request=CosplayPolicy.validate(fields,SystemClock.elapsedRealtime());
            if(!ready(context)||!validJpeg(owned))return response(false,"Check character model settings and permissions, then ask again.");
            long now=SystemClock.elapsedRealtime();request=CosplayPolicy.validate(fields,now);
            if(!ready(context)||voiceOnly(context))return response(false,"Character preview is not ready.");
            CosplayRequestStore.Prepared prepared=CosplayRequestStore.prepare(request,now);
            return envelope(LegacyWire.prepared(prepared.ticket(),prepared.expiresAtElapsedMs(),"Character preview is ready to open."));
        }catch(IllegalArgumentException|IllegalStateException invalid){return response(false,"This character request expired or was already used. Ask again from TikTok.");}
        finally{if(owned!=null)Arrays.fill(owned,(byte)0);}
    }
    private static boolean validJpeg(byte[] bytes){
        Bitmap image=null;
        try{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            if(!"image/jpeg".equals(bounds.outMimeType)||!CosplayPolicy.imageDimensionsAllowed(bounds.outWidth,bounds.outHeight))return false;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
            image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            return image!=null&&image.getWidth()==bounds.outWidth&&image.getHeight()==bounds.outHeight;
        }catch(RuntimeException|OutOfMemoryError invalid){return false;}finally{if(image!=null)image.recycle();}
    }
    private static boolean trustedAssistant(Context context,int uid,String packageName){
        if(!CosplayContract.ASSISTANT_PACKAGE.equals(packageName))return false;
        try{PackageInfo info=context.getPackageManager().getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES);
            if(info.applicationInfo==null||info.signingInfo==null||info.signingInfo.hasMultipleSigners())return false;
            var signatures=info.signingInfo.getApkContentsSigners();if(signatures==null||signatures.length!=1)return false;
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());StringBuilder signer=new StringBuilder();
            for(byte value:digest)signer.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            boolean platform=context.getPackageManager().checkSignatures("android",packageName)==PackageManager.SIGNATURE_MATCH;
            return CosplayPolicy.trustedAssistant(uid,packageName,info.applicationInfo.uid,signer.toString(),platform);
        }catch(Exception unavailable){return false;}
    }
    private static Bundle response(boolean ok,String message){Bundle out=new Bundle();out.putString("schema",CosplayContract.SCHEMA);out.putBoolean("ok",ok);out.putString("userMessage",message);return out;}
    private static Bundle envelope(Map<String,Object> values){Bundle out=new Bundle();for(var field:values.entrySet()){
        if(field.getValue() instanceof String)out.putString(field.getKey(),(String)field.getValue());
        else if(field.getValue() instanceof Boolean)out.putBoolean(field.getKey(),(Boolean)field.getValue());
        else out.putLong(field.getKey(),(Long)field.getValue());}return out;}
}
