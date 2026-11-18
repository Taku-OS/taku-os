package org.agentos.tests;

import org.json.*;
import java.io.*;

/** Integration probe uses the existing control queue; writes only Harness/Skills results. */
public final class HarnessProbe {
    private static final File ROOT=new File("/data/user/0/org.agentos.assistant/files/runtime");
    private static String read(File file)throws Exception {
        try(FileInputStream input=new FileInputStream(file)) {ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>2097152)throw new IOException("Result too large");}return bytes.toString("UTF-8");}
    }
    private static void write(File file,String value)throws Exception {try(FileOutputStream output=new FileOutputStream(file)){output.write(value.getBytes("UTF-8"));output.getFD().sync();}}
    public static void main(String[] args)throws Exception {
        if(args.length!=3||!args[0].matches("^(harness|skills)/.*|account/read"))throw new IllegalArgumentException("Harness/Skills/status probe only");
        File directory=null;String id="";
        for(int n=0;n<100;n++){id=Long.toString(System.currentTimeMillis()+n);File candidate=new File(ROOT,"tasks/"+id);if(candidate.mkdir()){directory=candidate;break;}}
        if(directory==null)throw new IOException("Probe slot unavailable");
        write(new File(directory,"request.json"),new JSONObject().put("id",id).put("kind","agent_control").put("prompt","")
                .put("method",args[0]).put("params",new JSONObject(args[1])).toString());
        write(new File(directory,"state.json"),new JSONObject().put("id",id).put("state","queued").put("prompt","").toString());
        write(new File(directory,"ready"),"1");
        File response=new File(directory,"response.json");long end=System.nanoTime()+150_000_000_000L;
        while(!response.exists()){if(System.nanoTime()>end)throw new IOException("Probe control timeout");Thread.sleep(100);}
        JSONObject result=new JSONObject(read(response));write(new File(args[2]),result.toString());
        if(!result.optBoolean("ok")){System.out.println(result.optString("message"));System.exit(1);}
        System.out.println("Control acknowledged: "+args[0]);
    }
}
