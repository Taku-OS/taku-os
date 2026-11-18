package org.agentos.memory;

import ai.onnxruntime.*;
import java.io.*;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * On-device text embeddings: bge-small-zh-v1.5 (ONNX, dynamically quantized export, 512 dims) with an exact port of its BERT tokenizer
 * (clean text, CJK characters split, no lowercasing, greedy WordPiece, [CLS] pooling, L2 normalized).
 * Queries get the bge retrieval prefix; documents do not. Runs locally; no text leaves the device.
 */
final class Embedder implements AutoCloseable {
 static final String QUERY_PREFIX="为这个句子生成表示以用于检索相关文章：";
 /** Which export of the model the stored vectors come from; a change re-embeds everything (MemoryStore.ensureVectors). */
 static final String MODEL_ID="bge-small-zh-v1.5/model_quantized";
 static final int DIM=512,MAX_TOKENS=512;
 final Map<String,Integer> vocab=new HashMap<>();
 final OrtEnvironment env;final OrtSession session;final boolean typeIds;

 Embedder(InputStream vocabFile,String modelPath)throws Exception{
  try(BufferedReader r=new BufferedReader(new InputStreamReader(vocabFile,StandardCharsets.UTF_8))){String line;int id=0;while((line=r.readLine())!=null)vocab.put(line,id++);}
  env=OrtEnvironment.getEnvironment();
  OrtSession.SessionOptions o=new OrtSession.SessionOptions();o.setIntraOpNumThreads(2);
  session=env.createSession(modelPath,o);typeIds=session.getInputNames().contains("token_type_ids");
 }

 float[] encode(String text,boolean query)throws Exception{
  int[] ids=tokenize(query?QUERY_PREFIX+text:text);
  long[] in=new long[ids.length],mask=new long[ids.length],types=new long[ids.length];
  for(int i=0;i<ids.length;i++){in[i]=ids[i];mask[i]=1;}
  long[] shape={1,ids.length};
  Map<String,OnnxTensor> feed=new HashMap<>();
  try{
   feed.put("input_ids",OnnxTensor.createTensor(env,LongBuffer.wrap(in),shape));
   feed.put("attention_mask",OnnxTensor.createTensor(env,LongBuffer.wrap(mask),shape));
   if(typeIds)feed.put("token_type_ids",OnnxTensor.createTensor(env,LongBuffer.wrap(types),shape));
   try(OrtSession.Result out=session.run(feed)){
    float[][][] h=(float[][][])out.get(0).getValue();float[] v=Arrays.copyOf(h[0][0],DIM);
    double n=0;for(float x:v)n+=x*x;n=Math.sqrt(n);if(n>0)for(int i=0;i<v.length;i++)v[i]/=n;return v;
   }
  }finally{for(OnnxTensor t:feed.values())t.close();}
 }

 int[] tokenize(String text){
  List<Integer> out=new ArrayList<>();out.add(vocab.get("[CLS]"));
  int unk=vocab.get("[UNK]");
  for(String word:preTokenize(normalize(text))){
   if(out.size()>=MAX_TOKENS-1)break;
   wordPiece(word,out,unk);
  }
  while(out.size()>MAX_TOKENS-1)out.remove(out.size()-1);
  out.add(vocab.get("[SEP]"));
  int[] r=new int[out.size()];for(int i=0;i<r.length;i++)r[i]=out.get(i);return r;
 }

 void wordPiece(String word,List<Integer> out,int unk){
  int[] cps=word.codePoints().toArray();
  if(cps.length>100){out.add(unk);return;}
  List<Integer> pieces=new ArrayList<>();int start=0;
  while(start<cps.length){
   int end=cps.length;Integer found=null;
   while(start<end){
    String sub=new String(cps,start,end-start);if(start>0)sub="##"+sub;
    Integer id=vocab.get(sub);if(id!=null){found=id;break;}end--;
   }
   if(found==null){out.add(unk);return;}
   pieces.add(found);start=end;
  }
  out.addAll(pieces);
 }

 /** BertNormalizer(clean_text, handle_chinese_chars, lowercase=false, strip_accents=None) */
 static String normalize(String text){
  StringBuilder b=new StringBuilder(text.length()+16);
  text.codePoints().forEach(c->{
   if(c==0||c==0xFFFD||isControl(c))return;
   if(isWhitespace(c)){b.append(' ');return;}
   if(isChinese(c)){b.append(' ').appendCodePoint(c).append(' ');return;}
   b.appendCodePoint(c);
  });
  return b.toString();
 }
 /** BertPreTokenizer: split on whitespace, isolate punctuation */
 static List<String> preTokenize(String text){
  List<String> words=new ArrayList<>();StringBuilder cur=new StringBuilder();
  int[] cps=text.codePoints().toArray();
  for(int c:cps){
   if(isWhitespace(c)){if(cur.length()>0){words.add(cur.toString());cur.setLength(0);}continue;}
   if(isPunctuation(c)){if(cur.length()>0){words.add(cur.toString());cur.setLength(0);}words.add(new String(Character.toChars(c)));continue;}
   cur.appendCodePoint(c);
  }
  if(cur.length()>0)words.add(cur.toString());
  return words;
 }
 static boolean isWhitespace(int c){
  if(c=='\t'||c=='\n'||c=='\r'||c==' ')return true;
  return (c>=0x9&&c<=0xD)||c==0x85||c==0xA0||c==0x1680||(c>=0x2000&&c<=0x200A)||c==0x2028||c==0x2029||c==0x202F||c==0x205F||c==0x3000;
 }
 static boolean isControl(int c){
  if(c=='\t'||c=='\n'||c=='\r')return false;
  int t=Character.getType(c);
  return t==Character.CONTROL||t==Character.FORMAT||t==Character.UNASSIGNED||t==Character.PRIVATE_USE||t==Character.SURROGATE;
 }
 static boolean isPunctuation(int c){
  if((c>=33&&c<=47)||(c>=58&&c<=64)||(c>=91&&c<=96)||(c>=123&&c<=126))return true;
  int t=Character.getType(c);
  return t==Character.CONNECTOR_PUNCTUATION||t==Character.DASH_PUNCTUATION||t==Character.START_PUNCTUATION||t==Character.END_PUNCTUATION
   ||t==Character.INITIAL_QUOTE_PUNCTUATION||t==Character.FINAL_QUOTE_PUNCTUATION||t==Character.OTHER_PUNCTUATION;
 }
 static boolean isChinese(int c){
  return (c>=0x4E00&&c<=0x9FFF)||(c>=0x3400&&c<=0x4DBF)||(c>=0x20000&&c<=0x2A6DF)||(c>=0x2A700&&c<=0x2B73F)
   ||(c>=0x2B740&&c<=0x2B81F)||(c>=0x2B920&&c<=0x2CEAF)||(c>=0xF900&&c<=0xFAFF)||(c>=0x2F800&&c<=0x2FA1F);
 }

 static byte[] pack(float[] v){java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(v.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(float x:v)b.putFloat(x);return b.array();}
 static float[] unpack(byte[] a){java.nio.FloatBuffer b=java.nio.ByteBuffer.wrap(a).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] v=new float[b.remaining()];b.get(v);return v;}
 static float dot(float[] a,float[] b){float s=0;for(int i=0;i<a.length;i++)s+=a[i]*b[i];return s;}

 @Override public void close(){try{session.close();}catch(Exception ignored){}}
}
