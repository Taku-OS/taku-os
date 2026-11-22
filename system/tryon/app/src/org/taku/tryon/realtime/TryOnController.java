// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import org.taku.tryon.data.FalKeyStore;
import org.taku.tryon.data.XmaxKeyStore;

/** The single validation and state path for native UI and the on-device Agent API. */
public final class TryOnController {
    public static final String OUTFIT = "outfit", CHARACTER = "character";
    private static volatile boolean visible;
    private static Request previous;
    private TryOnController() {}
    static void visible(boolean value) { visible = value; }
    public static final class Request {
        final String mode, prompt;
        final Uri reference;
        final long referenceExpires;
        final RealtimeCallConfiguration configuration;
        Request(String mode, Uri reference, String prompt, long referenceExpires, RealtimeCallConfiguration configuration) {
            this.mode = mode; this.reference = reference; this.prompt = prompt;
            this.referenceExpires = referenceExpires; this.configuration = configuration;
        }
        Bundle bundle() {
            Bundle b = new Bundle(); b.putString("mode",mode); b.putString("referenceUri",reference.toString());
            b.putString("prompt",prompt); b.putLong("referenceExpires",referenceExpires);
            b.putString("provider",configuration.providerId()); b.putString("region",configuration.environment); return b;
        }
        static Request from(Context context, Bundle b) {
            if (b == null) throw new IllegalArgumentException("invalid_request");
            String mode = b.getString("mode",OUTFIT); validateMode(mode);
            String raw = b.getString("referenceUri",""); Uri uri = Uri.parse(raw);
            if (!"content".equals(uri.getScheme()) || raw.length() > 2048) throw new IllegalArgumentException("reference_required");
            String prompt = b.getString("prompt","").trim();
            if (prompt.length() > 2000) throw new IllegalArgumentException("invalid_prompt");
            RealtimeCallConfiguration config = configuration(context,mode);
            return new Request(mode,uri,prompt,b.getLong("referenceExpires",0),config);
        }
    }
    static RealtimeCallConfiguration configuration(Context c,String mode) {
        return CHARACTER.equals(mode) ? RealtimeCallConfiguration.loadCosplay(c) : RealtimeCallConfiguration.loadShopping(c);
    }
    private static void validateMode(String mode) {
        if (!OUTFIT.equals(mode) && !CHARACTER.equals(mode)) throw new IllegalArgumentException("invalid_mode");
    }
    /** Visible image/prompt draft, shared by the activity and Agent. No model work. */
    public static synchronized Bundle draft(Context context, Bundle args) {
        return draft(context,args,false);
    }
    private static synchronized Bundle draft(Context context,Bundle args,boolean referenceChanged) {
        android.content.SharedPreferences prefs=context.getSharedPreferences("tryon-draft",Context.MODE_PRIVATE);
        if(args!=null) {
            if(!validArguments(args,"mode","referenceUri","prompt","referenceExpires"))return result(false,"invalid_arguments");
            if(CallTryOnService.busy())return result(false,"busy");
            String mode=args.getString("mode",prefs.getString("mode",OUTFIT));
            String uri=args.getString("referenceUri",prefs.getString("referenceUri",""));
            String prompt=args.getString("prompt",prefs.getString("prompt",""));
            try{validateMode(mode);}catch(IllegalArgumentException invalid){return result(false,"invalid_mode");}
            if(!uri.isEmpty()&&(!"content".equals(Uri.parse(uri).getScheme())||uri.length()>2048))return result(false,"reference_required");
            if(prompt.length()>2000)return result(false,"invalid_prompt");
            long expires=args.getLong("referenceExpires",prefs.getLong("referenceExpires",0));
            if(expires<0)return result(false,"invalid_request");
            if(!referenceChanged&&mode.equals(prefs.getString("mode",OUTFIT))&&uri.equals(prefs.getString("referenceUri",""))&&prompt.equals(prefs.getString("prompt",""))&&expires==prefs.getLong("referenceExpires",0))return draft(context,null);
            if(!prefs.edit().putLong("revision",prefs.getLong("revision",0)+1).putString("mode",mode).putString("referenceUri",uri).putString("prompt",prompt).putLong("referenceExpires",expires).commit())return result(false,"storage_failed");
        }
        Bundle out=result(true,"draft");
        out.putLong("revision",prefs.getLong("revision",0));out.putString("mode",prefs.getString("mode",OUTFIT));out.putString("referenceUri",prefs.getString("referenceUri",""));
        out.putString("prompt",prefs.getString("prompt",""));out.putLong("referenceExpires",prefs.getLong("referenceExpires",0));return out;
    }
    static synchronized Bundle importedReference(Context context, byte[] jpeg) {
        return importedReference(context,jpeg,0);
    }
    static synchronized Bundle importedReference(Context context,byte[] jpeg,long referenceExpires) {
        if(referenceExpires<0)return result(false,"invalid_request");
        if(referenceExpires!=0&&SystemClock.elapsedRealtime()>=referenceExpires)return result(false,"reference_expired");
        if(CallTryOnService.busy())return result(false,"busy");
        android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(context.getFilesDir(),"imported-reference.jpg"));
        java.io.FileOutputStream out=null;
        try {
            out=file.startWrite();out.write(jpeg);
            if(referenceExpires!=0&&SystemClock.elapsedRealtime()>=referenceExpires){file.failWrite(out);return result(false,"reference_expired");}
            Bundle b=new Bundle();b.putString("referenceUri","content://org.taku.tryon.api/reference/current");b.putLong("referenceExpires",referenceExpires);
            Bundle before=draft(context,null),saved;
            try{saved=draft(context,b,true);}catch(RuntimeException failed){saved=result(false,"storage_failed");}
            if(!saved.getBoolean("ok")){file.failWrite(out);out=null;restoreImportedDraft(context,before);return saved;}
            file.finishWrite(out);return saved;
        }catch(java.io.IOException|RuntimeException failed){if(out!=null)file.failWrite(out);return result(false,"storage_failed");}
    }
    private static void restoreImportedDraft(Context context,Bundle before) {
        // commit(false) can already change SharedPreferences memory; restore only this draft.
        // This is process-local failure rollback, not a cross-file crash transaction.
        try{context.getSharedPreferences("tryon-draft",Context.MODE_PRIVATE).edit()
                .putLong("revision",before.getLong("revision")).putString("mode",before.getString("mode"))
                .putString("referenceUri",before.getString("referenceUri")).putString("prompt",before.getString("prompt"))
                .putLong("referenceExpires",before.getLong("referenceExpires")).commit();}catch(RuntimeException ignored){}
    }
    public static synchronized Bundle configure(Context context, Bundle args) {
        try {
            if (!validArguments(args,"mode","provider","region")) return result(false,"invalid_arguments");
            if (CallTryOnService.busy()) return result(false,"busy");
            String mode = args.getString("mode",OUTFIT); validateMode(mode);
            RealtimeCallConfiguration before = configuration(context,mode);
            String provider = args.getString("provider",before.providerId());
            if (!"lucy".equals(provider) && !"xmax".equals(provider)) return result(false,"invalid_provider");
            RealtimeCallConfiguration next = new RealtimeCallConfiguration("lucy".equals(provider)
                    ? RealtimeCallConfiguration.Provider.LUCY : RealtimeCallConfiguration.Provider.XMAX,
                    args.getString("region",before.environment));
            if (CHARACTER.equals(mode)) next.saveCosplay(context); else next.saveShopping(context);
            return status(context);
        } catch (IllegalArgumentException invalid) { return result(false,"invalid_configuration"); }
        catch (Exception failure) { return result(false,"storage_failed"); }
    }
    public static synchronized Bundle credential(Context context,String provider,String key,boolean clear) {
        try {
            if (CallTryOnService.busy()) return result(false,"busy");
            if ("lucy".equals(provider)) { FalKeyStore store = new FalKeyStore(context); if(clear)store.clear();else store.set(key); }
            else if ("xmax".equals(provider)) { XmaxKeyStore store = new XmaxKeyStore(context); if(clear)store.clear();else store.set(key); }
            else return result(false,"invalid_provider");
            return result(true,"configured");
        } catch (IllegalArgumentException invalid) { return result(false,"invalid_credential"); }
        catch (Exception failure) { return result(false,"credential_storage_failed"); }
    }
    public static synchronized Bundle start(Context context,Bundle args) {
        try {
            if(!validArguments(args,"mode","referenceUri","prompt","referenceExpires"))return result(false,"invalid_arguments");
            Bundle values=draft(context,null); if(args!=null)values.putAll(args);
            return start(context,Request.from(context,values));
        }
        catch (IllegalArgumentException invalid) { return result(false,fixedRequestError(invalid.getMessage())); }
    }
    static synchronized Bundle start(Context context,Request request) {
        if (CallTryOnService.busy()) return result(false,"busy");
        if (ReferenceImport.writing()) return result(false,"reference_import_busy");
        if (!visible) return result(false,"foreground_required");
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return result(false,"camera_permission_required");
        if (!Settings.canDrawOverlays(context)) return result(false,"overlay_permission_required");
        if (request.referenceExpires != 0 && SystemClock.elapsedRealtime() >= request.referenceExpires) return result(false,"reference_expired");
        if (!request.configuration.hasKey(context)) return result(false,"missing_key");
        long generation=CallTryOnService.reserve();if(generation==0)return result(false,"busy");
        try {
            Intent service = new Intent(context,CallTryOnService.class).putExtras(request.bundle()).putExtra("attemptGeneration",generation);
            context.startForegroundService(service); previous=request;
            Bundle accepted=result(true,"starting");accepted.putLong("attemptGeneration",generation);return accepted;
        } catch (RuntimeException denied) { CallTryOnService.releaseReservation(generation); return result(false,"foreground_service_denied"); }
    }
    public static synchronized Bundle retry(Context context) {
        if (previous==null) return result(false,"no_previous_request");
        Request old=previous;
        return start(context,new Request(old.mode,old.reference,old.prompt,old.referenceExpires,configuration(context,old.mode)));
    }
    public static Bundle stop(Context context) { new android.os.Handler(android.os.Looper.getMainLooper()).post(TryOnResultWindow::dismissCurrent);CallTryOnService.control("stop"); return result(true,"stopping"); }
    public static Bundle presentation(String value) { boolean active=CallTryOnService.control(value);return result(active,active?"requested":"no_active_session"); }
    /** Absolute window coordinates use the same clamped move operation as a GUI drag. */
    public static Bundle move(Bundle args) {
        int x,y;
        try {
            if(args==null||args.keySet().size()!=2||!args.keySet().containsAll(java.util.Set.of("x","y")))return result(false,"invalid_arguments");
            x=moveCoordinate(args.get("x"));y=moveCoordinate(args.get("y"));
        } catch(RuntimeException malformed) { return result(false,"invalid_arguments"); }
        boolean moved=CallTryOnService.move(x,y);
        return result(moved,moved?"requested":"presentation_unavailable");
    }
    private static int moveCoordinate(Object value) {
        if(!(value instanceof Integer)&&!(value instanceof Long))throw new IllegalArgumentException("invalid_arguments");
        long coordinate=((Number)value).longValue();
        if(coordinate<Integer.MIN_VALUE||coordinate>Integer.MAX_VALUE)throw new IllegalArgumentException("invalid_arguments");
        return (int)coordinate;
    }
    public static Bundle status(Context context) {
        try {
            JSONObject json = new JSONObject();
            Bundle d=draft(context,null);
            json.put("draft",new JSONObject().put("mode",d.getString("mode")).put("referenceUri",d.getString("referenceUri"))
                    .put("prompt",d.getString("prompt")).put("referenceExpires",d.getLong("referenceExpires")));
            json.put("referenceImport",ReferenceImport.status());
            json.put("schema","taku.tryon.v1"); json.put("foreground",visible);
            json.put("cameraPermission",context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED);
            json.put("overlayPermission",Settings.canDrawOverlays(context));
            json.put("lucyKeyConfigured",new FalKeyStore(context).hasKey()); json.put("xmaxKeyConfigured",new XmaxKeyStore(context).hasKey());
            for(String mode:new String[]{OUTFIT,CHARACTER}) {
                RealtimeCallConfiguration c=configuration(context,mode);
                json.put(mode,new JSONObject().put("provider",c.providerId()).put("region",c.environment));
            }
            CallTryOnService.Snapshot s=CallTryOnService.snapshot();
            json.put("state",s.state).put("detail",s.detail).put("code",s.code).put("active",s.active)
                    .put("cleanupPending",CallTryOnService.busy()&&!s.active).put("remainingSeconds",s.remainingSeconds)
                    .put("sourceFrames",s.sourceFrames).put("returnedFrames",s.returnedFrames).put("presentation",s.presentation);
            Bundle bounds=CallTryOnService.position();
            JSONObject position=new JSONObject().put("canMove",bounds.getBoolean("canMove"));
            for(String key:new String[]{"x","y","width","height"})if(bounds.get(key) instanceof Integer)position.put(key,bounds.get(key));
            json.put("position",position);
            Bundle out=result(true,"status");out.putString("json",json.toString());return out;
        } catch(Exception failure){return result(false,"status_failed");}
    }
    public static Bundle diagnostics(Context context) {
        Bundle out=result(true,"diagnostics");out.putString("json",new CallTryOnDiagnosticStore(context).read().toString());return out;
    }
    public static boolean validArguments(Bundle values,String... allowed) {
        if(values==null)return true;
        java.util.Set<String> names=new java.util.HashSet<>(java.util.Arrays.asList(allowed));
        try {
            for(String key:values.keySet()) {
                if(!names.contains(key))return false;
                Object value=values.get(key);
                if("referenceExpires".equals(key)) {if(!(value instanceof Long))return false;}
                else if(!(value instanceof String))return false;
            }
            return true;
        }catch(RuntimeException malformed){return false;}
    }
    public static Bundle result(boolean ok,String code) { Bundle b=new Bundle();b.putBoolean("ok",ok);b.putString("code",code);return b; }
    private static String fixedRequestError(String code) {
        return "invalid_mode".equals(code)||"reference_required".equals(code)||"invalid_prompt".equals(code)?code:"invalid_request";
    }
}
