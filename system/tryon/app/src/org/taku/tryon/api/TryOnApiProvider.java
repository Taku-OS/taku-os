// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.api;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.taku.tryon.common.AgentAccess;
import org.taku.tryon.realtime.TryOnController;

/** Standard Android Agent entry. All operations delegate to the GUI's controller. */
public final class TryOnApiProvider extends ContentProvider {
    private final CredentialWriteGate credentialGate=new CredentialWriteGate();
    private final Handler credentialTimer=new Handler(Looper.getMainLooper());
    // No queued readers: an OS read slow to observe close cannot accumulate abandoned workers.
    private final ThreadPoolExecutor credentialWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),task->{Thread thread=new Thread(task,"tryon-credential-write");thread.setDaemon(true);return thread;});
    private final AtomicReference<CredentialPipe> credentialPipe=new AtomicReference<>();
    private static final class CredentialPipe {
        final CredentialWriteGate.Lease lease;
        final ParcelFileDescriptor read,write;
        final AtomicBoolean closed=new AtomicBoolean();
        Runnable timeout;
        CredentialPipe(CredentialWriteGate.Lease lease,ParcelFileDescriptor[] pipe){this.lease=lease;read=pipe[0];write=pipe[1];}
    }
    private void enforce(){AgentAccess.enforce(getContext(),Binder.getCallingUid());}
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        enforce();
        if(method!=null&&org.taku.tryon.realtime.LegacyCosplayGateway.handles(method))
            return org.taku.tryon.realtime.LegacyCosplayGateway.call(getContext(),method,arg,extras,getCallingPackage());
        Bundle args=extras==null?new Bundle():extras;
        if(method==null)return TryOnController.result(false,"invalid_arguments");
        if(arg!=null){
            if(!args.isEmpty()||!("configure".equals(method)||"draft".equals(method)||"start".equals(method)||"clearCredential".equals(method)||"move".equals(method)))return TryOnController.result(false,"invalid_arguments");
            try{
                args=new Bundle();
                for(java.util.Map.Entry<String,Object> field:ApiArguments.parse(arg).entrySet()){
                    if(field.getValue() instanceof Long)args.putLong(field.getKey(),(Long)field.getValue());
                    else args.putString(field.getKey(),(String)field.getValue());
                }
            }catch(IllegalArgumentException malformed){return TryOnController.result(false,"invalid_arguments");}
        }
        switch(method) {
            case "configure":if(!TryOnController.validArguments(args,"mode","provider","region"))return TryOnController.result(false,"invalid_arguments");break;
            case "draft":case "start":if(!TryOnController.validArguments(args,"mode","referenceUri","prompt","referenceExpires"))return TryOnController.result(false,"invalid_arguments");break;
            case "clearCredential":if(!TryOnController.validArguments(args,"provider"))return TryOnController.result(false,"invalid_arguments");break;
            case "move":break; // Required integer coordinates are checked by the same Controller entry.
            default:if(!args.isEmpty())return TryOnController.result(false,"invalid_arguments");
        }
        switch(method){
            case "describe":{Bundle b=TryOnController.result(true,"describe");b.putString("methods","status,draft,configure,start,retry,stop,minimize,expand,fullscreen,move,diagnostics,clearCredential");b.putString("arguments","configure,draft,start,clearCredential,move accept strict JSON object in arg (32 KiB maximum), mutually exclusive with non-empty extras; same validation");b.putString("moveArguments","Required x,y integer display pixels, each within signed 32-bit range; window top-left, clamped by the GUI geometry. Only a movable active preview accepts move; status.position reads actual bounds and canMove.");b.putString("credentialWrite","content://org.taku.tryon.api/credentials/lucy or /xmax; one bounded UTF-8 input at a time; EOF/commit admission within 30 seconds; credential_write_busy or credential_write_timeout; metadata only on read");b.putString("startRequires","Open org.taku.tryon/.realtime.TryOnActivity; camera permission and display-over-apps permission; content referenceUri; explicit billed start");b.putString("voiceMethods","cosplayStatus,cosplayModelStatus,configureCosplay,prepareCosplay,cancelCosplay,reportCosplayVoice");b.putString("voiceProtocol","taku.cosplay.voice.v1; setup at org.taku.tryon/.realtime.TryOnActivity; invocation preparation requires the installed signed org.agentos.agent package and a real current TikTok frame");b.putString("voiceLanguage","Audio Engine settingsScope=assistant-entry; open org.agentos.audio/.DictationSettingsActivity or use content://org.agentos.audio.open-access settings; configureCosplay rejects speechLanguage");return b;}
            case "status":return TryOnController.status(getContext());
            case "diagnostics":return TryOnController.diagnostics(getContext());
            case "draft":return TryOnController.draft(getContext(),arg==null?extras:args);
            case "configure":return TryOnController.configure(getContext(),args);
            case "start":return TryOnController.start(getContext(),args);
            case "retry":return TryOnController.retry(getContext());
            case "stop":return TryOnController.stop(getContext());
            case "minimize":case "expand":case "fullscreen":return TryOnController.presentation(method);
            case "move":return TryOnController.move(args);
            case "clearCredential":return TryOnController.credential(getContext(),args.getString("provider",""),null,true);
            default:return TryOnController.result(false,"unknown_method");
        }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        enforce();String path=uri.getPath();
        if("/reference/current".equals(path)) {
            if("r".equals(mode)) {
                try{return ParcelFileDescriptor.open(new File(getContext().getFilesDir(),"imported-reference.jpg"),ParcelFileDescriptor.MODE_READ_ONLY);}
                catch(FileNotFoundException absent){throw new FileNotFoundException("reference_required");}
            }
            if(!"w".equals(mode)||!org.taku.tryon.realtime.ReferenceImport.begin())throw new FileNotFoundException("reference_import_busy");
            try {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
                Thread reader=new Thread(()->{
                    Handler timer=new Handler(Looper.getMainLooper());Runnable timeout=()->{try{pipe[0].closeWithError("reference_import_timeout");}catch(IOException ignored){}};
                    timer.postDelayed(timeout,30000);
                    try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){org.taku.tryon.realtime.ReferenceImport.write(getContext(),input);}
                    catch(IOException ignored){}finally{timer.removeCallbacks(timeout);}
                },"tryon-reference-import");reader.setDaemon(true);reader.start();return pipe[1];
            }catch(IOException failed){org.taku.tryon.realtime.ReferenceImport.abort();throw new FileNotFoundException("reference_import_failed");}
        }
        if(!"w".equals(mode)||!("/credentials/lucy".equals(path)||"/credentials/xmax".equals(path)))throw new FileNotFoundException("write_only_credentials");
        return openCredentialPipe(uri.getLastPathSegment());
    }
    private ParcelFileDescriptor openCredentialPipe(String provider)throws FileNotFoundException{
        CredentialWriteGate.Lease lease=credentialGate.acquire(SystemClock.elapsedRealtime());
        if(lease==null)throw new FileNotFoundException("credential_write_busy");
        CredentialPipe pending=null;ParcelFileDescriptor[] endpoints=null;
        try{
            endpoints=ParcelFileDescriptor.createReliablePipe();pending=new CredentialPipe(lease,endpoints);
            final CredentialPipe pipe=pending;credentialPipe.set(pipe);
            if(!credentialGate.reading(lease))throw new IOException("provider_closed");
            pipe.timeout=()->{if(credentialGate.expire(lease,SystemClock.elapsedRealtime()))closeCredentialPipe(pipe,"credential_write_timeout");};
            if(!credentialTimer.postDelayed(pipe.timeout,Math.max(1,lease.deadline-SystemClock.elapsedRealtime())))throw new IOException("timer_unavailable");
            credentialWorker.execute(()->readCredentialPipe(pipe,provider));return pipe.write;
        }catch(RejectedExecutionException busy){
            closeCredentialPipe(pending,"credential_write_busy");throw new FileNotFoundException("credential_write_busy");
        }catch(IOException|RuntimeException|OutOfMemoryError failed){
            if(pending!=null)closeCredentialPipe(pending,"credential_pipe_failed");else{
                if(endpoints!=null)for(ParcelFileDescriptor endpoint:endpoints)try{endpoint.close();}catch(IOException ignored){}
                credentialGate.release(lease);
            }
            throw new FileNotFoundException("credential_pipe_failed");
        }
    }
    private void readCredentialPipe(CredentialPipe pipe,String provider){
        InputStream input=null;byte[] bytes=null;String error=null;
        try{
            input=new ParcelFileDescriptor.AutoCloseInputStream(pipe.read);bytes=input.readNBytes(1026);
            if(bytes.length>1024){error="invalid_credential";return;}
            // A timeout or shutdown may already have released this generation's descriptors.
            if(!credentialGate.beginCommit(pipe.lease,SystemClock.elapsedRealtime())){
                error=SystemClock.elapsedRealtime()>=pipe.lease.deadline?"credential_write_timeout":"credential_failed";return;
            }
            credentialTimer.removeCallbacks(pipe.timeout);
            Bundle result=TryOnController.credential(getContext(),provider,new String(bytes,StandardCharsets.UTF_8).trim(),false);
            if(!result.getBoolean("ok"))error=result.getString("code","credential_failed");
        }catch(Exception|OutOfMemoryError ignored){error="credential_failed";}
        finally{
            if(bytes!=null)java.util.Arrays.fill(bytes,(byte)0);
            closeCredentialPipe(pipe,error);
            try{if(input!=null)input.close();}catch(IOException ignored){}
        }
    }
    private void closeCredentialPipe(CredentialPipe pipe,String error){
        if(!pipe.closed.compareAndSet(false,true))return;
        if(pipe.timeout!=null)credentialTimer.removeCallbacks(pipe.timeout);
        try{if(error==null)pipe.read.close();else pipe.read.closeWithError(error);}catch(IOException|RuntimeException ignored){}
        try{pipe.write.close();}catch(IOException|RuntimeException ignored){}
        credentialPipe.compareAndSet(pipe,null);credentialGate.release(pipe.lease);
    }
    @Override public void shutdown(){
        credentialGate.shutdown();CredentialPipe pending=credentialPipe.getAndSet(null);
        if(pending!=null)closeCredentialPipe(pending,"credential_failed");
        credentialWorker.shutdownNow();super.shutdown();
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){enforce();MatrixCursor c=new MatrixCursor(new String[]{"json"});c.addRow(new Object[]{TryOnController.status(getContext()).getString("json","{}")});return c;}
    @Override public String getType(Uri uri){enforce();return "/reference/current".equals(uri.getPath())?"image/jpeg":"application/json";}
    @Override public Uri insert(Uri uri,ContentValues values){enforce();throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){enforce();throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){enforce();throw new UnsupportedOperationException();}
}
