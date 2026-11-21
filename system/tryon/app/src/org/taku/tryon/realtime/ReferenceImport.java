// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
/** Bounded, validated screenshot/image import. Only normalized JPEG is atomically retained. */
public final class ReferenceImport {
    public static final int MAX_INPUT_BYTES=4*1024*1024;
    private static final AtomicBoolean WRITING=new AtomicBoolean();
    private static volatile String state="idle",code="none";
    private static volatile long revision;
    private ReferenceImport(){}
    public static boolean begin(){if(CallTryOnService.busy()||!WRITING.compareAndSet(false,true))return false;state="writing";code="none";return true;}
    public static boolean writing(){return WRITING.get();}
    public static void abort(){state="failed";code="reference_import_failed";WRITING.set(false);}
    public static void write(Context context,InputStream input){
        write(context,input,0);
    }
    public static void write(Context context,InputStream input,long referenceExpires){
        RealtimeImage image=null;
        try {
            if(referenceExpires<0||referenceExpires!=0&&SystemClock.elapsedRealtime()>=referenceExpires){state="failed";code=referenceExpires<0?"invalid_request":"reference_expired";return;}
            byte[] bytes=input.readNBytes(MAX_INPUT_BYTES+1);
            if(bytes.length>MAX_INPUT_BYTES)throw new IOException("too_large");
            image=RealtimeImage.decode(bytes);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            if(!image.preview.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,output))throw new IOException("decode_failed");
            Bundle result=TryOnController.importedReference(context,output.toByteArray(),referenceExpires);
            code=result.getString("code","import_failed");state=result.getBoolean("ok")?"ready":"failed";if(result.getBoolean("ok"))revision++;
        }catch(Exception|OutOfMemoryError invalid){state="failed";code="invalid_reference";}
        finally {if(image!=null)image.close();WRITING.set(false);}
    }
    public static JSONObject status(){
        JSONObject out=new JSONObject();try{out.put("state",state).put("code",code).put("revision",revision);}catch(Exception ignored){}return out;
    }
}
