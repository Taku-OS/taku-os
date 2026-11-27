package org.agentos.memory;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Embedder (Java tokenizer port + ONNX Runtime + mean pooling) against vectors computed in Python with the reference tokenizer. */
public final class VectorParity{public static void main(String[] a)throws Exception{
 Embedder e=new Embedder(Bpe.open(new File(a[0])),a[1]);
 List<String> texts=Files.readAllLines(Paths.get(a[2]));List<String> ref=Files.readAllLines(Paths.get(a[3]));
 double worst=1;long ns=0;int n=0;
 for(int i=0;i<texts.size();i++){String t=new String(Base64.getDecoder().decode(texts.get(i)),StandardCharsets.UTF_8);
  long t0=System.nanoTime();float[] v=e.encode(t,false);ns+=System.nanoTime()-t0;n++;
  String[] r=ref.get(i).split(" ");double dot=0;for(int k=0;k<v.length;k++)dot+=v[k]*Double.parseDouble(r[k]);worst=Math.min(worst,dot);}
 System.out.printf("%d texts, %d dims; lowest cosine to reference %.6f; %.1f ms per text%n",n,e.encode("x",false).length,worst,ns/1e6/n);
}}
