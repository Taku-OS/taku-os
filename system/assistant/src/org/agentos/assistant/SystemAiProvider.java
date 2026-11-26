package org.agentos.assistant;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Shared, permission-gated AI API. Caller identity and application policy cannot be supplied by clients. */
public final class SystemAiProvider extends ContentProvider {
 private static final String NOTEBOOK = "org.agentos.notebook";
 private static final String JPEG = "data:image/jpeg;base64,";
 private static final Pattern BASE64_JPEG = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
 private static final Set<String> NOTEBOOK_OPERATIONS = Set.of(
         "notebookStatus", "notebookFile", "notebookResolveSocialSource");

 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  int uid=Binder.getCallingUid();Bundle out=new Bundle();
  try{
   if(!"ai".equals(method)||arg==null||arg.length()>400000)throw new IllegalArgumentException("Invalid AI request");
   JSONObject source=new JSONObject(arg);String action=source.optString("action");
   if (NOTEBOOK_OPERATIONS.contains(action)) {
    requireNotebookCaller(uid);
    long token = Binder.clearCallingIdentity();
    try {
     out.putString("result", new JSONObject().put("ok", true).put("result", notebook(action, source)).toString());
    } finally {
     Binder.restoreCallingIdentity(token);
    }
    return out;
   }
   if(!Set.of("read","resolve","generate","configure").contains(action))throw new IllegalArgumentException("Unknown system AI operation");
   if(action.equals("configure")){
    if(!AppPermissionCatalog.isSystemOrShellUid(uid)&&!AppPermissionCatalog.isTrustedEnforcementHost(getContext(),uid))
     throw new SecurityException("System AI configuration denied");
   }else{
    getContext().enforceCallingPermission("org.agentos.assistant.permission.USE_AI","System AI access denied");
   }
   String consumer="";
   if(!action.equals("configure")){
    String[] packages=getContext().getPackageManager().getPackagesForUid(uid);if(packages==null||packages.length!=1)throw new SecurityException("AI caller identity is ambiguous");
    String pkg=packages[0];consumer=pkg;
    if(pkg.equals("org.agentos.memory")&&getContext().getPackageManager().checkSignatures(pkg,getContext().getPackageName())==android.content.pm.PackageManager.SIGNATURE_MATCH)consumer="memory";
    if(pkg.equals("org.agentos.inputmethod"))for(String digest:getContext().getResources().getStringArray(R.array.ime_reply_signers)){byte[] cert=new byte[digest.length()/2];for(int i=0;i<cert.length;i++)cert[i]=(byte)Integer.parseInt(digest.substring(i*2,i*2+2),16);if(getContext().getPackageManager().hasSigningCertificate(pkg,cert,android.content.pm.PackageManager.CERT_INPUT_SHA256))consumer="ime";}
    if(!consumer.equals("memory")&&!consumer.equals("ime")&&!AppPermissionProvider.isAllowedLocal(getContext(),uid,AppPermissionCatalog.SCOPE_TAKU_CONTROL))
     throw new SecurityException("PERMISSION_DENIED");
   }
   JSONObject request=new JSONObject().put("op","systemAi").put("action",action).put("consumer",consumer);
   for(String key:new String[]{"role","model","prompt","image"})if(source.has(key))request.put(key,source.get(key));
   if(action.equals("configure"))request.put("patch",source.getJSONObject("patch"));
   if(!consumer.equals("ime")&&!consumer.equals("memory"))request.remove("model");
   long token=Binder.clearCallingIdentity();try{
    if(!action.equals("configure"))NativeClient.exchange(new JSONObject().put("op","systemAi").put("action","register").put("consumer",consumer));
    JSONObject result=NativeClient.exchange(request);
    if(action.equals("read"))result=new JSONObject().put("enabled",result.optBoolean("enabled")&&result.getJSONObject("apps").optBoolean(consumer)).put("revision",result.getLong("revision")).put("models",result.getJSONArray("models"));
    out.putString("result",new JSONObject().put("ok",true).put("result",result).toString());
   }finally{Binder.restoreCallingIdentity(token);}
  }catch(SecurityException e){throw e;}catch(Exception e){try{out.putString("result",new JSONObject().put("ok",false).put("message",e.getMessage()==null?"System AI temporarily unavailable":e.getMessage()).toString());}catch(Exception ignored){}}
  return out;
 }

 private void requireNotebookCaller(int uid) {
  getContext().enforceCallingPermission("org.agentos.assistant.permission.USE_AI", "System AI access denied");
  AppPermissionProvider.enforceLocal(getContext(), uid, AppPermissionCatalog.SCOPE_TAKU_CONTROL);
  PackageManager packages = getContext().getPackageManager();
  String[] names = packages.getPackagesForUid(uid);
  if (names == null || names.length != 1 || !NOTEBOOK.equals(names[0])
          || !AppPermissionCatalog.isPlatformSigned(packages, NOTEBOOK)) {
   throw new SecurityException("Notebook AI requires the platform-signed Notebook caller");
  }
 }

 private JSONObject notebook(String action, JSONObject source) throws Exception {
  switch (action) {
   case "notebookStatus":
    requireFields(source, "action");
    return NativeClient.exchange(notebookRequest("notebookSourceStatus"));
   case "notebookFile":
    requireFields(source, "action", "prompt", "image");
    return NativeClient.exchange(notebookRequest("notebookFile")
            .put("prompt", text(source, "prompt", 48_000)).put("image", jpeg(source, "image")));
   case "notebookResolveSocialSource":
    requireFields(source, "action", "sourcePackage", "evidence", "capturedAt", "sourceZone", "screenshot");
    JSONObject evidence = source.getJSONObject("evidence");
    Object captureTime = source.get("capturedAt");
    if (evidence.toString().length() > 4000 || !(captureTime instanceof Integer || captureTime instanceof Long)) {
     throw invalidNotebookInput();
    }
    JSONObject lookup = notebookRequest("resolveSocialSource").put("evidence", evidence)
            .put("sourcePackage", text(source, "sourcePackage", 160))
            .put("capturedAt", captureTime).put("sourceZone", text(source, "sourceZone", 100));
    lookup.put("screenshot", jpeg(source, "screenshot"));
    return NativeClient.exchange(lookup, 180_000);
   default:
    throw invalidNotebookInput();
  }
 }

 private static JSONObject notebookRequest(String action) throws Exception {
  return new JSONObject().put("op", "systemAi").put("action", action).put("consumer", NOTEBOOK);
 }

 private static String jpeg(JSONObject object, String key) throws Exception {
  String data = text(object, key, 300_000);
  if (!data.startsWith(JPEG)) throw invalidNotebookInput();
  String encoded = data.substring(JPEG.length());
  if (encoded.length() % 4 != 0 || !BASE64_JPEG.matcher(encoded).matches()) throw invalidNotebookInput();
  byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
  BitmapFactory.Options bounds = new BitmapFactory.Options();
  bounds.inJustDecodeBounds = true;
  BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
  if (!"image/jpeg".equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outHeight <= 0
          || bounds.outWidth > 2048 || bounds.outHeight > 2048) {
   throw invalidNotebookInput();
  }
  return data;
 }

 private static String text(JSONObject object, String key, int maxLength) throws Exception {
  Object field = object.get(key);
  if (!(field instanceof String value) || value.isBlank() || value.length() > maxLength) {
   throw invalidNotebookInput();
  }
  return value;
 }

 private static void requireFields(JSONObject object, String... fields) {
  if (object.length() != fields.length) throw invalidNotebookInput();
  for (String field : fields) {
   if (!object.has(field)) throw invalidNotebookInput();
  }
 }

 private static IllegalArgumentException invalidNotebookInput() {
  return new IllegalArgumentException("Invalid Notebook AI request.");
 }

 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){throw new UnsupportedOperationException();}public String getType(Uri u){return "application/vnd.taku.ai+json";}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
