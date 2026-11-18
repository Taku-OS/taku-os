package org.agentos.lab;

import java.io.*;
import java.net.*;
import org.json.JSONObject;

/** Controlled loopback lookup; records counts/timing, never resolved addresses. */
public final class ResolverProbe {
    public static void main(String[] args)throws Exception {
        JSONObject result=new JSONObject().put("host","example.com");
        int port=args.length==0?53:Integer.parseInt(args[0]);
        for(int type:new int[]{1,28}){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeShort(123);out.writeShort(256);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);
            for(String label:new String[]{"example","com"}){out.writeByte(label.length());out.writeBytes(label);}out.writeByte(0);out.writeShort(type);out.writeShort(1);
            byte[] query=bytes.toByteArray();long start=System.nanoTime();
            try(DatagramSocket socket=new DatagramSocket()){
                socket.setSoTimeout(10000);socket.send(new DatagramPacket(query,query.length,InetAddress.getByAddress(new byte[]{127,0,0,1}),port));
                DatagramPacket reply=new DatagramPacket(new byte[512],512);socket.receive(reply);
                DataInputStream in=new DataInputStream(new ByteArrayInputStream(reply.getData(),0,reply.getLength()));
                if(in.readUnsignedShort()!=123)throw new IOException("Wrong reply ID");int flags=in.readUnsignedShort();in.readUnsignedShort();int count=in.readUnsignedShort();
                result.put(type==1?"A":"AAAA",new JSONObject().put("rcode",flags&15).put("answers",count).put("elapsedMs",(System.nanoTime()-start)/1000000));
            }
        }
        System.out.println(result.toString());
    }
}
