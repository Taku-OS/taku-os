package org.agentos.memory;
import java.io.*;import java.nio.file.*;import java.util.*;import java.nio.charset.StandardCharsets;
public class BpeParity{public static void main(String[] a)throws Exception{
 Bpe t=Bpe.open(new File(a[0]));List<String> texts=Files.readAllLines(Paths.get(a[1])),exp=Files.readAllLines(Paths.get(a[2]));
 int bad=0;long ns=0;
 for(int i=0;i<texts.size();i++){String s=new String(Base64.getDecoder().decode(texts.get(i)),StandardCharsets.UTF_8);
  long t0=System.nanoTime();int[] got=t.encode(s,1<<20);ns+=System.nanoTime()-t0;
  String g=Arrays.stream(got).mapToObj(String::valueOf).reduce((x,y)->x+" "+y).orElse("");
  if(!g.equals(exp.get(i))){bad++;if(bad<=8)System.out.println("MISMATCH #"+i+" "+s.replace("\n","\\n").substring(0,Math.min(60,s.length()))+"\n  got "+g.substring(0,Math.min(200,g.length()))+"\n  exp "+exp.get(i).substring(0,Math.min(200,exp.get(i).length())));}}
 System.out.println((texts.size()-bad)+"/"+texts.size()+" identical; "+(ns/texts.size()/1000)+" us per text");
 System.exit(bad==0?0:1);}}
