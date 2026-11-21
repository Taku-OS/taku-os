// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.*;
/** Cancellable document-provider open/read lease. No late open may outlive cancellation. */
final class ReferenceRead implements AutoCloseable {
    private final CancellationSignal signal=new CancellationSignal();
    private AssetFileDescriptor descriptor;
    private InputStream stream;
    private boolean closed;
    RealtimeImage read(Context context,Uri uri)throws IOException{
        signal.throwIfCanceled();
        AssetFileDescriptor opened=context.getContentResolver().openAssetFileDescriptor(uri,"r",signal);
        if(opened==null)throw new IOException("reference_unavailable");
        synchronized(this){if(closed){opened.close();throw new IOException("reference_cancelled");}descriptor=opened;stream=opened.createInputStream();}
        return RealtimeImage.read(stream);
    }
    @Override public void close(){
        InputStream input;AssetFileDescriptor fd;
        synchronized(this){if(closed)return;closed=true;input=stream;fd=descriptor;stream=null;descriptor=null;}
        signal.cancel();
        try{if(input!=null)input.close();}catch(IOException ignored){}
        try{if(fd!=null)fd.close();}catch(IOException ignored){}
    }
}
