package org.agentos.lab;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/** Loopback DNS for musl programs. Android's InetAddress uses the platform resolver. */
public final class ResolverBridge {
    static int u16(byte[] b,int n){return ((b[n]&255)<<8)|(b[n+1]&255);}
    static byte[] response(byte[] query) {
        if(query.length<12)return new byte[0];
        int end=12,type=0,error=0;String hostname="";
        try {
            if(u16(query,4)!=1 || (query[2]&0xf8)!=0)throw new IllegalArgumentException();
            StringBuilder name=new StringBuilder();
            for(int labels=0;;labels++){
                if(end>=query.length || labels>127)throw new IllegalArgumentException();
                int length=query[end++]&255;
                if(length==0)break;
                if(length>63 || end+length>query.length)throw new IllegalArgumentException();
                if(name.length()>0)name.append('.');
                name.append(new String(query,end,length,StandardCharsets.US_ASCII));end+=length;
                if(name.length()>253)throw new IllegalArgumentException();
            }
            if(end+4>query.length || name.length()==0)throw new IllegalArgumentException();
            type=u16(query,end);int klass=u16(query,end+2);end+=4;
            if(klass!=1 || (type!=1 && type!=28)){error=4;}
            hostname=name.toString();
        } catch(Exception e){error=1;end=12;}
        byte[][] addresses=new byte[0][];
        if(error==0)try {
            final int addressBytes=type==1?4:16;
            int answerLimit=Math.min(12,(512-end)/(12+addressBytes));
            addresses=Arrays.stream(InetAddress.getAllByName(hostname)).map(InetAddress::getAddress)
                .filter(a->a.length==addressBytes).limit(answerLimit).toArray(byte[][]::new);
        } catch(UnknownHostException e){error=2;}catch(Exception e){error=2;}
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.write(query,0,2);out.writeShort(0x8080|((query[2]&1)<<8)|error);
            out.writeShort(end>12?1:0);out.writeShort(error==0?addresses.length:0);out.writeShort(0);out.writeShort(0);
            if(end>12)out.write(query,12,end-12);
            if(error==0)for(byte[] address:addresses){out.writeShort(0xc00c);out.writeShort(type);out.writeShort(1);out.writeInt(30);out.writeShort(address.length);out.write(address);}
            return bytes.toByteArray();
        }catch(Exception e){return new byte[0];}
    }
    public static void main(String[] args)throws Exception {
        int port=args.length==0?53:Integer.parseInt(args[0]);
        DatagramSocket socket=new DatagramSocket(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port));
        ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
        System.out.println("Android resolver bridge listening on loopback port "+socket.getLocalPort());
        for(;;){
            DatagramPacket packet=new DatagramPacket(new byte[512],512);socket.receive(packet);
            byte[] query=Arrays.copyOf(packet.getData(),packet.getLength());SocketAddress peer=packet.getSocketAddress();
            try{workers.execute(()->{byte[] answer=response(query);if(answer.length==0)return;try{socket.send(new DatagramPacket(answer,answer.length,peer));}catch(Exception ignored){}});}
            catch(RejectedExecutionException ignored){}
        }
    }
}
