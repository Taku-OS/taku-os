package org.agentos.lab;
import android.media.*;
import android.graphics.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.json.*;
/** Decode a generated, public test stream without a Surface, app UI, or app data. */
public final class DecodeProbe {
 static int sample(Image.Plane p,int x,int y){ByteBuffer b=p.getBuffer();return b.get(b.position()+y*p.getRowStride()+x*p.getPixelStride())&255;}
 static String digest(Image img)throws Exception{MessageDigest sha=MessageDigest.getInstance("SHA-256");Rect crop=img.getCropRect();Image.Plane[] ps=img.getPlanes();for(int i=0;i<3;i++){int div=i==0?1:2,w=crop.width()/div,h=crop.height()/div;byte[] row=new byte[w];for(int y=0;y<h;y++){for(int x=0;x<w;x++)row[x]=(byte)sample(ps[i],crop.left/div+x,crop.top/div+y);sha.update(row);}}StringBuilder s=new StringBuilder();for(byte b:sha.digest())s.append(String.format("%02x",b&255));return s.toString();}
 static int clamp(int x){return Math.max(0,Math.min(255,x));}
 static void picture(Image img,String file)throws Exception{Rect c=img.getCropRect();int w=c.width(),h=c.height();int[] pixels=new int[w*h];Image.Plane[] p=img.getPlanes();for(int y=0;y<h;y++)for(int x=0;x<w;x++){int Y=sample(p[0],c.left+x,c.top+y)-16,U=sample(p[1],(c.left+x)/2,(c.top+y)/2)-128,V=sample(p[2],(c.left+x)/2,(c.top+y)/2)-128;int R=clamp((298*Y+409*V+128)>>8),G=clamp((298*Y-100*U-208*V+128)>>8),B=clamp((298*Y+516*U+128)>>8);pixels[y*w+x]=0xff000000|R<<16|G<<8|B;}Bitmap b=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);try(FileOutputStream f=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}
 public static void main(String[] args)throws Exception{
  String codec=args[0],path=args[1],prefix=args[2];boolean surfaceMode=args.length>3&&args[3].equals("surface");MediaExtractor ex=new MediaExtractor();MediaCodec dec=null;ImageReader reader=null;JSONObject result=new JSONObject().put("requestedCodec",codec).put("source",path).put("surface",surfaceMode);JSONArray frames=new JSONArray();result.put("frames",frames);
  try{
   ex.setDataSource(path);int video=-1;for(int i=0;i<ex.getTrackCount();i++)if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")){video=i;break;}if(video<0)throw new IOException("No video track");ex.selectTrack(video);MediaFormat format=ex.getTrackFormat(video);if(!surfaceMode)format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);else reader=ImageReader.newInstance(format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT),ImageFormat.YUV_420_888,3);result.put("inputFormat",format.toString());dec=MediaCodec.createByCodecName(codec);result.put("actualCodec",dec.getName());dec.configure(format,surfaceMode?reader.getSurface():null,null,0);dec.start();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean inputDone=false,outputDone=false;long end=SystemClock.elapsedRealtime()+20000;
   while(!outputDone&&SystemClock.elapsedRealtime()<end){
    if(!inputDone){int ix=dec.dequeueInputBuffer(10000);if(ix>=0){ByteBuffer in=dec.getInputBuffer(ix);int size=ex.readSampleData(in,0);if(size<0){dec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{dec.queueInputBuffer(ix,0,size,ex.getSampleTime(),0);ex.advance();}}}
    int ox=dec.dequeueOutputBuffer(info,10000);if(ox==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)result.put("outputFormat",dec.getOutputFormat().toString());else if(ox>=0){boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;boolean data=info.size>0||surfaceMode&&!eos;Image img=null;boolean released=false;if(surfaceMode){dec.releaseOutputBuffer(ox,data);released=true;if(data){long waitUntil=SystemClock.elapsedRealtime()+2000;while(img==null&&SystemClock.elapsedRealtime()<waitUntil){img=reader.acquireNextImage();if(img==null)Thread.sleep(5);}}}else if(data)img=dec.getOutputImage(ox);if(data){JSONObject frame=new JSONObject().put("ptsUs",info.presentationTimeUs).put("size",info.size).put("flags",info.flags);if(img!=null){try(Image closable=img){frame.put("format",img.getFormat()).put("crop",img.getCropRect().toString()).put("sha256",digest(img));JSONArray planes=new JSONArray();for(Image.Plane p:img.getPlanes())planes.put(new JSONObject().put("rowStride",p.getRowStride()).put("pixelStride",p.getPixelStride()).put("remaining",p.getBuffer().remaining()));frame.put("planes",planes);if(frames.length()==0){String png=prefix+"-first.png";picture(img,png);frame.put("png",png);}}}else frame.put("noOutputImage",true);frames.put(frame);}outputDone=eos;if(!released)dec.releaseOutputBuffer(ox,false);}
   }
   result.put("eos",outputDone).put("frameCount",frames.length());
  }catch(Exception error){result.put("error",error.toString());}
  finally{if(dec!=null){try{dec.stop();}catch(Exception ignored){}dec.release();}if(reader!=null)reader.close();ex.release();}
  System.out.println(result.toString(2));
 }
}
