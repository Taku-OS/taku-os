package org.agentos.memory;
import java.nio.file.*;import java.util.*;import java.nio.charset.StandardCharsets;
public final class DumpWords{public static void main(String[] a)throws Exception{
 StringBuilder out=new StringBuilder();for(String l:Files.readAllLines(Paths.get(a[0]))){String q=new String(Base64.getDecoder().decode(l),StandardCharsets.UTF_8);List<String> w=Recall.sentenceWords(q);
  out.append(w==null?"-":String.join("\u0001",w)).append('\n');}
 Files.write(Paths.get(a[1]),out.toString().getBytes(StandardCharsets.UTF_8));}}
