package org.agentos.lab;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Public X video resolution. The only input URL comes from the authoritative saved record. */
final class NotebookVideoSource {
    static final int MAX_METADATA_BYTES=1024*1024, MAX_VIDEO_BYTES=24*1024*1024;
    static final long MAX_DURATION_MS=60_000;
    static String requireLinkedSource(JSONObject reference,String id) throws Exception {
        if (!id.equals(reference.optString("id")) || !reference.optString("sha256").matches("[a-f0-9]{64}")) {
            throw new IOException("VIDEO_REFERENCE_INVALID");
        }
        Object source = reference.opt("sourceUrl");
        if (!"linked".equals(reference.optString("sourceResolution")) || !(source instanceof String url)) {
            throw new IOException("VIDEO_SOURCE_NOT_LINKED");
        }
        postId(url);
        return url;
    }
    static String postId(String source) throws Exception {
        URI u=new URI(source);
        String host=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getFragment()!=null
                ||u.getPort()!=-1&&u.getPort()!=443
                ||!Set.of("x.com","www.x.com","mobile.x.com","twitter.com","www.twitter.com","mobile.twitter.com").contains(host))
            throw new IOException("VIDEO_SOURCE_UNSUPPORTED");
        Matcher m=Pattern.compile("/(?:[A-Za-z0-9_]{1,15}|i/web)/status/([1-9][0-9]{0,19})(?:/video/[1-9][0-9]*)?/?").matcher(u.getPath());
        if(!m.matches())throw new IOException("VIDEO_SOURCE_UNSUPPORTED");
        return m.group(1);
    }
    static URL checkedUrl(String value,boolean metadata) throws Exception {
        URL u=new URL(value);
        String host=metadata?"cdn.syndication.twimg.com":"video.twimg.com";
        if(!"https".equals(u.getProtocol())||!host.equals(u.getHost())||u.getUserInfo()!=null
                ||u.getRef()!=null||u.getPort()!=-1&&u.getPort()!=443)
            throw new IOException("VIDEO_MEDIA_URL_UNSUPPORTED");
        return u;
    }
    static JSONObject select(JSONObject tweet,String id) throws Exception {
        if(!id.equals(tweet.optString("id_str")))throw new IOException("VIDEO_POST_ID_MISMATCH");
        JSONObject author = tweet.optJSONObject("user");
        if ("TweetTombstone".equals(tweet.optString("__typename"))
                || (author != null && author.optBoolean("protected"))) {
            throw new IOException("VIDEO_NOT_PUBLIC");
        }
        JSONObject video=tweet.optJSONObject("video");
        if(video==null||!"available".equals(video.optJSONObject("mediaAvailability")==null?"":video.getJSONObject("mediaAvailability").optString("status")))
            throw new IOException("VIDEO_UNAVAILABLE");
        long duration=video.optLong("durationMs",-1);
        if(duration<=0||duration>MAX_DURATION_MS)throw new IOException("VIDEO_DURATION_UNSUPPORTED");
        JSONArray variants=video.getJSONArray("variants");String chosen=null;int score=-1;
        for(int i=0;i<variants.length();i++) {
            JSONObject v=variants.getJSONObject(i);if(!"video/mp4".equals(v.optString("type")))continue;
            String src=v.optString("src");URL u=checkedUrl(src,false);
            Matcher m=Pattern.compile("/(\\d+)x(\\d+)/").matcher(u.getPath());
            if(!m.find())continue;
            int w=Integer.parseInt(m.group(1)),h=Integer.parseInt(m.group(2));
            if(w<=0||h<=0||w>800||h>800)continue;
            int pixels=w*h;if(pixels>score){score=pixels;chosen=src;}
        }
        if(chosen==null)throw new IOException("VIDEO_MP4_UNAVAILABLE");
        return new JSONObject().put("postId",id).put("mediaUrl",chosen).put("declaredDurationMs",duration);
    }
    static JSONObject resolve(String source) throws Exception {
        String id=postId(source);
        String url=publicEmbedUrl(id);
        JSONObject r=select(new JSONObject(new String(fetch(url,true,MAX_METADATA_BYTES),java.nio.charset.StandardCharsets.UTF_8)),id);
        return r.put("sourceUrl",source);
    }

    static String publicEmbedUrl(String id) {
        return "https://cdn.syndication.twimg.com/tweet-result?id="+id+"&lang=en&token="+publicEmbedToken(id);
    }

    // Public X embed widget calculation. There is no account token or authorization header.
    static String publicEmbedToken(String id) {
        double value = Double.parseDouble(id) / 1e15 * Math.PI;
        long integer = (long) value;
        double remainder = value - integer;
        double interval = Math.ulp(value) / 2;
        StringBuilder digits = new StringBuilder();
        while (remainder >= interval) {
            double scaled = remainder * 36;
            int digit = (int) scaled;
            digits.append(Character.forDigit(digit, 36));
            remainder = scaled - digit;
            interval *= 36;
            // Use only the precision of the original number and propagate base-36 carry.
            if (remainder + interval > 1 && (remainder > .5 || (remainder == .5 && (digit & 1) == 1))) {
                int last = digits.length() - 1;
                while (last >= 0 && digits.charAt(last) == 'z') last--;
                if (last < 0) {
                    integer++;
                    digits.setLength(0);
                } else {
                    digits.setLength(last + 1);
                    digits.setCharAt(last, Character.forDigit(Character.digit(digits.charAt(last), 36) + 1, 36));
                }
                break;
            }
        }
        return (Long.toString(integer, 36) + digits).replace("0", "");
    }
    static byte[] fetch(String location,boolean metadata,int max) throws Exception {
        URL u=checkedUrl(location,metadata);
        for(int redirects=0;redirects<3;redirects++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            HttpURLConnection c=open(u);
            c.setConnectTimeout(8_000);c.setReadTimeout(8_000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","Taku-Notebook-Video/1");
            c.setRequestProperty("Accept",metadata?"application/json":"video/mp4");
            try {
                int status=c.getResponseCode();
                if(status>=300&&status<400){u=checkedUrl(new URL(u,c.getHeaderField("Location")).toString(),metadata);continue;}
                if(status!=200)throw new IOException("VIDEO_HTTP_"+status);
                if(c.getContentLengthLong()>max)throw new IOException("VIDEO_RESPONSE_TOO_LARGE");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] b=new byte[16*1024];for(int n;(n=in.read(b))!=-1;) {
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                        if(n>max-out.size())throw new IOException("VIDEO_RESPONSE_TOO_LARGE");out.write(b,0,n);
                    }
                    byte[] bytes=out.toByteArray();
                    if(!metadata&&(bytes.length<12||bytes[4]!='f'||bytes[5]!='t'||bytes[6]!='y'||bytes[7]!='p'))
                        throw new IOException("VIDEO_NOT_MP4");
                    return bytes;
                }
            } finally {c.disconnect();}
        }
        throw new IOException("VIDEO_REDIRECT_LIMIT");
    }
    private static HttpURLConnection open(URL url) throws Exception {
        File f=new File("/data/local/agentos/network-proxy");
        if(!f.isFile())return (HttpURLConnection)url.openConnection();
        URI u=new URI(TaskBroker.read(f).trim());
        if(!"http".equals(u.getScheme())||u.getHost()==null||u.getPort()<1||u.getPort()>65535||u.getUserInfo()!=null)
            throw new IOException("VIDEO_PROXY_UNAVAILABLE");
        return (HttpURLConnection)url.openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(u.getHost(),u.getPort())));
    }
    static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static long[] samples(long durationMs) throws Exception {
        if(durationMs<=0||durationMs>MAX_DURATION_MS)throw new IOException("VIDEO_DURATION_UNSUPPORTED");
        int count=(int)Math.min(32,Math.max(2,(durationMs+499)/500));long[] times=new long[count];
        long step=Math.max(500,(durationMs+count-1)/count);
        for(int i=0;i<count;i++)times[i]=Math.min(durationMs-1,i*step);
        return times;
    }
}
