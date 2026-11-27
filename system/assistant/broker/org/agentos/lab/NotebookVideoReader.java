package org.agentos.lab;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.media.MediaDataSource;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.Locale;
import org.json.*;

/** Isolated, bounded Android decoder process; no UI screenshot or desktop media dependency. */
public final class NotebookVideoReader {
    static final File ROOT=new File("/data/local/agentos/notebook-video");
    static File directory(String job) throws Exception {
        if(!job.matches("[a-f0-9-]{36}"))throw new IOException("VIDEO_JOB_INVALID");
        return new File(ROOT,job);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].matches("[a-f0-9]{64}") || !args[2].matches("[0-9]{1,6}"))
            throw new IOException("VIDEO_REFERENCE_INVALID");
        File dir=directory(args[1]);
        if(!dir.isDirectory())throw new IOException("VIDEO_JOB_INVALID");
        try {read(args[0], dir, Integer.parseInt(args[2]));}
        catch(Exception|LinkageError e) {
            String message=e.getMessage()==null?"":e.getMessage();
            String code=message.matches("VIDEO_[A-Z0-9_]+") || "ANDROID_USER_CHANGED".equals(message) ? message
                    :e instanceof SocketTimeoutException?"VIDEO_NETWORK_TIMEOUT":"VIDEO_DECODE_OR_NETWORK_FAILED";
            android.util.Log.e("NotebookVideoReader",code);
            TaskBroker.write(new File(dir,"result.json"),new JSONObject().put("ok",false).put("code",code)
                .put("failureType",e.getClass().getName()).put("failureAt",e.getStackTrace().length==0?"":e.getStackTrace()[0].toString()).toString());
        }
        // Native media can start Binder threads; this one-shot subprocess must not outlive its result.
        System.exit(0);
    }
    static void read(String id, File dir, int user) throws Exception {
        long started=System.currentTimeMillis();
        JSONObject reference=NotebookBridge.provider(user,"get",new JSONObject().put("id",id));
        JSONObject result=NotebookVideoSource.resolve(NotebookVideoSource.requireLinkedSource(reference,id));
        String mediaUrl=(String) result.remove("mediaUrl");
        byte[] video=NotebookVideoSource.fetch(mediaUrl,false,NotebookVideoSource.MAX_VIDEO_BYTES);
        result.put("videoSha256",NotebookVideoSource.sha(video)).put("sizeBytes",video.length);
        MediaMetadataRetriever decoder=new MediaMetadataRetriever();
        try {
            // Public in-memory data source: a standalone worker has no Android Application
            // context for the platform's modern-MediaProvider file-descriptor conversion.
            decoder.setDataSource(new MediaDataSource() {
                public int readAt(long position,byte[] buffer,int offset,int size)throws IOException {
                    if(position<0)throw new IOException("VIDEO_OFFSET_INVALID");
                    if(size==0)return 0;
                    if(position>=video.length)return -1;
                    int count=(int)Math.min(size,video.length-position);
                    System.arraycopy(video,(int)position,buffer,offset,count);return count;
                }
                public long getSize(){return video.length;}
                public void close(){}
            });
            long duration=Long.parseLong(decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if(Math.abs(duration-result.getLong("declaredDurationMs"))>1500)throw new IOException("VIDEO_DURATION_MISMATCH");
            long[] times=NotebookVideoSource.samples(duration);
            JSONArray frames=new JSONArray(),sheets=new JSONArray();
            int cell=384,label=30,tile=cell+label,cols=4,rows=4;
            Paint paint=new Paint();paint.setColor(Color.BLACK);
            for(int offset=0;offset<times.length;offset+=16) {
                Bitmap sheet=Bitmap.createBitmap(cols*cell,rows*tile,Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas=new Canvas(sheet);canvas.drawColor(Color.WHITE);
                    for(int n=offset;n<Math.min(offset+16,times.length);n++) {
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                        Bitmap frame=decoder.getScaledFrameAtTime(times[n]*1000,MediaMetadataRetriever.OPTION_CLOSEST,cell,cell);
                        if(frame==null)throw new IOException("VIDEO_FRAME_UNAVAILABLE");
                        try {
                            int k=n-offset,x=k%cols*cell,y=k/cols*tile;
                            stamp(canvas,x+8,y+4,String.format(Locale.ROOT,"%.3fs",times[n]/1000.0),paint);
                            canvas.drawBitmap(frame,x+(cell-frame.getWidth())/2f,y+label+(cell-frame.getHeight())/2f,null);
                            byte[] png=encode(frame,Bitmap.CompressFormat.PNG,100);
                            frames.put(new JSONObject().put("index",n).put("requestedTimeMs",times[n])
                                .put("width",frame.getWidth()).put("height",frame.getHeight()).put("sha256",NotebookVideoSource.sha(png)));
                        } finally {frame.recycle();}
                    }
                    byte[] jpeg=encode(sheet,Bitmap.CompressFormat.JPEG,92);String name="contact-"+(offset/16)+".jpg";
                    Files.write(new File(dir,name).toPath(),jpeg);
                    sheets.put(new JSONObject().put("file",name).put("sha256",NotebookVideoSource.sha(jpeg)).put("mimeType","image/jpeg")
                        .put("firstIndex",offset).put("lastIndex",Math.min(offset+15,times.length-1)));
                } finally {sheet.recycle();}
            }
            result.put("schema","taku.notebook.video/1").put("ok",true).put("referenceId",id).put("referenceTitle",reference.optString("title"))
                .put("referenceImageSha256",reference.optString("sha256")).put("durationMs",duration).put("frames",frames).put("sheets",sheets)
                .put("sampleCount",times.length).put("sampleStepMs",times.length>1?times[1]-times[0]:0)
                .put("sampling","Android MediaMetadataRetriever OPTION_CLOSEST; times are requested sample offsets, not exact decoder PTS")
                .put("processingLocation","android-device").put("mediaSource","public-x-embed")
                .put("audioAnalyzed",false).put("decodedAt",System.currentTimeMillis())
                .put("elapsedMs",System.currentTimeMillis()-started).put("jobId",dir.getName());
            NotebookBridge.requireCurrentUser(user);
            TaskBroker.write(new File(dir,"result.json"),result.put("androidUserId",user).toString());
        } finally {decoder.release();}
    }
    static byte[] encode(Bitmap bitmap,Bitmap.CompressFormat format,int quality)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!bitmap.compress(format,quality,out))throw new IOException("VIDEO_FRAME_ENCODE_FAILED");
        return out.toByteArray();
    }
    /** Tiny diagnostic timestamp font; a shell worker has no Zygote-preloaded text font map. */
    static void stamp(Canvas canvas,int x,int y,String value,Paint paint) {
        String alphabet="0123456789.s";
        String[] glyphs={"01110/10001/10011/10101/11001/10001/01110","00100/01100/00100/00100/00100/00100/01110",
            "01110/10001/00001/00010/00100/01000/11111","11110/00001/00001/01110/00001/00001/11110",
            "00010/00110/01010/10010/11111/00010/00010","11111/10000/10000/11110/00001/00001/11110",
            "01110/10000/10000/11110/10001/10001/01110","11111/00001/00010/00100/01000/01000/01000",
            "01110/10001/10001/01110/10001/10001/01110","01110/10001/10001/01111/00001/00001/01110",
            "00000/00000/00000/00000/00000/00110/00110","00000/00000/01111/10000/01110/00001/11110"};
        for(char ch:value.toCharArray()) {
            int index=alphabet.indexOf(ch);if(index<0)continue;String[] lines=glyphs[index].split("/");
            for(int row=0;row<7;row++)for(int col=0;col<5;col++)if(lines[row].charAt(col)=='1')
                canvas.drawRect(x+col*3,y+row*3,x+(col+1)*3,y+(row+1)*3,paint);
            x+=18;
        }
    }
}
