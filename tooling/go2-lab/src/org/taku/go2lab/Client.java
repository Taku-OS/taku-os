package org.taku.go2lab;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Fixed loopback tool. Navigation actuation is restricted to simulation by the bridge. */
public final class Client {
  private static byte[] request(String path, String body, int limit) throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL((path.startsWith("map/")?"http://127.0.0.1:18846/"+path.substring(4):"http://127.0.0.1:18845/api/"+path)).openConnection(Proxy.NO_PROXY);
    c.setConnectTimeout(3000);c.setReadTimeout(path.startsWith("map/")?35000:15000);c.setInstanceFollowRedirects(false);
    try {
      if(body!=null){
        byte[] data=body.getBytes(StandardCharsets.UTF_8);
        if(data.length>(path.equals("navigation/geo-prepare")?262144:1024))throw new IOException("Request too large");
        c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);
        try(OutputStream out=c.getOutputStream()){out.write(data);}
      }
      int status=c.getResponseCode();
      try(InputStream in=status==200?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>limit)throw new IOException("Response too large");}
        byte[] data=out.toByteArray();
        if(status!=200)throw new IOException(new String(data,StandardCharsets.UTF_8));
        return data;
      }
    }finally{c.disconnect();}
  }
  public static void main(String[] args) throws Exception {
    if(args.length<1||args.length>2)throw new IllegalArgumentException("go2 inspect|connect|disconnect|nav-state|nav-prepare|nav-observe|nav-stop|nav-place JSON|nav-goal JSON|nav-visual JSON|nav-scan left/right");
    String action=args[0],path,body=null;
    switch(action){
      case "inspect":path="state";break;
      case "connect":case "disconnect":path=action;body="{}";break;
      case "nav-state":case "geo-state":path="navigation";break;
      case "map-state":path="map/status";break;
      case "map-search":case "map-route":
        if(args.length!=2)throw new IllegalArgumentException("JSON required");path="map/"+action.substring(4);body=new JSONObject(args[1]).toString();break;
      case "geo-calibrate":case "geo-start":case "geo-heartbeat":
        if(args.length!=2)throw new IllegalArgumentException("JSON required");path="navigation/"+action;body=new JSONObject(args[1]).toString();break;
      case "geo-prepare-route":
        if(args.length!=2)throw new IllegalArgumentException("name and scope JSON required");
        JSONObject options=new JSONObject(args[1]);
        if(options.length()!=2||!options.has("name")||!options.has("scope"))throw new IllegalArgumentException("name and scope only");
        File routeFile=new File("/data/local/agentos/go2-lab/map-route.json");
        if(routeFile.length()>262144)throw new IOException("Saved route too large");
        JSONObject saved;
        try(InputStream in=new FileInputStream(routeFile);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
          byte[] buffer=new byte[8192];int n;
          while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>262144)throw new IOException("Saved route too large");}
          saved=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
        if(!"GCJ-02".equals(saved.getString("coordinateSystem")))throw new IOException("Wrong coordinate system");
        options.put("points",saved.getJSONArray("points"));path="navigation/geo-prepare";body=options.toString();break;
      case "nav-prepare":case "nav-observe":case "nav-stop":path="navigation/"+action.substring(4);body="{}";break;
      case "nav-place":case "nav-goal":case "nav-visual":
        if(args.length!=2)throw new IllegalArgumentException("JSON required");path="navigation/"+action.substring(4);body=new JSONObject(args[1]).toString();break;
      case "nav-scan":
        if(args.length!=2)throw new IllegalArgumentException("Direction required");path="navigation/scan";body=new JSONObject().put("direction",args[1]).toString();break;
      default:throw new IllegalArgumentException("Unknown command");
    }
    JSONObject result=new JSONObject(new String(request(path,body,path.startsWith("map/")?5000000:1000000),StandardCharsets.UTF_8));
    if(action.equals("map-route")){
      org.json.JSONArray points=new org.json.JSONArray(),steps=result.getJSONArray("steps");
      for(int i=0;i<steps.length();i++){
        org.json.JSONArray line=steps.getJSONObject(i).getJSONArray("polyline");
        for(int j=0;j<line.length();j++){
          String[] p=line.getString(j).split(",");points.put(new org.json.JSONArray().put(Double.parseDouble(p[0])).put(Double.parseDouble(p[1])));
        }
      }
      File file=new File("/data/local/agentos/go2-lab/map-route.json");
      JSONObject saved=new JSONObject().put("points",points).put("coordinateSystem",result.getString("coordinateSystem"));
      try(FileOutputStream out=new FileOutputStream(file)){out.write(saved.toString().getBytes(StandardCharsets.UTF_8));}
      file.setReadable(false,false);file.setReadable(true,true);
      result.remove("mapImage");result.remove("steps");result.put("routeFile",file.getPath());result.put("pointCount",points.length());
    }
    if(action.equals("nav-observe")){
      String id=result.getString("observationId");if(!id.matches("[a-f0-9]{32}"))throw new IOException("Invalid observation ID");
      File image=new File("/data/local/agentos/go2-lab/observation.jpg");
      // A single replaceable local image; the assistant can pass it to view_image.
      byte[] jpeg=request("navigation/image?id="+id,null,1048576);
      try(FileOutputStream out=new FileOutputStream(image)){out.write(jpeg);}
      image.setReadable(false,false);image.setReadable(true,true);
      result.put("localImage",image.getPath());
      result.getJSONObject("navigation").remove("map");
    }
    if(result.has("map")){result.put("mapReady",!result.isNull("map"));result.remove("map");}
    if(result.has("geo"))result.getJSONObject("geo").remove("route");
    System.out.println(result.toString());
  }
}
