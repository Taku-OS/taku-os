package org.agentos.lab;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import org.json.JSONArray;
import org.json.JSONObject;
/** Read-only codec capability enumeration. No decoder, surface, UI, or app data is created. */
public final class CodecListProbe {
 public static void main(String[] args) throws Exception {
  MediaCodecList list=new MediaCodecList(MediaCodecList.REGULAR_CODECS);
  JSONArray codecs=new JSONArray();int order=0;
  for(MediaCodecInfo info:list.getCodecInfos()){
   int current=order++;
   if(info.isEncoder())continue;
   for(String mime:info.getSupportedTypes()){
    if(!mime.equalsIgnoreCase("video/hevc")&&!mime.equalsIgnoreCase("video/avc"))continue;
    MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(mime);JSONArray colors=new JSONArray();for(int v:caps.colorFormats)colors.put(v);JSONArray profiles=new JSONArray();for(MediaCodecInfo.CodecProfileLevel v:caps.profileLevels)profiles.put(new JSONObject().put("profile",v.profile).put("level",v.level));
    codecs.put(new JSONObject().put("listOrder",current).put("name",info.getName()).put("canonicalName",info.getCanonicalName()).put("softwareOnly",info.isSoftwareOnly()).put("hardwareAccelerated",info.isHardwareAccelerated()).put("vendor",info.isVendor()).put("mime",mime).put("colorFormats",colors).put("profiles",profiles).put("supports720x1280",caps.getVideoCapabilities().isSizeSupported(720,1280)).put("requiresSecurePlayback",caps.isFeatureRequired("secure-playback")));
   }
  }
  JSONObject result=new JSONObject().put("codecs",codecs).put("preferredHEVC720x1280",list.findDecoderForFormat(MediaFormat.createVideoFormat("video/hevc",720,1280))).put("preferredAVC720x1280",list.findDecoderForFormat(MediaFormat.createVideoFormat("video/avc",720,1280)));
  System.out.println(result.toString(2));
 }
}
